"""Creating a message from an inbound payload — one implementation, two front doors.

The REST API and the web "add" page both need to do exactly the same things:
validate attachment bytes, keep the "content may be empty only when something is
attached" invariant, hang attachments off `metadata`, and report contract drift.

Two copies of that logic would drift — the attachment limits and the content
invariant are precisely the rules that must not differ between the phone, the API
and the browser. So it lives here and both callers delegate.
"""

from __future__ import annotations

import os
from datetime import datetime, timezone

from marshmallow import ValidationError

from blob_store import BlobError
from models import db, Message
from schemas.message_schema import MessageCreateSchema

message_create_schema = MessageCreateSchema()

#: Files are written before the row is committed, so a crash in between leaves an
#: unreferenced file (swept by scripts/blob-gc.py) rather than a message whose
#: attachment is missing.
docs_note = 'see docs/attachments.md'


def store_attachments(files, max_bytes=None):
    """Validate + store uploaded files. Returns (attachments, rejected).

    Imported lazily: `api.v1.blobs` pulls in Flask request context helpers, and
    this module is also used by scripts.
    """
    if not files:
        return [], []
    from api.v1.blobs import store_uploads
    return store_uploads(files, max_bytes=max_bytes)


def create_message(json_data, files=None, *, source=None, max_attachment_bytes=None):
    """Build (but do not commit) a Message from a payload plus optional files.

    Raises `BlobError` for anything the client can fix (too large, wrong type,
    empty body with no attachment, malformed metadata) and `ValidationError` for
    schema violations, so callers can map them to the right status code.

    `source` is only used for the log line, so an operator reading the journal can
    tell whether the message came in over the API or from the web page.

    `max_attachment_bytes` raises the per-file ceiling for callers whose constraints
    differ from a phone upload (see scripts/import-evernote.py). It is threaded all
    the way down to the validation that enforces it.
    """
    json_data = dict(json_data or {})
    # marshmallow's DateTime field only accepts a string (it runs the value through
    # a date parser), so a caller that has a datetime object — the web form uses
    # `datetime.now(timezone.utc)` — must be normalised here rather than each
    # caller remembering to call .isoformat(). Getting this wrong fails with
    # "Not a valid datetime", which is a confusing error for a correct value.
    stamp = json_data.get('timestamp')
    if isinstance(stamp, datetime):
        if stamp.tzinfo is None:
            stamp = stamp.replace(tzinfo=timezone.utc)
        json_data['timestamp'] = stamp.astimezone(timezone.utc).isoformat()

    attachments, rejected = store_attachments(files, max_bytes=max_attachment_bytes)
    if files and not attachments:
        raise BlobError(rejected[0]['error'] if rejected else '附件均未通过校验',
                        status=415)

    has_text = bool((json_data.get('content') or '').strip())
    if not has_text and not attachments:
        # The one invariant the schema cannot express cleanly.
        raise BlobError('content 不能为空（除非同时上传了附件）')
    json_data.setdefault('content', '')

    data = message_create_schema.load(json_data)

    metadata = data.get('metadata', {}) or {}
    if attachments:
        metadata['attachments'] = attachments

    message = Message(
        source_device_id=data['source_device_id'],
        type=data['type'],
        sender=data['sender'],
        content=data['content'],
        timestamp=data['timestamp'],
        message_metadata=metadata,
        received_at=datetime.now(timezone.utc),
    )

    # metadata JSON is where channel-specific facts live, with one exception: a
    # fact that already has a column must not be copied in. Report (never reject)
    # so client drift shows up in logs instead of quietly creating a second source
    # of truth.
    import metadata_policy as policy
    from flask import current_app
    try:
        logger = current_app.logger
    except RuntimeError:      # outside an app context (scripts/tests)
        logger = None
    for issue in policy.lint_metadata(data['type'], message.message_metadata):
        if logger:
            logger.warning('metadata contract: %s (source=%s, device=%s, type=%s)',
                           issue, source or '?', data['source_device_id'], data['type'])

    db.session.add(message)
    return message, attachments, rejected


def source_device_for_web(default='web'):
    """Device id used for messages created in the browser.

    Mirrors the phone's convention (a *device* name, not a user name): a manual
    note has no counterparty, so the honest label is where it was written.
    """
    return os.environ.get('WEB_DEVICE_ID') or default
