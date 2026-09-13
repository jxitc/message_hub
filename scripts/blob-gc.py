#!/usr/bin/env python3
"""Mark-and-sweep garbage collection for attachment blobs.

Deleting a message deliberately does not delete its bytes: the same content can be
referenced by several messages, and immediate reference counting is easy to get
wrong. Instead this sweeps on demand — collect every key the database still
references, then delete files nothing points at.

Always run without --apply first. The output tells you exactly what would go.

    ./venv/bin/python scripts/blob-gc.py            # 演练（默认）
    ./venv/bin/python scripts/blob-gc.py --apply    # 真删
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import create_app  # noqa: E402
from blob_store import BlobStore  # noqa: E402
from models import db, Message  # noqa: E402


def referenced_keys():
    """Every blob key mentioned by any message (plus attachment_skipped has none)."""
    keys = set()
    for (metadata,) in db.session.query(Message.message_metadata).all():
        for attachment in (metadata or {}).get('attachments') or []:
            key = attachment.get('key')
            if key:
                keys.add(key)
    return keys


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--apply', action='store_true',
                        help='actually delete (default is a dry run)')
    args = parser.parse_args()

    app = create_app()
    with app.app_context():
        store = BlobStore()
        keys = referenced_keys()
        total_files = len(list(store.iter_keys()))
        size_before, _files = store.usage()
        print('数据库引用的 key: %d' % len(keys))
        print('磁盘上的文件    : %d （%.2f MB）' % (total_files, size_before / 1048576))

        # List first, measure, then delete — measuring after the sweep would
        # report 0 bytes freed, which is both wrong and useless.
        orphans = store.collect_garbage(keys, dry_run=True)
        if not orphans:
            print('\n没有孤儿文件 ✅')
            return 0

        sizes = {key: store.size(key) for key in orphans}
        for key in orphans[:20]:
            print('  %s  %.1f KB' % (key, sizes[key] / 1024))
        if len(orphans) > 20:
            print('  … 还有 %d 个' % (len(orphans) - 20))
        freed = sum(sizes.values())

        if not args.apply:
            print('\n演练：%d 个孤儿文件，约 %.2f MB。加 --apply 删除。'
                  % (len(orphans), freed / 1048576))
            return 0

        for key in orphans:
            store.delete(key)
        size_after, files_after = store.usage()
        print('\n已删除 %d 个孤儿文件，释放 %.2f MB；现在 %d 个文件 %.2f MB'
              % (len(orphans), (size_before - size_after) / 1048576,
                 files_after, size_after / 1048576))
    return 0


if __name__ == '__main__':
    sys.exit(main())
