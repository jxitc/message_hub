#!/usr/bin/env python3
"""Profile a `.enex` export before importing it — inventory only, no writes.

Answers the questions you actually have before a bulk import, by streaming the XML
once:

  · 多少条笔记？时间跨度？标签/笔记本长什么样？
  · 附件有多少、都是什么类型、多大？**哪些会因为超过上限而被跳过？**
  · 正文是空的（只有附件）的笔记有多少？
  · 大概要花多少提取时间？值不值得先在本地做 OCR？

It never prints note bodies — only titles, counts and aggregate stats — so a 300MB
archive does not end up in an LLM context. `--samples N --out DIR` writes a handful
of real attachments to disk so you can test text extraction on those before
committing to a batch run.

Sizes are estimated from the base64 length (×3/4) instead of decoding every resource:
decoding 300MB of base64 purely to measure it wastes minutes for no benefit.

    ./venv/bin/python scripts/profile-enex.py --file notes.enex
    ./venv/bin/python scripts/profile-enex.py --file notes.enex --samples 6 --out /tmp/enex-samples
"""

import argparse
import base64
import binascii
import collections
import os
import re
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from blob_store import MAX_ATTACHMENT_BYTES  # noqa: E402

#: 只保留这么多个"最大附件"的 base64（用来抽样看大文件长什么样），避免吃内存
MAX_KEEP_LARGEST = 5
#: 单个抽样附件上限（编码后的字符数）：更大的只记录不落盘
MAX_SAMPLE_CHARS = 40 * 1024 * 1024

_WS = re.compile(r'\s+')


def human(n):
    for unit in ('B', 'KB', 'MB', 'GB'):
        if n < 1024 or unit == 'GB':
            return '%.1f %s' % (n, unit) if unit != 'B' else '%d B' % n
        n /= 1024.0


