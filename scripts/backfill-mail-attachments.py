#!/usr/bin/env python3
"""Attach files from already-imported emails that were collected before the hub
stored attachments at all.

Those rows have only the body text; the PDFs they carried (a job description, an
invoice) were dropped. The mail is still in the mailbox, so this re-fetches each
message by Message-ID, stores its attachments as blobs, and links them to the
existing row — no duplicate messages, and extraction picks them up afterwards.

Read-only against the mailbox (BODY.PEEK). Idempotent: rows that already have
attachments are skipped.

    ./venv/bin/python scripts/backfill-mail-attachments.py            # dry run
    ./venv/bin/python scripts/backfill-mail-attachments.py --apply
"""

import argparse
import email
import imaplib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import mail_collector as mc  # noqa: E402
from app import create_app  # noqa: E402
from blob_store import BlobStore  # noqa: E402
from models import db, Message  # noqa: E402


def fetch_full(conn, message_id):
    for query in ('(HEADER Message-ID "<%s>")' % message_id,
                  '(HEADER Message-ID "%s>")' % message_id,
                  '(HEADER Message-ID "%s")' % message_id):
        try:
            status, data = conn.search(None, query)
        except imaplib.IMAP4.error:
            continue
        if status != 'OK' or not data or not data[0]:
            continue
        uid = data[0].split()[-1]
        status, fetched = conn.fetch(uid, '(BODY.PEEK[])')
        if status == 'OK' and fetched and isinstance(fetched[0], tuple):
            return fetched[0][1]
    return None


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--apply', action='store_true', help='write changes')
    parser.add_argument('--limit', type=int, default=200)
    args = parser.parse_args()

    accounts = mc.load_accounts()
    if not accounts:
        print('没有配置邮箱，无法回填。')
        return 1

    app = create_app()
    with app.app_context():
        rows = (db.session.query(Message)
                .filter(Message.type == 'EMAIL')
                .order_by(Message.timestamp.desc())
                .limit(args.limit).all())
        pending = [r for r in rows
                   if not (r.message_metadata or {}).get('attachments')
                   and (r.message_metadata or {}).get('message_id')]
        print('%d 封邮件，其中 %d 封还没记录附件' % (len(rows), len(pending)))
        if not pending:
            return 0

        store = BlobStore()
        limits = mc.fetch_limits('http://127.0.0.1:5001')
        stored = skipped_total = missing = 0

        for account in accounts:
            remaining = [r for r in pending]
            if not remaining:
                break
            print('\n连接 %s（%s）…' % (account['host'], account['user']))
            conn = imaplib.IMAP4_SSL(account['host'], account.get('port', 993))
            try:
                conn.login(account['user'], account['password'])
                conn.select(account.get('folder', 'INBOX'), readonly=True)
                for row in remaining:
                    message_id = row.message_metadata.get('message_id')
                    raw = fetch_full(conn, message_id)
                    if not raw:
                        print('  ? %s  邮箱里找不到' % message_id[:46])
                        missing += 1
                        continue
                    attachments, skipped = mc.split_attachments(
                        email.message_from_bytes(raw), *limits)
                    if not attachments and not skipped:
                        print('  - %s  没有附件' % message_id[:46])
                        continue
                    print('  + %s  %d 个附件, %d 个跳过'
                          % (message_id[:46], len(attachments), len(skipped)))
                    for name, mime, blob in attachments:
                        print('      %-42s %-24s %8.1f KB' % (name[:42], mime, len(blob)/1024))
                    if args.apply:
                        meta = dict(row.message_metadata)
                        records = []
                        for name, mime, blob in attachments:
                            from blob_store import kind_for
                            key, sha256, _created = store.put_bytes(blob, mime)
                            records.append({
                                'key': key, 'sha256': sha256, 'kind': kind_for(mime),
                                'mime': mime, 'size': len(blob),
                                'name': os.path.basename(name),
                                'source': 'email',
                                'extraction': {'status': 'pending'},
                            })
                        if records:
                            meta['attachments'] = records
                        if skipped:
                            meta['attachments_skipped'] = skipped
                        row.message_metadata = meta
                    stored += len(attachments)
                    skipped_total += len(skipped)
            except Exception as exc:  # noqa: BLE001
                print('  ! %s 失败: %s' % (account['user'], exc))
            finally:
                try:
                    conn.logout()
                except Exception:
                    pass

        if args.apply:
            db.session.commit()
            print('\n完成：关联 %d 个附件，记录 %d 个未存储，%d 封邮箱里找不到。'
                  % (stored, skipped_total, missing))
            print('接着跑一次提取：./venv/bin/python scripts/reextract.py --pending')
        else:
            print('\n演练：将关联 %d 个附件，记录 %d 个未存储，%d 封找不到。'
                  % (stored, skipped_total, missing))
            print('加 --apply 执行。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
