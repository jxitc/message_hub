#!/usr/bin/env python3
"""Import Evernote `.enex` exports into Message Hub.

Why a script and not paste: a notebook is thousands of notes plus attachments. The
export carries everything worth keeping — title, body, tags, created/updated,
source URL and the resources themselves — and all of it maps onto the model we
already have:

    正文          -> content            (文本，可搜索；下游 AI 直接读)
    标签/笔记本    -> metadata.tags / metadata.notebook
    创建/更新时间  -> timestamp          (用 created，不是导入时间)
    来源链接       -> metadata.url
    附件           -> blob + metadata.attachments，**并且会走现有的提取流水线**
                     （图片自动 OCR、PDF 抽文本 —— 等于连你笔记里的扫描件也变成可搜文本）

Two properties matter more than speed here:

* **Idempotent.** Each note's Evernote GUID is stored in `metadata.evernote_guid`
  and skipped on a re-run, so importing the same export twice cannot duplicate the
  archive. Interrupted imports just resume.
* **Same validation as every other channel.** It calls
  `message_ingest.create_message()`, the function the REST API and the web page
  share, so the size cap and the type whitelist are not silently bypassed by a
  bulk path. Resources over the cap are recorded in `attachments_skipped` with a
  reason rather than dropped silently.

Parsing streams the XML (`iterparse`) because exports run to hundreds of MB and the
target box has 960 MB of RAM.

    # dry run first — always
    ./venv/bin/python scripts/import-evernote.py --file notes.enex
    ./venv/bin/python scripts/import-evernote.py --file notes.enex --apply

    # 大导出可以先只导前 N 条看看效果
    ./venv/bin/python scripts/import-evernote.py --file notes.enex --limit 20 --apply
"""

import argparse
import base64
import binascii
import hashlib
import os
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import blob_store  # noqa: E402
import mail_collector as mc  # noqa: E402  (reuse its HTML->text converter)
import message_ingest  # noqa: E402
from app import create_app  # noqa: E402
from models import db, Message  # noqa: E402

#: 导入的笔记算哪个"设备"。与手机、网页版同理：手动来源用来源标识，不用人名。
DEVICE_ID = 'evernote-import'
SENDER = 'Evernote'

#: `<en-media hash="...">` 指向附件；正文里把它换成可读的文件名占位。
_EN_MEDIA = re.compile(r'<en-media\b[^>]*/?>', re.I)
_HASH_ATTR = re.compile(r'hash="([0-9a-fA-F]+)"')
_TODO = re.compile(r'<en-todo\b[^>]*/?>', re.I)


class UploadedBlob:
    """`message_ingest.store_attachments` 要的是 FileStorage 形状的对象。

    这里只实现它用到的两个成员（filename / read），这样脚本不必伪造 HTTP 请求，
    却仍然走**同一份**限额与白名单校验。
    """

    def __init__(self, filename, data):
        self.filename = filename
        self._data = data

    def read(self):
        return self._data


def parse_evernote_time(value):
    """`20260101T120000Z` → aware datetime（Evernote 的时间都是 UTC）。"""
    if not value:
        return None
    raw = value.strip().rstrip('Z')
    for fmt in ('%Y%m%dT%H%M%S', '%Y%m%dT%H%M%S'):
        try:
            return datetime.strptime(raw, fmt).replace(tzinfo=timezone.utc)
        except ValueError:
            continue
    return None


def note_text(content_element, resource_names):
    """ENML（XHTML）→ 纯文本，并把 `<en-media>` 换成对应的文件名。

    复用 mail_collector 里的 HTML→文本 转换器：同一套清理规则，不另写一份。
    """
    if content_element is None or not (content_element.text or '').strip():
        return ''
    enml = content_element.text

    def replace_media(match):
        found = _HASH_ATTR.search(match.group(0))
        if not found:
            return ''
        name = resource_names.get(found.group(1).lower())
        return '\n[附件: %s]\n' % name if name else '\n[附件]\n'

    enml = _EN_MEDIA.sub(replace_media, enml)
    enml = _TODO.sub('[ ] ', enml)
    text = mc.html_to_text(enml)
    # 折叠多余空行：Evernote 的 ENML 里 <div> 层级很深，转文本后经常一堆空行
    text = re.sub(r'\n{3,}', '\n\n', text)
    return text.strip()