def profile(path, samples=0, out_dir=None, titles=25, top_tags=15, progress_every=2000):
    stats = {
        'notes': 0, 'with_body': 0, 'attachment_only': 0, 'empty': 0, 'no_guid': 0,
        'with_url': 0, 'with_tags': 0,
        'attachments': 0, 'attachment_bytes': 0, 'over_limit': 0, 'over_limit_bytes': 0,
        'content_chars': 0,
    }
    by_mime = collections.Counter()
    bytes_by_mime = collections.Counter()
    buckets = collections.Counter()
    tags = collections.Counter()
    notebooks = collections.Counter()
    created_months = collections.Counter()
    sizes = []
    largest = []                 # [(encoded_chars, note_title, name, mime, text)]
    sample_titles = []
    over_limit_examples = []
    dumped = 0
    out_dir_abs = None
    if samples and out_dir:
        out_dir_abs = os.path.abspath(out_dir)
        os.makedirs(out_dir_abs, exist_ok=True)

    started = time.time()
    context = ET.iterparse(path, events=('end',))
    for _event, element in context:
        if element.tag != 'note':
            continue
        stats['notes'] += 1
        if stats['notes'] % progress_every == 0:
            sys.stderr.write('  … 已扫描 %d 条笔记（%.0fs）\n'
                             % (stats['notes'], time.time() - started))

        title = (element.findtext('title') or '').strip() or '(无标题)'
        if len(sample_titles) < titles:
            sample_titles.append(title)

        created = (element.findtext('created') or '').strip()
        if len(created) >= 6:
            created_months[created[:6]] += 1

        attrs = element.find('note-attributes')
        guid = (attrs.findtext('guid') if attrs is not None else None) or ''
        if not guid:
            stats['no_guid'] += 1
        if attrs is not None:
            notebook = (attrs.findtext('notebook') or '').strip()
            if notebook:
                notebooks[notebook] += 1
            if (attrs.findtext('source-url') or '').strip():
                stats['with_url'] += 1

        note_tags = [(t.text or '').strip() for t in element.findall('tag')]
        note_tags = [t for t in note_tags if t]
        if note_tags:
            stats['with_tags'] += 1
            tags.update(note_tags)

        content = element.find('content')
        body_chars = len((content.text or '').strip()) if content is not None else 0
        stats['content_chars'] += body_chars

        note_attachments = 0
        for resource in element.findall('resource'):
            note_attachments += 1
            stats['attachments'] += 1
            mime = (resource.findtext('mime') or 'application/octet-stream').strip()
            attrs_r = resource.find('resource-attributes')
            name = ((attrs_r.findtext('file-name') if attrs_r is not None else None)
                    or '').strip() or '(无名)'
            data_el = resource.find('data')
            raw_text = (data_el.text or '') if data_el is not None else ''
            encoded_chars = len(_WS.sub('', raw_text))
            approx = encoded_chars * 3 // 4
            stats['attachment_bytes'] += approx
            by_mime[mime] += 1
            bytes_by_mime[mime] += approx
            sizes.append(approx)

            if approx > MAX_ATTACHMENT_BYTES:
                stats['over_limit'] += 1
                stats['over_limit_bytes'] += approx
                if len(over_limit_examples) < 10:
                    over_limit_examples.append((title, name, mime, approx))

            # 分桶：给"附件都多大"一个直观分布
            for edge in (64 * 1024, 256 * 1024, 1024 * 1024, 5 * 1024 * 1024):
                if approx < edge:
                    buckets['< %s' % human(edge)] += 1
                    break
            else:
                buckets['>= 5.0 MB'] += 1

            # 抽样落盘：先到先得（不需要留内存）
            if samples and dumped < samples and out_dir_abs and encoded_chars < MAX_SAMPLE_CHARS:
                try:
                    payload = base64.b64decode(_WS.sub('', raw_text))
                    safe = re.sub(r'[^A-Za-z0-9._-]', '_', name)[:60] or 'sample'
                    target = os.path.join(out_dir_abs, '%02d-%s' % (dumped + 1, safe))
                    with open(target, 'wb') as fh:
                        fh.write(payload)
                    dumped += 1
                except (binascii.Error, ValueError, OSError):
                    pass

            # 另外记住最大的几个（含 base64，供看完统计后单独落盘）
            if encoded_chars < MAX_SAMPLE_CHARS:
                largest.append((encoded_chars, title, name, mime, raw_text))
                largest.sort(key=lambda item: item[0], reverse=True)
                del largest[MAX_KEEP_LARGEST:]

        if body_chars == 0 and note_attachments == 0:
            stats['empty'] += 1
        elif body_chars == 0:
            stats['attachment_only'] += 1
        else:
            stats['with_body'] += 1

        element.clear()

    elapsed = time.time() - started
    return stats, dict(by_mime=by_mime, bytes_by_mime=bytes_by_mime, buckets=buckets,
                       tags=tags, notebooks=notebooks, months=created_months,
                       sizes=sizes, largest=largest, sample_titles=sample_titles,
                       over_limit_examples=over_limit_examples, dumped=dumped,
                       out_dir=out_dir_abs, elapsed=elapsed)


