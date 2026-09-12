#!/usr/bin/env python3
"""Audit how well the stored metadata matches the field contract.

Answers three questions from real data instead of opinion:

1. which metadata keys does each channel actually write (and how often),
2. which of them duplicate a column — i.e. are a second source of truth,
3. which channel-private keys look like filter dimensions worth an escape hatch
   (high coverage + high cardinality).

Read-only. Run on the server from /opt/message_hub:

    ./venv/bin/python scripts/audit-metadata.py
    ./venv/bin/python scripts/audit-metadata.py --contract   # print the contract too
"""

import argparse
import json
import os
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import message_contract as contract  # noqa: E402
from app import create_app  # noqa: E402
from models import db, Message  # noqa: E402


def collect(limit=None):
    """Walk every message once and bucket its metadata keys per channel."""
    query = db.session.query(Message.type, Message.message_metadata)
    if limit:
        query = query.limit(limit)

    per_channel = defaultdict(lambda: {'rows': 0, 'keys': Counter(),
                                       'values': defaultdict(set), 'bad_json': 0})
    for message_type, raw in query:
        bucket = per_channel[message_type]
        bucket['rows'] += 1
        if isinstance(raw, str):
            try:
                metadata = json.loads(raw)
            except ValueError:
                bucket['bad_json'] += 1
                continue
        else:
            metadata = raw or {}
        for key, value in metadata.items():
            bucket['keys'][key] += 1
            # Cardinality tells us whether a key could be a filter dimension.
            # Only hashable scalars are counted; lists get joined.
            if isinstance(value, list):
                value = ','.join(str(v) for v in value)
            if isinstance(value, (str, int, float, bool)) and len(bucket['values'][key]) < 5000:
                bucket['values'][key].add(str(value))
    return per_channel


def report(per_channel):
    print('=== 每个渠道写了哪些 metadata 键 ===\n')
    for message_type in sorted(per_channel):
        bucket = per_channel[message_type]
        rows = bucket['rows']
        print('[%s] %d 行%s' % (message_type, rows,
                                '  ⚠️ %d 行 metadata 不是合法 JSON' % bucket['bad_json']
                                if bucket['bad_json'] else ''))
        for key, count in bucket['keys'].most_common():
            distinct = len(bucket['values'].get(key, ()))
            flags = []
            if key in contract.REDUNDANT_METADATA_KEYS:
                flags.append('❌ 与列重复')
            elif key in contract.RESERVED_JSON_NAMES:
                flags.append('✅ 公共保留名')
            elif key in contract.KNOWN_CHANNEL_KEYS:
                flags.append('· 渠道私有')
            else:
                flags.append('? 未登记')
            # A key covering most rows with a modest number of distinct values
            # is exactly the shape of a useful filter dimension.
            if rows and count / rows > 0.5 and 1 < distinct <= 200:
                flags.append('→ 可作筛选维度')
            print('    %-18s %5d/%d 行  去重 %4d  %s'
                  % (key, count, rows, distinct, ' '.join(flags)))
        print()

    print('=== 契约问题汇总 ===\n')
    problems = 0
    for message_type in sorted(per_channel):
        bucket = per_channel[message_type]
        for key, count in sorted(bucket['keys'].items()):
            if key in contract.REDUNDANT_METADATA_KEYS:
                problems += 1
                print('  [%s] metadata.%s 出现 %d 次 —— %s'
                      % (message_type, key, count,
                         contract.REDUNDANT_METADATA_KEYS[key]))
    if not problems:
        print('  没有与列重复的 key 🎉')

    print('\n=== 埋在 JSON 里、最值得做"逃生舱"筛选的维度 ===\n')
    ranked = []
    for message_type in sorted(per_channel):
        bucket = per_channel[message_type]
        rows = bucket['rows'] or 1
        for key, count in bucket['keys'].items():
            distinct = len(bucket['values'].get(key, ()))
            # Skip what a column already covers (those are not filter gaps), the
            # redundant copies, and the dedup ids.
            if (key in contract.COMMON_FIELDS
                    or key in contract.REDUNDANT_METADATA_KEYS
                    or key in ('message_id', 'notification_id')):
                continue
            ranked.append((count / rows, distinct, message_type, key))
    for coverage, distinct, message_type, key in sorted(ranked, reverse=True)[:8]:
        print('  %-18s %-18s 覆盖 %5.1f%%  去重 %4d 值'
              % (message_type, key, coverage * 100, distinct))
    if not ranked:
        print('  （无）')


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--contract', action='store_true',
                        help='also print the field contract')
    parser.add_argument('--limit', type=int, default=None,
                        help='only inspect the first N messages')
    args = parser.parse_args()

    if args.contract:
        print(contract.describe_contract())
        print()

    app = create_app()
    with app.app_context():
        per_channel = collect(args.limit)
    report(per_channel)
    return 0


if __name__ == '__main__':
    sys.exit(main())