def read_resources(note, limit_hint=None):
    """返回 (files, skipped, names_by_hash)。

    files:   [UploadedBlob] —— 交给共享的入库逻辑去校验与存储
    skipped: [{name, size, mime, reason}] —— 超限/校验不通过的，记下来而不是丢
    """
    files, skipped, by_hash = [], [], {}
    for resource in note.findall('resource'):
        data_el = resource.find('data')
        mime = (resource.findtext('mime') or 'application/octet-stream').strip()
        attrs = resource.find('resource-attributes')
        name = None
        if attrs is not None:
            name = (attrs.findtext('file-name') or '').strip() or None
        raw_text = (data_el.text or '') if data_el is not None else ''

        try:
            payload = base64.b64decode(re.sub(r'\s+', '', raw_text))
        except (binascii.Error, ValueError):
            skipped.append({'name': name or '(未知附件)', 'size': 0, 'mime': mime,
                            'reason': 'base64 解码失败'})
            continue

        if not name:
            ext = {'image/png': '.png', 'image/jpeg': '.jpg', 'application/pdf': '.pdf'}.get(mime, '.bin')
            name = 'evernote-resource%s' % ext
        # ENEX 的 <resource> 里**没有** hash 字段：正文里的 <en-media hash="...">
        # 引用的是资源数据的 MD5，得自己算出来才能把附件名对应回正文。
        by_hash[hashlib.md5(payload).hexdigest()] = name

        if not payload:
            skipped.append({'name': name, 'size': 0, 'mime': mime, 'reason': '空附件'})
            continue

        # 逐个资源先按**同一份**规则校验（blob_store.validate_upload），但失败时记为
        # "未存储"而不是让整条笔记失败。理由：上传接口里 413 是硬错误，因为客户端能压缩
        # 重试；而批量导入时谁也没法压缩那个附件，笔记的正文却是有价值的 —— 丢整条笔记
        # 才是真正的数据损失。这也与邮件侧的 attachments_skipped 词汇一致。
        try:
            blob_store.validate_upload(name, payload)
        except blob_store.BlobError as exc:
            reason = ('超过 %d KB 上限，未存储' % (blob_store.MAX_ATTACHMENT_BYTES // 1024)
                      if exc.status == 413 else '类型不受支持，未存储：%s' % exc.message)
            skipped.append({'name': name, 'size': len(payload), 'mime': mime,
                            'reason': reason})
            continue
        files.append(UploadedBlob(name, payload))
    return files, skipped, by_hash


def import_note(note, existing_guids, apply_changes=True):
    """Import one `<note>` element. Returns (status, detail)."""
    guid_el = note.find('note-attributes/guid') if note.find('note-attributes') is not None else None
    guid = (guid_el.text or '').strip() if guid_el is not None else ''
    if not guid:
        # 没有 GUID 的极老导出：用标题+创建时间做一个稳定键
        title = (note.findtext('title') or '').strip()
        guid = 'no-guid:%s:%s' % (title[:80], note.findtext('created') or '')
    if guid in existing_guids:
        return 'skipped', guid

    title = (note.findtext('title') or '').strip()
    files, skipped, names_by_hash = read_resources(note)
    body = note_text(note.find('content'), names_by_hash)
    created = parse_evernote_time(note.findtext('created')) or datetime.now(timezone.utc)

    attrs = note.find('note-attributes')
    metadata = {'evernote_guid': guid}
    if title:
        metadata['title'] = title
    tags = [(t.text or '').strip() for t in note.findall('tag') if (t.text or '').strip()]
    if tags:
        metadata['tags'] = tags
    notebook = (attrs.findtext('notebook') if attrs is not None else None) or ''
    if notebook:
        metadata['notebook'] = notebook.strip()
    source_url = (attrs.findtext('source-url') if attrs is not None else None) or ''
    if source_url:
        metadata['url'] = source_url.strip()
    updated = parse_evernote_time(note.findtext('updated'))
    if updated:
        metadata['evernote_updated'] = updated.isoformat()

    # 正文为空但有附件是合法状态（提取出来的文字之后会填进 content）。
    attachments, rejected = message_ingest.store_attachments(files)
    if rejected:
        skipped.extend(rejected)
    if skipped:
        metadata['attachments_skipped'] = skipped

    if not body and not attachments:
        return 'empty', guid           # 空笔记：不占时间线

    message, stored, _rej = message_ingest.create_message({
        'source_device_id': DEVICE_ID,
        'type': 'NOTE',
        'sender': SENDER,
        'content': body,
        'timestamp': created,
        'metadata': metadata,
    }, files, source='evernote-import')
    return ('created', message) if apply_changes else ('would-create', message)


def iter_notes(path):
    """流式产出 `<note>` 元素 —— 导出文件可能几百 MB，不能整棵读进内存。"""
    context = ET.iterparse(path, events=('end',))
    for _event, element in context:
        if element.tag == 'note':
            yield element
            element.clear()


def existing_guids():
    guids = set()
    for (metadata,) in db.session.query(Message.message_metadata).all():
        guid = (metadata or {}).get('evernote_guid')
        if guid:
            guids.add(guid)
    return guids


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--file', required=True, help='.enex 文件路径')
    parser.add_argument('--apply', action='store_true', help='真正写入（默认只演练）')
    parser.add_argument('--limit', type=int, default=0, help='只处理前 N 条（0=不限）')
    parser.add_argument('--device-id', default=DEVICE_ID,
                        help='source_device_id（默认 %s）' % DEVICE_ID)
    args = parser.parse_args()

    if not os.path.exists(args.file):
        print('找不到文件：%s' % args.file)
        return 1
    globals()['DEVICE_ID'] = args.device_id

    app = create_app()
    with app.app_context():
        known = existing_guids()
        print('已有 %d 条 Evernote 笔记（按 GUID 去重）' % len(known))
        print('%s：%s\n' % ('演练（加 --apply 才写入）' if not args.apply else '开始导入',
                            args.file))

        counts = {'created': 0, 'skipped': 0, 'empty': 0, 'failed': 0, 'would-create': 0}
        with_attachments = 0
        for index, note in enumerate(iter_notes(args.file), 1):
            if args.limit and counts['created'] + counts['would-create'] >= args.limit:
                print('\n达到 --limit %d，停下。' % args.limit)
                break
            try:
                status, payload = import_note(note, known)
            except Exception as exc:  # noqa: BLE001 - 一条坏笔记不该中断整批
                counts['failed'] += 1
                print('  ✗ 第 %d 条失败：%s' % (index, exc))
                db.session.rollback()
                continue

            if status == 'created':
                if args.apply:
                    db.session.commit()
                else:
                    db.session.rollback()
                known.add(payload.message_metadata['evernote_guid'])
                counts['created'] += 1
                title = payload.message_metadata.get('title') or payload.content[:40]
                n_att = len(payload.message_metadata.get('attachments') or [])
                with_attachments += 1 if n_att else 0
                if counts['created'] <= 5 or n_att:
                    print('  + %-46s %s%s' % (title[:46], payload.type,
                                              '  [%d 个附件]' % n_att if n_att else ''))
            elif status == 'skipped':
                counts['skipped'] += 1
            elif status == 'empty':
                counts['empty'] += 1

        if args.apply:
            db.session.commit()
        else:
            db.session.rollback()

        print('\n结果：%s %d 条，跳过（已导入过）%d 条，空笔记 %d 条，失败 %d 条'
              % ('将导入' if not args.apply else '已导入', counts['created'],
                 counts['skipped'], counts['empty'], counts['failed']))
        if with_attachments:
            print('其中 %d 条带附件；附件文本由后台提取器处理（图片 OCR / PDF 抽取）。' % with_attachments)
        if not args.apply:
            print('确认无误后加 --apply 真正写入。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
