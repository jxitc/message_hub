#!/usr/bin/env python3
"""migrate.py — small idempotent schema migrations for Message Hub.

Run after ``db.create_all()`` (deploy.sh does this). Kept deliberately tiny
because the project uses SQLite (with optional PostgreSQL via DATABASE_URL).

Current migration: drop the ``messages.is_read`` column (read/unread feature
removed 2026-09-04). Safe to run repeatedly — it checks the live schema first.
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


def run_all():
    engine = _engine()
    print(f"migrate: engine = {engine.url.render_as_string(hide_password=True)}")
    drop_column_if_exists(engine, 'messages', 'is_read')
    print("migrate: done")


if __name__ == '__main__':
    run_all()
