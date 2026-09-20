#!/usr/bin/env python3
"""Remove messages that are the same event stored many times, and label the rest.

The hub can now recognise a repeat upload (see message_identity.py), but that only
stops *new* copies. The rows that piled up before — 15,745 rows for ~991 real
events on 2026-09-20 — have to be reconciled once, and this is that pass. It is
also what backfills ``messages.natural_key``: the index is created on an all-NULL
column (see migrate.add_natural_key), and this script is what fills it in.

    ./venv/bin/python scripts/dedup-messages.py                    # dry run, all
    ./venv/bin/python scripts/dedup-messages.py --device OPPO-X    # one device
    ./venv/bin/python scripts/dedup-messages.py --apply            # do it
    ./venv/bin/python scripts/dedup-messages.py --apply --report   # + per-group log

Dry run is the default because this deletes rows and nothing here is recoverable.

Which copy survives
-------------------
The *richest* one, not the first: longest trimmed ``content``, then earliest
``received_at``, then smallest id. Earliest-first would be wrong for the case that
actually occurs — a notification whose first capture read "视频标题加载失败" and
whose retry read the real title — and keeping the degraded copy would throw away
the only good text. Ties (the normal case: identical copies) fall back to the
earliest, which is the one the phone really sent first.

"Longest" is a heuristic, not a law, so ``--report`` prints every group whose
copies disagreed, with each distinct variant and which one survives. Read that
before ``--apply``; it is the only place a wrong keep decision is visible.

Rows with no natural key (hand-written notes, and anything the channel left
unidentified) are never deleted and keep a NULL key.
"""

import argparse
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import message_identity  # noqa: E402
from app import create_app  # noqa: E402
from models import db, Message  # noqa: E402

#: How many doomed copies of one group the --report output names before it stops.
#: The largest real group held 281 copies; printing all of them buries the useful
#: part of the audit (which row survived, and why).
DROPS_SHOWN = 4


def key_for(message):
    """The natural key of an already-stored row.

    Computed from the row, not read from the column: an existing value could be
    stale (written by an older key version), and recomputing is what makes this
    script usable as the backfill.
    """
    return message_identity.natural_key(
        message_type=message.type,
        metadata=message.message_metadata or {},
        sender=message.sender,
        timestamp=message.timestamp,
        content=message.content,
    )


def _received_sort_key(message):
    """Earliest first, NULLs (impossible — column is NOT NULL) last."""
    return (message.received_at is None, message.received_at, message.id)


def pick_survivor(group):
    """The row to keep out of a group that all share one natural key."""
    def rank(message):
        return (-len((message.content or '').strip()),) + _received_sort_key(message)
    return sorted(group, key=rank)[0]


def collect_groups(device=None, message_type=None):
    """{natural_key: [Message, ...]} for the rows in scope, plus unkeyed rows."""
    query = db.session.query(Message)
    if device:
        query = query.filter(Message.source_device_id == device)
    if message_type:
        query = query.filter(Message.type == message_type)

    groups = defaultdict(list)
    unkeyed = []
    needs_key = 0
    for message in query:
        key = key_for(message)
        if key is None:
            unkeyed.append(message)
            continue
        if message.natural_key != key:
            needs_key += 1
        groups[key].append(message)
    return groups, unkeyed, needs_key


def plan(groups):
    """Split the groups into (survivors, doomed) and count what changes."""
    survivors, doomed = [], []
    for key, group in groups.items():
        keep = pick_survivor(group)
        survivors.append(keep)
        doomed.extend(m for m in group if m is not keep)
    return survivors, doomed


