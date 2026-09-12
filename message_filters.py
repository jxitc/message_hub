"""One place that decides which messages a set of filters selects.

The web UI, the REST API and the CLI all funnel their filter arguments through
:func:`apply_filters` — and so do the *delete* paths. Deleting by filter is
destructive, so the preview a user sees and the rows a delete removes must be
selected by the exact same code. Two implementations would eventually disagree
by a row or two, and the user would silently lose data they never saw.

Timestamps deserve a note: ``Message.timestamp`` is stored as *naive UTC*
(SQLAlchemy's SQLite DATETIME drops tzinfo on write, and every producer already
converts to UTC). Comparisons here therefore bind naive UTC datetimes, which
renders as ``YYYY-MM-DD HH:MM:SS.ffffff`` and orders correctly in SQLite's
string comparison. Binding an aware datetime would append ``+00:00`` and only
happen to work.
"""

from __future__ import annotations

from datetime import datetime, time, timezone

from sqlalchemy import text

from models import db, Message


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------

def parse_instant(value, *, end_of_day=False):
    """Parse a date or timestamp into a naive-UTC datetime, or None.

    Accepts ``YYYY-MM-DD`` and ISO 8601 timestamps (with or without offset).
    A bare date means "that whole day" — midnight for the lower bound, the last
    microsecond of the day for the upper bound (``end_of_day=True``).

    The web UI converts the user's *local* day boundaries in the browser and
    sends full ISO instants, so bare dates only reach here from API/CLI callers
    and are then read as UTC days.
    """
    if not value:
        return None
    raw = str(value).strip()
    if not raw:
        return None

    text_value = raw.replace('Z', '+00:00').replace('z', '+00:00')

    # Bare date -> whole UTC day.
    if len(text_value) == 10:
        try:
            day = datetime.strptime(text_value, '%Y-%m-%d')
        except ValueError:
            return None
        if end_of_day:
            return datetime.combine(day.date(), time(23, 59, 59, 999999))
        return day

    try:
        parsed = datetime.fromisoformat(text_value)
    except ValueError:
        return None

    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(timezone.utc).replace(tzinfo=None)
    return parsed


def _recipient_clause(recipient):
    """SQL clause matching metadata.recipients (a JSON array of addresses).

    Uses SQLite's JSON1 table-valued function so the match happens in SQL —
    filtering in Python would break pagination totals. Addresses are compared
    case-insensitively because mail domains and many senders vary the case.
    """
    return text(
        "EXISTS (SELECT 1 FROM json_each(messages.message_metadata, '$.recipients') "
        "WHERE lower(json_each.value) = :mh_recipient)"
    ).bindparams(mh_recipient=recipient.strip().lower())


# ---------------------------------------------------------------------------
# Filtering
# ---------------------------------------------------------------------------

def normalize_filters(message_type=None, device=None, recipient=None,
                      since=None, until=None):
    """Strip blanks so empty form fields never turn into filters."""
    def clean(value):
        value = (value or '').strip() if isinstance(value, str) else value
        return value or None

    return {
        'type': clean(message_type),
        'device': clean(device),
        'recipient': clean(recipient),
        'since': clean(since),
        'until': clean(until),
    }


def apply_filters(query, message_type=None, device=None, recipient=None,
                  since=None, until=None):
    """Apply message filters to a SQLAlchemy query and return it.

    ``since``/``until`` filter on the message's own timestamp (when the event
    happened), not on ``received_at`` (when the hub stored it) — the two differ
    for backfilled mail imports, and users think in event time.
    """
    filters = normalize_filters(message_type, device, recipient, since, until)

    if filters['type']:
        query = query.filter(Message.type == filters['type'])
    if filters['device']:
        query = query.filter(Message.source_device_id == filters['device'])
    if filters['recipient']:
        query = query.filter(_recipient_clause(filters['recipient']))

    since_dt = parse_instant(filters['since'])
    until_dt = parse_instant(filters['until'], end_of_day=True)
    if since_dt:
        query = query.filter(Message.timestamp >= since_dt)
    if until_dt:
        query = query.filter(Message.timestamp <= until_dt)

    return query


def apply_filter_dict(query, filters):
    """Apply a dict produced by :func:`normalize_filters`.

    Exists because that dict keys the type filter as ``'type'`` (the name used
    in forms and JSON payloads), which is not a valid keyword argument — and
    ``apply_filters(**filters)`` would raise instead of filtering. Call sites
    that hold a normalised dict use this.
    """
    filters = filters or {}
    return apply_filters(query,
                         message_type=filters.get('type'),
                         device=filters.get('device'),
                         recipient=filters.get('recipient'),
                         since=filters.get('since'),
                         until=filters.get('until'))


def describe_filters(filters):
    """Human-readable summary of active filters, for confirm dialogs/logs."""
    labels = []
    if filters.get('type'):
        labels.append('type=%s' % filters['type'])
    if filters.get('device'):
        labels.append('device=%s' % filters['device'])
    if filters.get('recipient'):
        labels.append('to=%s' % filters['recipient'])
    if filters.get('since'):
        labels.append('from=%s' % str(filters['since'])[:10])
    if filters.get('until'):
        labels.append('to=%s' % str(filters['until'])[:10])
    return ', '.join(labels)


# ---------------------------------------------------------------------------
# Options for the UI
# ---------------------------------------------------------------------------

def list_message_types():
    rows = db.session.query(Message.type).distinct().all()
    return sorted(t for (t,) in rows if t)


def list_devices():
    rows = db.session.query(Message.source_device_id).distinct().all()
    return sorted(d for (d,) in rows if d)


def list_recipients():
    """Every distinct recipient address seen in email metadata.

    Feeds the "To" dropdown. Falls back to an empty list if SQLite lacks JSON1
    rather than breaking the whole messages page.
    """
    try:
        rows = db.session.execute(text(
            "SELECT DISTINCT lower(json_each.value) AS addr "
            "FROM messages, json_each(messages.message_metadata, '$.recipients') "
            "ORDER BY addr"
        )).fetchall()
    except Exception:
        db.session.rollback()
        return []
    return [row[0] for row in rows if row[0]]
