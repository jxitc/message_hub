#!/usr/bin/env python3
"""Re-run attachment text extraction from the stored originals.

The originals are immutable and extraction is deterministic, so whenever a better
engine (or a new language pack) arrives, every derived text can be rebuilt. That
is the whole reason bytes are kept alongside the text.

    ./venv/bin/python scripts/reextract.py --status          # what is stored
    ./venv/bin/python scripts/reextract.py --pending         # finish the queue now
    ./venv/bin/python scripts/reextract.py --all             # rebuild everything
    ./venv/bin/python scripts/reextract.py --message <uuid>  # one message
    ./venv/bin/python scripts/reextract.py --engines         # is the toolchain there?
"""

import argparse
import copy
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import extraction  # noqa: E402
from app import create_app  # noqa: E402
from blob_store import BlobStore  # noqa: E402
from models import db, Message  # noqa: E402


def iter_messages(with_attachments_only=True):
    query = db.session.query(Message)
    for message in query.order_by(Message.received_at.desc()):
        attachments = (message.message_metadata or {}).get('attachments') or []
        if attachments or not with_attachments_only:
            yield message


def reset_status(message, status='pending'):
    """Mark every attachment of a message for (re-)extraction.

    The copy must be a **deep** one. SQLAlchemy decides whether a JSON column
    changed by comparing the new value with the previously loaded one; if the two
    share inner dicts (as a shallow copy does), an in-place edit makes them compare
    equal and the UPDATE is never emitted — the reset silently does nothing.
    """
    metadata = copy.deepcopy(message.message_metadata or {})
    attachments = list(metadata.get('attachments') or [])
    if not attachments:
        return 0
    for attachment in attachments:
        state = dict(attachment.get('extraction') or {})
        state['status'] = status
        attachment['extraction'] = state
    metadata['attachments'] = attachments
    message.message_metadata = metadata
    return len(attachments)


def report_status():
    counts = {}
    total = 0
    for message in iter_messages():
        for attachment in (message.message_metadata or {}).get('attachments') or []:
            total += 1
            state = (attachment.get('extraction') or {}).get('status') or '(none)'
            counts[state] = counts.get(state, 0) + 1
    store = BlobStore()
    size, files = store.usage()
    print('附件总数: %d' % total)
    for state, count in sorted(counts.items()):
        print('  %-12s %d' % (state, count))
    print('blob 存储: %d 个文件, %.2f MB' % (files, size / 1048576))
    print('引擎: %s' % extraction.available_engines())
    return 0


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--status', action='store_true', help='只报告状态')
    group.add_argument('--engines', action='store_true', help='只检查提取工具链')
    group.add_argument('--pending', action='store_true', help='立刻处理队列里等待的')
    group.add_argument('--all', action='store_true', help='重跑所有附件')
    group.add_argument('--ocr', action='store_true',
                       help='对所有 PDF 强制走 OCR（文本层太薄、疑似扫描件时用）')
    group.add_argument('--message', help='只重跑这一条消息')
    args = parser.parse_args()

    if args.engines:
        print(extraction.available_engines())
        return 0

    app = create_app()
    with app.app_context():
        if args.status or not (args.pending or args.all or args.message or args.ocr):
            return report_status()

        store = BlobStore()
        if args.pending:
            messages, attachments = extraction.run_once(app, store, limit=1000)
            print('处理 %d 条消息 / %d 个附件' % (messages, attachments))
            return 0

        if args.message:
            targets = [db.session.get(Message, args.message)]
            if targets[0] is None:
                print('找不到消息 %s' % args.message)
                return 1
        else:
            targets = list(iter_messages())

        total = 0
        for message in targets:
            if reset_status(message) == 0:
                continue
            db.session.commit()
            total += extraction.process_message(db.session, store, message,
                                                force_ocr=args.ocr)
        db.session.commit()
        print('重跑完成，处理 %d 个附件' % total)
    return 0


if __name__ == '__main__':
    sys.exit(main())
