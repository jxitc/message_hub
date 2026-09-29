#!/usr/bin/env python3
"""把外部重跑的 OCR 结果回填进消息的附件记录。

背景：有些附件服务器上的 tesseract 几乎读不出东西（81 个里 76 个是图片，且多是
证件——护照、BRP、驾照、身份证）。这些在 Mac 上用 macOS Vision 重跑后明显更好，
但**不换服务器上的 OCR 组件**：这里只把"算好的文本"写回去。

    ./venv/bin/python scripts/backfill-ocr.py results.json            # 演练
    ./venv/bin/python scripts/backfill-ocr.py results.json --apply    # 真写

输入 JSON 是 Mac 端产出的列表，每项至少要有：
    {"key": "<附件 key>", "new_text": "...", "better": true, "old_fields": 3, "new_fields": 30}

三条纪律：

1. **只在不更差时才替换。** `better` 由调用方算出，本脚本再独立复核一遍
   （比较"像字段的 token 数"），不一致就跳过并报出来——不靠调用方的判断。
2. **保留旧值痕迹。** 覆盖前把旧的 engine 和字数记进 `extraction.replaced`，
   否则"这条文本是哪来的、原来是什么样"以后没人说得清。
3. **必须 deepcopy 后整体赋值。** SQLAlchemy 判断 JSON 列有没有变是比较新旧值；
   浅拷贝里改内层 dict 会让两者比较相等，UPDATE 根本不会发出（这个坑踩过）。
"""

import argparse
import copy
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import create_app          # noqa: E402
from models import db, Message      # noqa: E402

#: 与选材时同一个口径，用来独立复核"新的有没有更好"
TOKEN = re.compile(r'[A-Za-z]{3,}|\d{4,}|[\u4e00-\u9fff]{2,}')


def field_tokens(text):
    return len(set(TOKEN.findall(text or '')))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('results', help='Mac 端产出的 JSON')
    parser.add_argument('--apply', action='store_true', help='真的写入（默认演练）')
    parser.add_argument('--engine', default='vision (macOS, 离线回填)',
                        help='写进 extraction.engine 的标记')
    args = parser.parse_args()

    rows = json.load(open(args.results, encoding='utf-8'))
    print('输入 %d 条' % len(rows))

    app = create_app()
    replaced = skipped = missing = 0
    chars_before = chars_after = 0
    details = []

    with app.app_context():
        # key -> (message, index)；一个 key 可能被多条消息引用，全部都要更新
        index = {}
        for message in Message.query:
            for position, attachment in enumerate(
                    (message.message_metadata or {}).get('attachments') or []):
                key = attachment.get('key')
                if key:
                    index.setdefault(key, []).append((message, position))

        for row in rows:
            key = row.get('key')
            new_text = (row.get('new_text') or '').strip()
            for message, position in index.get(key, []):
                metadata = copy.deepcopy(message.message_metadata or {})
                attachment = metadata['attachments'][position]
                extraction = dict(attachment.get('extraction') or {})
                old_text = extraction.get('text') or ''
                old_fields, new_fields = field_tokens(old_text), field_tokens(new_text)

                # 独立复核：调用方说 better 不算，这里自己比一遍
                if not new_text:
                    skipped += 1
                    details.append(('空结果，跳过', row.get('name'), key[:12]))
                    continue
                if new_fields <= old_fields:
                    skipped += 1
                    details.append(('不更好（%d→%d 字段），跳过' % (old_fields, new_fields),
                                    row.get('name'), key[:12]))
                    continue

                chars_before += len(old_text)
                chars_after += len(new_text)
                if args.apply:
                    extraction['text'] = new_text
                    extraction['engine'] = args.engine
                    extraction['chars'] = len(new_text)
                    # 旧值的痕迹：换过什么、原来多大
                    extraction['replaced'] = {
                        'engine': extraction.get('replaced', {}).get('engine')
                        or attachment.get('extraction', {}).get('engine'),
                        'chars': len(old_text),
                        'fields': old_fields,
                    }
                    attachment['extraction'] = extraction
                    message.message_metadata = metadata
                replaced += 1
                details.append(('替换（%d→%d 字段）' % (old_fields, new_fields),
                                row.get('name'), key[:12]))

        for row in rows:
            if row.get('key') not in index:
                missing += 1

        if args.apply:
            db.session.commit()

        print()
        print('== %s ==' % ('已写入' if args.apply else '演练（什么都没改）'))
        print('  将替换/已替换 : %d 处' % replaced)
        print('  跳过（不更好）: %d 处' % skipped)
        print('  找不到对应附件: %d 个 key' % missing)
        print('  文本总量      : %d 字 → %d 字' % (chars_before, chars_after))
        print()
        print('  前 12 条明细:')
        for status, name, key in details[:12]:
            print('    %-26s %-32s %s' % (status, (name or '')[:32], key))
        if not args.apply:
            print()
            print('  加 --apply 才会真的写。')

    return 0


if __name__ == '__main__':
    sys.exit(main())
