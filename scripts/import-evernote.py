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

* **Idempotent.** Each note gets a key stored in `metadata.evernote_key` — Evernote's
  GUID when the export has one, otherwise `sha1(title + creation time)`. Newer
  exports (v11) carry no GUID at all, so the derived key is the normal path. It is
  stable across edits, so re-importing a later export stays a no-op instead of
  minting duplicates, and it deliberately does **not** hash the body for that reason.
  Interrupted imports resume; `--dry-run` writes nothing.
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
import collections
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

#: 归档导入的附件上限，**故意大于客户端上传的 1MB**。
#:
#: 1MB 是为手机/网页上传定的（移动流量、1 核机器、图片可压缩）；本地导入一份已有档案时
#: 这些约束都不成立，而这个档案里最有价值的文件恰恰超过 1MB —— 护照、签证函、竞业协议、
#: 户口本、13MB 的施工图。用 1MB 会把它们全部跳过，那是拿技术限制去砍掉用户最重要的资料。
#: 可用 --max-attachment-bytes 覆盖。
IMPORT_MAX_ATTACHMENT_BYTES = int(
    os.environ.get('IMPORT_MAX_ATTACHMENT_BYTES') or 25 * 1024 * 1024)

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

    额外带上 Evernote 给的图片尺寸与资源出处：它们在附件详情页上有用（"尺寸多大"、
    "这张图从哪个网页来的"），而我们自己的上传路径拿不到这些。
    """

    def __init__(self, filename, data, width=None, height=None, source_url=None):
        self.filename = filename
        self._data = data
        self.width = width
        self.height = height
        self.source_url = source_url

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


def read_resources(note, max_bytes=None):
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
        limit = max_bytes or IMPORT_MAX_ATTACHMENT_BYTES
        try:
            # 关键：把**导入用的上限**传下去。之前只在这里比了一下大小，而 validate_upload
            # 内部仍按客户端的 1MB 判定 —— 结果是 1MB 以上的附件全被拒，包括护照扫描件、
            # 签证函、竞业协议。上限必须一路传到真正做判断的那一层。
            blob_store.validate_upload(name, payload, max_bytes=limit)
        except blob_store.BlobError as exc:
            reason = ('超过 %d MB 导入上限，未存储' % (limit // 1048576)
                      if exc.status == 413 else '类型不受支持，未存储：%s' % exc.message)
            skipped.append({'name': name, 'size': len(payload), 'mime': mime,
                            'reason': reason})
            continue
        try:
            width = int((resource.findtext('width') or '').strip() or 0) or None
            height = int((resource.findtext('height') or '').strip() or 0) or None
        except ValueError:
            width = height = None
        resource_url = ''
        if attrs is not None:
            resource_url = (attrs.findtext('source-url') or '').strip()

        files.append(UploadedBlob(name, payload, width=width, height=height,
                                  source_url=resource_url))
    return files, skipped, by_hash


def dedup_key(note, title):
    """Identity of a note for the purpose of not importing it twice.

    Prefers Evernote's GUID, but **newer exports (v11) do not include one at all** —
    confirmed against the real 338-note export, whose `note-attributes` carried only
    author and source. So the fallback is the normal path here, not an edge case.

    The fallback is title + creation time, chosen deliberately:

    * **Stable.** Editing a note does not change either, so re-importing a later
      export stays a no-op instead of minting duplicates. That is the property this
      import actually needs — Evernote is being retired, this is a one-time move.
    * **Not title alone.** Titles repeat heavily in real archives: this one has 26
      notes called "无标题笔记", 10 called "未命名 - 名片", 5 "王艳 - 名片", 4 "婚礼".
      Hashing the title by itself would treat those as one note and silently drop 25
      of the 26. Creation time separates them; across all 338 notes the pair is
      unique (checked).
    * Body and resource hashes are deliberately **not** part of the key: they would
      make an edited note import a second time, which is the opposite of the goal.

    Residual risk, made visible rather than silent: two notes sharing both a title
    and a creation timestamp would collide. `main()` counts and reports those.
    """
    attrs = note.find('note-attributes')
    guid = ((attrs.findtext('guid') if attrs is not None else None) or '').strip()
    if guid:
        return 'guid:' + guid, guid

    material = (title + '\x00' + (note.findtext('created') or '')).encode('utf-8', 'replace')
    return 'sha1:' + hashlib.sha1(material).hexdigest(), ''


def import_note(note, existing_keys, apply_changes=True):
    """Import one `<note>` element. Returns (status, detail)."""
    title = (note.findtext('title') or '').strip()
    files, skipped, names_by_hash = read_resources(note)
    body = note_text(note.find('content'), names_by_hash)
    created = parse_evernote_time(note.findtext('created')) or datetime.now(timezone.utc)

    key, guid = dedup_key(note, title)
    if key in existing_keys:
        return 'skipped', {'key': key, 'title': title}

    attrs = note.find('note-attributes')
    # Evernote 专属的东西收进一个命名空间：通用概念（title/tags/url）保持扁平，
    # 渠道细节集中在 metadata.evernote 下，符合"渠道不进 schema"的那条原则。
    evernote_meta = {'key': key}
    if guid:
        evernote_meta['guid'] = guid
    updated = parse_evernote_time(note.findtext('updated'))
    if updated:
        evernote_meta['updated'] = updated.isoformat()
    if attrs is not None:
        for tag, name in (('author', 'author'), ('source', 'source'),
                          ('source-application', 'source_application'),
                          ('content-class', 'content_class'),
                          ('subject-date', 'subject_date')):
            value = (attrs.findtext(tag) or '').strip()
            if value:
                evernote_meta[name] = value
    metadata = {'evernote': evernote_meta}
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

    # 正文为空但有附件是合法状态（提取出来的文字之后会填进 content）。
    if not body and not files:
        return 'empty', {'key': key, 'title': title, 'chars': 0,
                         'attachments': 0, 'skipped': len(skipped),
                         'skipped_names': [s['name'] for s in skipped]}

    info = {'key': key, 'title': title, 'chars': len(body),
            'attachments': len(files), 'skipped': len(skipped),
            'skipped_names': [s['name'] for s in skipped]}

    if not apply_changes:
        # 演练**不写盘**：附件字节是在提交数据库行之前写入的，即使回滚数据库，
        # 演练也会在磁盘上留下上百 MB 的孤儿文件。这里只做校验与统计。
        return 'would-create', info

    # skipped 来自 read_resources（超限/类型不支持）**和** 入库时的配额检查，
    # 只要非空就必须写进 metadata —— 否则"这条笔记有附件但没存下来"的记录会静默消失，
    # 而那正是超限附件唯一留下的痕迹。
    if skipped:
        metadata['attachments_skipped'] = skipped

    # 注意：**不要**在这里自己调 store_attachments —— create_message 内部会调一次，
    # 它用自己新生成的记录覆盖 metadata['attachments']，先前标注的字段会被丢掉
    # （而且白读一遍附件字节）。所以先建消息，再给**真正入库的那组记录**补字段。
    message, stored, rejected = message_ingest.create_message({
        'source_device_id': DEVICE_ID,
        'type': 'NOTE',
        'sender': SENDER,
        'content': body,
        'timestamp': created,
        'metadata': metadata,
    }, files, source='evernote-import',
        max_attachment_bytes=IMPORT_MAX_ATTACHMENT_BYTES)

    if rejected:
        current = dict(message.message_metadata or {})
        current['attachments_skipped'] = (current.get('attachments_skipped') or []) + rejected
        message.message_metadata = current

    annotate_attachments(message, files)
    info['message'] = message
    info['stored'] = len(stored)
    return 'created', info


def annotate_attachments(message, files):
    """Add what only Evernote knows to the attachment records that were stored.

    Dimensions and the resource's own source URL are useful on the attachment page
    ("how big is it", "where did this image come from") and our own upload path
    cannot supply them. Records are matched by file name, consuming duplicates in
    order so repeated names line up one-to-one.
    """
    pending = collections.defaultdict(list)
    for uploaded in files:
        pending[uploaded.filename].append(uploaded)

    metadata = dict(message.message_metadata or {})
    records = list(metadata.get('attachments') or [])
    if not records:
        return
    for index, record in enumerate(records):
        candidates = pending.get(record.get('name') or '')
        uploaded = candidates.pop(0) if candidates else None
        if uploaded is None:
            continue
        record = dict(record)                      # 不原地改，避免共享引用
        if uploaded.width and uploaded.height:
            record['width'], record['height'] = uploaded.width, uploaded.height
        if uploaded.source_url:
            record['source_url'] = uploaded.source_url
        records[index] = record
    metadata['attachments'] = records
    message.message_metadata = metadata


def iter_notes(path):
    """流式产出 `<note>` 元素 —— 导出文件可能几百 MB，不能整棵读进内存。"""
    context = ET.iterparse(path, events=('end',))
    for _event, element in context:
        if element.tag == 'note':
            yield element
            element.clear()


def existing_keys():
    """Keys of notes already imported (either form — see dedup_key)."""
    keys = set()
    for (metadata,) in db.session.query(Message.message_metadata).all():
        node = (metadata or {}).get('evernote') or {}
        if node.get('key'):
            keys.add(node['key'])
        if node.get('guid'):
            keys.add('guid:' + node['guid'])
        # 兼容最初那版扁平键（万一有数据先按老结构导进去了）
        if (metadata or {}).get('evernote_key'):
            keys.add(metadata['evernote_key'])
        if (metadata or {}).get('evernote_guid'):
            keys.add('guid:' + metadata['evernote_guid'])
    return keys


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--file', required=True, help='.enex 文件路径')
    parser.add_argument('--apply', action='store_true', help='真正写入（默认只演练）')
    parser.add_argument('--limit', type=int, default=0, help='只处理前 N 条（0=不限）')
    parser.add_argument('--max-attachment-bytes', type=int, default=None,
                        help='导入时的单附件上限（默认 %d MB；客户端上传的 1MB 限制不适用）'
                             % (IMPORT_MAX_ATTACHMENT_BYTES // 1048576))
    parser.add_argument('--device-id', default=DEVICE_ID,
                        help='source_device_id（默认 %s）' % DEVICE_ID)
    args = parser.parse_args()

    if not os.path.exists(args.file):
        print('找不到文件：%s' % args.file)
        return 1
    globals()['DEVICE_ID'] = args.device_id
    if args.max_attachment_bytes:
        globals()['IMPORT_MAX_ATTACHMENT_BYTES'] = args.max_attachment_bytes

    app = create_app()
    with app.app_context():
        known = existing_keys()
        preexisting = set(known)
        seen_in_file = {}
        collisions = []
        print('已有 %d 条 Evernote 笔记（键 = GUID，或 标题+创建时间）' % len(known))
        print('%s：%s\n' % ('演练（加 --apply 才写入）' if not args.apply else '开始导入',
                            args.file))

        counts = {'created': 0, 'skipped': 0, 'empty': 0, 'failed': 0, 'would-create': 0}
        with_attachments = 0
        stored_total = 0
        for index, note in enumerate(iter_notes(args.file), 1):
            if args.limit and counts['created'] + counts['would-create'] >= args.limit:
                print('\n达到 --limit %d，停下。' % args.limit)
                break
            try:
                status, payload = import_note(note, known, apply_changes=args.apply)
            except Exception as exc:  # noqa: BLE001 - 一条坏笔记不该中断整批
                counts['failed'] += 1
                print('  ✗ 第 %d 条失败：%s' % (index, exc))
                db.session.rollback()
                continue

            if status in ('created', 'would-create'):
                seen_in_file.setdefault(payload['key'], payload['title'])
                # 只有真导入才提交。演练走 rollback —— 之前这里写成无条件 commit，
                # 等于把"演练"变成了真导入（并且 apply_changes 没传，附件也落了盘）。
                if status == 'created':
                    if args.apply:
                        db.session.commit()
                    else:
                        db.session.rollback()
                known.add(payload['key'])
                counts[status] += 1
                if payload['attachments']:
                    with_attachments += 1
                    stored_total += payload['attachments']
                    stored_bytes_hint = True
                if counts[status] <= 5 or payload['attachments']:
                    print('  + %-44s %s%s' % (
                        payload['title'][:44], '%d 字' % payload['chars'],
                        '  [%d 个附件%s]' % (payload['attachments'],
                                           '，%d 个未存储' % payload['skipped']
                                           if payload['skipped'] else '')))
                if payload['skipped_names'] and len(payload['skipped_names']) <= 3:
                    for name in payload['skipped_names']:
                        print('        ⚠️ 未存储: %s' % name[:56])
            elif status == 'skipped':
                counts['skipped'] += 1
                key = payload.get('key', '')
                first = seen_in_file.get(key)
                if key and key not in preexisting and first is not None:
                    # 同一份文件里出现两次相同的键：第二条会被当成"已导入过"跳过。
                    # 这就是那条残余风险，必须显式报出来，而不是伪装成正常的跳过。
                    collisions.append((first, payload.get('title', ''), key[:12]))
                elif key:
                    seen_in_file.setdefault(key, payload.get('title', ''))
            elif status == 'empty':
                counts['empty'] += 1

        if args.apply:
            db.session.commit()
        else:
            db.session.rollback()

        imported = counts['created'] + counts['would-create']
        print('\n结果：%s %d 条，跳过（已导入过）%d 条，空笔记 %d 条，失败 %d 条'
              % ('将导入' if not args.apply else '已导入', imported,
                 counts['skipped'], counts['empty'], counts['failed']))
        if with_attachments:
            print('其中 %d 条带附件，共 %d 个附件会入库；附件文本由后台提取器处理'
                  '（图片 OCR / PDF 抽取）。' % (with_attachments, stored_total))
        if collisions:
            print('\n⚠️ **同文件内有 %d 条笔记的键与前面重复**，它们会被当成重复跳过：'
                  % len(collisions))
            for first, second, key in collisions[:5]:
                print('    %r ↔ %r  (key %s…)' % (first[:30], second[:30], key))
            print('    说明有笔记"标题与创建时间都相同"。加 --device-id 换一个标识重跑，')
            print('    或先用 --limit 定位是哪几条。')
        if not args.apply:
            print('确认无误后加 --apply 真正写入。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
