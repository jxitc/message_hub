#!/usr/bin/env python3
"""Backfill recipient info onto email rows collected before we stored it.

Mail messages used to be saved with only Subject/From/Date, so the "Received
at (To)" filter could not see them. The headers were always there — they just
were not kept. This script re-reads each affected message's headers from IMAP
(by Message-ID) and fills in `metadata.recipients` plus the `To:` line of the
stored content.

Read-only against the mailbox: it fetches headers with BODY.PEEK, so no message
is marked as read. Idempotent — rows that already have recipients are skipped.

Usage (on the server, from /opt/message_hub):

    ./venv/bin/python scripts/backfill-mail-recipients.py            # show what would change
    ./venv/bin/python scripts/backfill-mail-recipients.py --apply    # write the changes
"""

import argparse
import email
import imaplib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import mail_collector as mc  # noqa: E402
from app import create_app  # noqa: E402
from models import db, Message  # noqa: E402


def fetch_header(conn, message_id):
    """Return the raw header bytes for a Message-ID, or None."""
    for query in ('(HEADER Message-ID "<%s>")' % message_id,
                  '(HEADER Message-ID "%s")' % message_id):
        try:
            status, data = conn.search(None, query)
        except imaplib.IMAP4.error:
            continue
        if status != 'OK' or not data or not data[0]:
            continue
        uid = data[0].split()[-1]
        status, fetched = conn.fetch(uid, '(BODY.PEEK[HEADER])')
        if status == 'OK' and fetched and isinstance(fetched[0], tuple):
            return fetched[0][1]
    return None


def add_to_header_line(content, to_value):
    """Insert or replace the `To:` line in the stored 'Subject/From/To/Date' head."""
    head, sep, body = (content or '').partition('\n\n')
    lines = head.split('\n')
    if any(line.startswith('To: ') for line in lines):
        lines = [('To: ' + to_value) if line.startswith('To: ') else line for line in lines]
    else:
        index = next((i for i, line in enumerate(lines) if line.startswith('From: ')), None)
        insert_at = index + 1 if index is not None else len(lines)
        lines.insert(insert_at, 'To: ' + to_value)
    return '\n'.join(lines) + sep + body


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--apply', action='store_true',
                        help='write changes (default is a dry run)')
    parser.add_argument('--limit', type=int, default=500,
                        help='max rows to process (default 500)')
    args = parser.parse_args()

    accounts = mc.load_accounts()
    if not accounts:
        print('No mail accounts configured — nothing to backfill from.')
        return 1

    app = create_app()
    with app.app_context():
        rows = (Message.query
                .filter(Message.type == 'EMAIL')
                .order_by(Message.timestamp.desc())
                .limit(args.limit)
                .all())
        pending = [r for r in rows if not (r.message_metadata or {}).get('recipients')]
        print('%d email rows, %d missing recipients' % (len(rows), len(pending)))
        if not pending:
            return 0

        updated = skipped = failed = 0
        for account in accounts:
            remaining = [r for r in pending if (r.message_metadata or {}).get('message_id')]
            if not remaining:
                break

            print('\nConnecting to %s as %s …' % (account['host'], account['user']))
            conn = imaplib.IMAP4_SSL(account['host'], account.get('port', 993))
            try:
                conn.login(account['user'], account['password'])
                conn.select(account.get('folder', 'INBOX'), readonly=True)

                for row in remaining:
                    meta = dict(row.message_metadata or {})
                    message_id = meta.get('message_id')
                    raw = fetch_header(conn, message_id)
                    if not raw:
                        print('  ? %s  not found in mailbox' % message_id[:48])
                        skipped += 1
                        continue

                    msg = email.message_from_bytes(raw)
                    fields = mc.recipient_fields(msg)
                    if not fields['recipients']:
                        print('  - %s  no recipient header' % message_id[:48])
                        skipped += 1
                        continue

                    print('  + %s  -> %s' % (message_id[:48], ', '.join(fields['recipients'])))
                    if args.apply:
                        # Only the normalised list is stored — see build_payload().
                        meta['recipients'] = fields['recipients']
                        row.message_metadata = meta
                        row.content = add_to_header_line(row.content, fields['to'])
                    updated += 1
            except Exception as exc:  # noqa: BLE001 - report and continue
                print('  ! %s failed: %s' % (account['user'], exc))
                failed += 1
            finally:
                try:
                    conn.logout()
                except Exception:
                    pass

        if args.apply:
            db.session.commit()
            print('\nWrote %d rows (%d skipped, %d account errors).' % (updated, skipped, failed))
        else:
            print('\nDry run: %d rows would be updated (%d skipped, %d account errors).'
                  % (updated, skipped, failed))
            print('Re-run with --apply to write.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