def apply_plan(groups, survivors, doomed):
    """Delete the copies, then label every survivor. Returns (deleted, keyed).

    The order inside the transaction matters, and the obvious version is wrong.
    A row that is about to be deleted may already hold exactly the key its
    survivor needs — that is the normal state right after a deploy, when new
    uploads are keyed but the historical flood rows are not, and a re-upload of a
    historical event has already claimed the key. Assigning the survivor's key
    while the doomed rows still hold theirs trips the unique index and the whole
    transaction rolls back. So: delete first, then clear every key in scope, then
    write the survivors' keys.
    """
    doomed_ids = {m.id for m in doomed}
    for message in doomed:
        db.session.delete(message)
    db.session.flush()

    for group in groups.values():
        for message in group:
            if message.id not in doomed_ids and message.natural_key is not None:
                message.natural_key = None
    db.session.flush()

    for message in survivors:
        message.natural_key = key_for(message)
    db.session.commit()
    return len(doomed), len(survivors)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--apply', action='store_true',
                        help='actually delete (default: count only)')
    parser.add_argument('--device', help='only this source_device_id')
    parser.add_argument('--type', help='only this message type')
    parser.add_argument('--report', action='store_true',
                        help='print one line per duplicate group')
    parser.add_argument('--report-limit', type=int, default=40,
                        help='how many groups --report prints (default 40)')
    args = parser.parse_args()

    app = create_app()
    with app.app_context():
        total = db.session.query(Message).count()
        groups, unkeyed, needs_key = collect_groups(args.device, args.type)
        dup_groups = {k: v for k, v in groups.items() if len(v) > 1}
        survivors, doomed = plan(groups)
        keyed = sum(len(v) for v in groups.values())

        print('== scope ==')
        print('  rows in database            : %d' % total)
        print('  rows examined               : %d' % (keyed + len(unkeyed)))
        print('  rows with a natural key     : %d (in %d groups)' % (keyed, len(groups)))
        print('  rows with no key (kept)     : %d' % len(unkeyed))
        print('  groups with >1 row          : %d' % len(dup_groups))
        print('  rows in those groups        : %d'
              % sum(len(v) for v in dup_groups.values()))
        print('  -> duplicates to delete     : %d' % len(doomed))
        print('  rows whose key is missing or stale : %d' % needs_key)

        if args.report:
            print('\n== biggest duplicate groups (showing %d of %d) =='
                  % (min(args.report_limit, len(dup_groups)), len(dup_groups)))
            biggest = sorted(dup_groups.items(), key=lambda kv: -len(kv[1]))
            for key, group in biggest[:args.report_limit]:
                keep = pick_survivor(group)
                print('  x%-4d %s' % (len(group), message_identity.describe(key)))
                print('        keep %s  (%s, %d chars)'
                      % (keep.id, keep.received_at, len((keep.content or '').strip())))
                others = [m for m in group if m is not keep]
                for m in others[:DROPS_SHOWN]:
                    print('        drop %s  (%s, %d chars)'
                          % (m.id, m.received_at, len((m.content or '').strip())))
                if len(others) > DROPS_SHOWN:
                    print('        ... and %d more copy/copies of the same event'
                          % (len(others) - DROPS_SHOWN))
            if len(dup_groups) > args.report_limit:
                print('  ... %d more group(s)' % (len(dup_groups) - args.report_limit))

            # The only groups where deleting copies could lose information are the
            # ones whose copies did not agree. "Longest wins" is a heuristic, not a
            # law — a real title can be shorter than a placeholder like
            # "视频标题加载失败" — so this lists every disagreement in full and says
            # which variant survives. Rare in practice (5 groups out of 624 on the
            # 2026-09-20 flood) and small enough to actually read.
            disagreements = {k: v for k, v in dup_groups.items()
                             if len({(m.content or '').strip() for m in v}) > 1}
            print('\n== groups whose copies disagreed (%d) ==' % len(disagreements))
            if not disagreements:
                print('  none — every group was byte-identical text')
            for key, group in sorted(disagreements.items(), key=lambda kv: -len(kv[1])):
                keep = pick_survivor(group)
                print('  %s' % message_identity.describe(key))
                variants = {}
                for m in group:
                    variants.setdefault((m.content or '').strip(), []).append(m)
                for text, members in variants.items():
                    mark = 'KEEP' if keep in members else 'drop'
                    print('    %s x%-3d %d chars  %r'
                          % (mark, len(members), len(text), text[:160]))

        if not args.apply:
            print('\nDry run: nothing was changed. Re-run with --apply to delete '
                  '%d row(s) and write %d key(s).' % (len(doomed), len(survivors)))
            return 0

        # One transaction — deleting without backfilling (or the reverse) would
        # leave the unique index inconsistent with the data it protects.
        deleted, keyed = apply_plan(groups, survivors, doomed)

        remaining = db.session.query(Message).count()
        print('\n== applied ==')
        print('  deleted                     : %d' % deleted)
        print('  keys written                : %d' % keyed)
        print('  rows now in database        : %d (was %d)' % (remaining, total))

        # Re-derive from the database: an incomplete pass must not look clean.
        groups_after, unkeyed_after, _ = collect_groups(args.device, args.type)
        still_dup = {k: v for k, v in groups_after.items() if len(v) > 1}
        print('  duplicate groups left       : %d' % len(still_dup))
        print('  rows without a key          : %d' % len(unkeyed_after))
        return 1 if still_dup else 0


if __name__ == '__main__':
    sys.exit(main())