def report(path, stats, extra, top_tags):
    line = '─' * 62
    print(line)
    print('文件：%s（%s）' % (os.path.basename(path), human(os.path.getsize(path))))
    print('扫描耗时：%.0f 秒' % extra['elapsed'])
    print(line)

    print('\n【笔记】')
    print('  总数            %d' % stats['notes'])
    print('  有正文          %d' % stats['with_body'])
    print('  只有附件没正文   %d  ← 导入后正文会由提取结果填充' % stats['attachment_only'])
    print('  完全空          %d  ← 导入时会跳过' % stats['empty'])
    print('  有标签          %d（去重后 %d 个标签）' % (stats['with_tags'], len(extra['tags'])))
    print('  有来源链接      %d' % stats['with_url'])
    print('  缺 GUID         %d  ← 会用"标题+创建时间"当去重键' % stats['no_guid'])
    print('  正文总字符      %d（平均 %.0f 字/条）'
          % (stats['content_chars'], stats['content_chars'] / max(stats['notes'], 1)))

    months = sorted(extra['months'].items())
    if months:
        print('  时间跨度        %s → %s' % (months[0][0], months[-1][0]))

    print('\n【附件】')
    print('  总数            %d（约 %s）' % (stats['attachments'], human(stats['attachment_bytes'])))
    if stats['attachments']:
        print('  **超上限会被跳过** %d 个，约 %s ← 上限 %s'
              % (stats['over_limit'], human(stats['over_limit_bytes']), human(MAX_ATTACHMENT_BYTES)))
    print('  按类型：')
    for mime, count in extra['by_mime'].most_common(12):
        print('    %-28s %5d 个  %10s' % (mime, count, human(extra['bytes_by_mime'][mime])))
    print('  按大小：')
    for bucket in ('< 64.0 KB', '< 256.0 KB', '< 1.0 MB', '< 5.0 MB', '>= 5.0 MB'):
        if extra['buckets'].get(bucket):
            print('    %-12s %5d 个' % (bucket, extra['buckets'][bucket]))

    if extra['largest']:
        print('\n【最大的几个附件（抽样用）】')
        for chars, title, name, mime, _text in extra['largest']:
            print('  %10s  %-34s %-24s %s' % (human(chars * 3 // 4), name[:34], mime[:24], title[:40]))

    if extra['over_limit_examples']:
        print('\n【超限附件（导入后会记进 attachments_skipped）】')
        for title, name, mime, size in extra['over_limit_examples']:
            print('  %10s  %-32s %-22s %s' % (human(size), name[:32], mime[:22], title[:36]))

    if extra['tags']:
        print('\n【标签 Top %d】' % top_tags)
        for tag, count in extra['tags'].most_common(top_tags):
            print('  %-28s %5d' % (tag[:28], count))

    if extra['notebooks']:
        print('\n【笔记本 Top 10】')
        for book, count in extra['notebooks'].most_common(10):
            print('  %-28s %5d' % (book[:28], count))

    if extra['sample_titles']:
        print('\n【标题样例（前 %d 条，只取标题）】' % len(extra['sample_titles']))
        for title in extra['sample_titles']:
            print('  · %s' % title[:70])

    images = extra['by_mime'].get('image/jpeg', 0) + extra['by_mime'].get('image/png', 0) \
        + extra['by_mime'].get('image/gif', 0) + extra['by_mime'].get('image/webp', 0)
    pdfs = extra['by_mime'].get('application/pdf', 0)
    if images or pdfs:
        print('\n【提取工作量（粗估）】')
        print('  图片 %d 张 → 服务器 tesseract 约 %.0f 分钟（1 核，按 5 秒/张）'
              % (images, images * 5 / 60))
        print('  PDF  %d 个 → pdftotext 约 %.0f 分钟（有文本层的很快，扫描件回退 OCR 会慢很多）'
              % (pdfs, pdfs * 1 / 60))
        print('  ⚠️ 扫描版 PDF 会走"渲染+OCR"回退，每个可能需要 10–60 秒')

    if extra['out_dir']:
        print('\n抽样文件已写入：%s（%d 个）' % (extra['out_dir'], extra['dumped']))
    print()


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--file', required=True, help='.enex 文件')
    parser.add_argument('--samples', type=int, default=0,
                       help='把前 N 个附件写到 --out（用于先试提取效果）')
    parser.add_argument('--out', help='抽样输出目录')
    parser.add_argument('--titles', type=int, default=25, help='打印多少条标题样例')
    parser.add_argument('--top-tags', type=int, default=15)
    parser.add_argument('--max-attachment-bytes', type=int, default=None,
                       help='按这个上限统计"会被跳过"的附件（默认取服务端配置）')
    args = parser.parse_args()

    if not os.path.exists(args.file):
        print('找不到文件：%s' % args.file)
        return 1

    global MAX_ATTACHMENT_BYTES
    if args.max_attachment_bytes:
        MAX_ATTACHMENT_BYTES = args.max_attachment_bytes

    stats, extra = profile(args.file, samples=args.samples, out_dir=args.out,
                           titles=args.titles)
    report(args.file, stats, extra, args.top_tags)
    return 0


if __name__ == '__main__':
    sys.exit(main())
