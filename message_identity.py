"""A message's natural key — the identity its channel already gave it.

Every channel that feeds the hub already assigns each event an identity: an SMS
carries the phone's `message_id`, a mail carries the RFC `Message-ID`, an Android
notification carries `(package, notification_id, timestamp)`. The hub used to
throw that away and invent a fresh uuid4 for every POST, which made "the same
event delivered twice" indistinguishable from "two events".

That is exactly what happened on 2026-09-20: a phone whose client retried its
whole backlog produced 15,745 rows for ~991 real events in a single hub. Nothing
on the server could tell the difference, so nothing could refuse the copies.

This module is the one place that decides what "the same message" means. Both
the ingest path (message_ingest.create_message) and the cleanup script
(scripts/dedup-messages.py) ask *it*, so a row the cleanup would merge is
precisely a row a re-upload will be refused for. Two implementations would drift
and the drift would be invisible until the next flood.

Deliberately NOT keyed
----------------------
`NOTE` and `DOCUMENT` are written by hand. Two notes with identical text are two
things the user meant to keep, so they have no natural key and are never
deduplicated — a big hammer here would silently eat real data.
"""

from __future__ import annotations

import hashlib
from datetime import datetime, timezone

#: Bumped if the shape of a key ever changes. Keys written by an older version
#: keep working for comparison among themselves but must not be mixed with new
#: ones, so the version is part of the stored string.
KEY_VERSION = 'v1'

#: `messages.natural_key` is VARCHAR(255). Anything longer is hashed instead of
#: truncated: two distinct keys sharing a 255-char prefix must not collide.
MAX_KEY_CHARS = 250

#: Types whose fallback identity is (sender, timestamp, content). Only reached
#: for rows that carry no channel id at all — see `natural_key`.
_TEXT_FALLBACK_TYPES = ('SMS', 'CALL_LOG', 'EMAIL')


def _escape(value):
    """Make a component safe to concatenate with `|` as the separator.

    `%` is escaped first, otherwise a literal `%7C` in the input would decode
    into a separator and two different keys could render identically.
    """
    return str(value).replace('%', '%25').replace('|', '%7C')


def _text(value):
    """Non-empty stripped string, or None. Whitespace-only counts as absent."""
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _stamp(value):
    """Canonical text for a message timestamp.

    The ingest path and the backfill path must land on the *same* string, or a
    re-upload would not match the row it duplicates: ingest sees the parsed
    datetime marshmallow produced, the backfill sees what SQLite handed back. So
    every representation goes through here, including strings — an ISO string with
    `Z` and the same instant as a datetime must not produce two keys.

    SQLite does not persist an offset, so a naive value read back from the
    database is UTC by construction (everything the hub stores is UTC), and a
    naive value arriving from a client is read the same way.
    """
    if value is None:
        return None
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        try:
            value = datetime.fromisoformat(text.replace('Z', '+00:00'))
        except ValueError:
            # Not a form we understand (some client's own format). Use it as-is:
            # the same client sends the same text every time, so it still
            # compares consistently against itself.
            return text
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc).isoformat(timespec='microseconds')
    return str(value)


def _finish(*parts):
    spec = KEY_VERSION + '|' + '|'.join(_escape(p) for p in parts)
    if len(spec) <= MAX_KEY_CHARS:
        return spec
    digest = hashlib.sha256(spec.encode('utf-8')).hexdigest()
    return '%s|h|%s' % (KEY_VERSION, digest)


def content_fingerprint(content):
    """Short digest of the text, for the fallback identity."""
    return hashlib.sha1((content or '').strip().encode('utf-8')).hexdigest()[:16]


def natural_key(*, message_type, metadata=None, sender=None, timestamp=None,
                content=None):
    """The channel-given identity of a message, or None if it has none.

    Returns a stable, comparison-safe string. Contract:

    * Notifications  -> `v1|ntf|<package>|<notification_id>|<timestamp>`
    * Anything with a channel id (SMS / mail / call log) -> `v1|id|<type>|<id>`
    * SMS-like with no channel id -> `v1|txt|<type>|<sender>|<ts>|<sha1(text)>`
    * Manual notes, documents, and payloads missing the needed fields -> None

    Note on `notification_id`: some apps (抖音) put a *constant* there, so the
    timestamp is what actually separates two notifications. That is fine — the
    pair is still the notification's identity — but it means neither field alone
    is sufficient, and a key built from only one of them would over-merge.
    """
    meta = metadata or {}
    message_type = _text(message_type)

    if message_type == 'PUSH_NOTIFICATION':
        package = _text(meta.get('package_name'))
        notification_id = _text(meta.get('notification_id'))
        when = _stamp(timestamp) or _text(meta.get('timestamp'))
        if package and notification_id and when:
            return _finish('ntf', package, notification_id, when)

    channel_id = _text(meta.get('message_id'))
    if channel_id:
        return _finish('id', message_type or '?', channel_id)

    if message_type in _TEXT_FALLBACK_TYPES:
        when = _stamp(timestamp) or _text(meta.get('timestamp'))
        who = _text(sender)
        if when and who:
            return _finish('txt', message_type, who, when,
                           content_fingerprint(content))

    return None


def describe(key):
    """Human-readable form of a key, for logs and audit output.

    Percent-escapes are undone; the structure is left visible so an operator can
    see *why* two rows were considered the same event.
    """
    if not key:
        return '(no natural key — never deduplicated)'
    return _unescape(key)


def _unescape(key):
    # Reverse of _escape, in the same order (the `%25` pass must run last).
    return key.replace('%7C', '|').replace('%25', '%')
