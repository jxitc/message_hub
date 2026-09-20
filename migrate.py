#!/usr/bin/env python3
"""migrate.py — small idempotent schema/data migrations for Message Hub.

Run after ``db.create_all()`` (deploy.sh does this). Kept deliberately tiny
because the project uses SQLite (with optional PostgreSQL via DATABASE_URL).

Migrations:
1. drop the ``messages.is_read`` column (read/unread feature removed 2026-09-04).
2. strip the duplicated recipient keys (``to``/``cc``/``delivered_to``/
   ``original_to``) out of email metadata, leaving ``recipients`` as the single
   source of truth. See build_payload() in mail_collector.py.
3. add ``messages.natural_key`` + its unique index (idempotent ingest,
   2026-09-20). The index is created on an all-NULL column, so it can be added
   before the historical duplicates are cleaned: SQL treats NULLs as distinct,
   and ``scripts/dedup-messages.py`` fills the column in afterwards.

Safe to run repeatedly — each step checks the current shape first.
"""

import os
import sys

from sqlalchemy import create_engine, inspect, text

from app import create_app
from models import db


def _engine():
    app = create_app()
    with app.app_context():
        return db.engine


def drop_column_if_exists(engine, table, column):
    """Drop ``table.column`` only if it currently exists (idempotent).

    Works on both SQLite (>= 3.35) and PostgreSQL.
    """
    inspector = inspect(engine)
    if table not in inspector.get_table_names():
        print(f"migrate: table '{table}' does not exist yet — nothing to do")
        return False
    cols = {c['name'] for c in inspector.get_columns(table)}
    if column not in cols:
        print(f"migrate: column '{table}.{column}' already gone — nothing to do")
        return False

    engine.connect().execute(text(f'ALTER TABLE "{table}" DROP COLUMN "{column}"'))
    print(f"migrate: dropped column '{table}.{column}'")
    return True


#: Recipient keys that used to be copied into metadata next to `recipients`.
REDUNDANT_RECIPIENT_KEYS = ('to', 'cc', 'delivered_to', 'original_to')


def strip_redundant_recipient_keys(engine):
    """Remove duplicated recipient fields from email metadata (idempotent).

    `recipients` (the normalised list) is what filtering and display read; the
    raw headers were a second copy of the same fact. The readable To line stays
    in `content`, so nothing user-visible is lost.
    """
    with engine.connect() as conn:
        # json_remove() is a no-op for keys that are absent, so the WHERE clause
        # is only there to skip rows that would not change — it keeps this from
        # rewriting every email on every deploy.
        predicates = ' OR '.join(
            "json_extract(message_metadata, '$.%s') IS NOT NULL" % key
            for key in REDUNDANT_RECIPIENT_KEYS)
        args = ', '.join("'$.%s'" % key for key in REDUNDANT_RECIPIENT_KEYS)
        result = conn.execute(text(
            "UPDATE messages "
            "SET message_metadata = json_remove(message_metadata, %s) "
            "WHERE type = 'EMAIL' AND message_metadata IS NOT NULL AND (%s)"
            % (args, predicates)))
        conn.commit()
        if result.rowcount:
            print('migrate: stripped redundant recipient keys from %d email row(s)'
                  % result.rowcount)
        else:
            print('migrate: email metadata already has no redundant recipient keys')
        return result.rowcount


def add_natural_key(engine):
    """Add ``messages.natural_key`` and its unique index (idempotent).

    Two statements rather than one: ``db.create_all()`` does not add columns to a
    table that already exists, so the column has to be ALTERed in. The index is
    ``CREATE UNIQUE INDEX IF NOT EXISTS`` so it can be re-run — and it is safe to
    create while the historical duplicate flood is still in the table, because
    every existing row has a NULL key and NULLs are distinct in a unique index.
    ``scripts/dedup-messages.py`` fills the keys in *after* removing duplicates.
    """
    inspector = inspect(engine)
    if 'messages' not in inspector.get_table_names():
        print("migrate: table 'messages' does not exist yet — nothing to do")
        return False
    changed = False
    cols = {c['name'] for c in inspector.get_columns('messages')}
    if 'natural_key' not in cols:
        with engine.connect() as conn:
            conn.execute(text('ALTER TABLE messages ADD COLUMN natural_key VARCHAR(255)'))
            conn.commit()
        print('migrate: added column messages.natural_key')
        changed = True
    with engine.connect() as conn:
        conn.execute(text(
            'CREATE UNIQUE INDEX IF NOT EXISTS ix_messages_natural_key '
            'ON messages (natural_key)'))
        conn.commit()
    print('migrate: messages.natural_key + unique index present')
    return changed


def run_all():
    engine = _engine()
    print(f"migrate: engine = {engine.url.render_as_string(hide_password=True)}")
    drop_column_if_exists(engine, 'messages', 'is_read')
    strip_redundant_recipient_keys(engine)
    add_natural_key(engine)
    print("migrate: done")


if __name__ == '__main__':
    run_all()
