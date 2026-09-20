"""Creating a message from an inbound payload — one implementation, two front doors.

The REST API and the web "add" page both need to do exactly the same things:
validate attachment bytes, keep the "content may be empty only when something is
attached" invariant, hang attachments off `metadata`, and report contract drift.

Two copies of that logic would drift — the attachment limits and the content
invariant are precisely the rules that must not differ between the phone, the API
and the browser. So it lives here and both callers delegate.

This is also the one place where a duplicate upload is recognised and refused,
because it is the one place every front door passes through (see
message_identity.py for what "the same message" means).
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List

from marshmallow import ValidationError

import message_identity
from blob_store import BlobError
from models import db, Message
from schemas.message_schema import MessageCreateSchema

message_create_schema = MessageCreateSchema()

#: Files are written before the row is committed, so a crash in between leaves an
#: unreferenced file (swept by scripts/blob-gc.py) rather than a message whose
#: attachment is missing.
docs_note = 'see docs/attachments.md'


@dataclass
class IngestResult:
    """What `create_message` did.

    A dataclass rather than the 3-tuple it used to return: callers now have to
    deal with `duplicate`, and an unnamed third element that sometimes means "a
    new row" and sometimes "the row you already had" is exactly the kind of thing
    that gets unpacked and ignored. Unpacking a 3-tuple still *works* against this
    if it grows an `__iter__`, so it deliberately has none.
    """

    message: Message
    attachments: List[Dict[str, Any]] = field(default_factory=list)
    rejected: List[Dict[str, Any]] = field(default_factory=list)
    #: True when the payload matched a message already stored: `message` is that
    #: existing row, nothing was written, and no attachment bytes were stored.
    duplicate: bool = False
    #: Set when a duplicate arrived carrying more text than the stored row; the
    #: stored row's `content` was replaced (metadata is left alone).
    upgraded: bool = False


def store_attachments(files, max_bytes=None):
    """Validate + store uploaded files. Returns (attachments, rejected).

    Imported lazily: `api.v1.blobs` pulls in Flask request context helpers, and
    this module is also used by scripts.
    """
    if not files:
        return [], []
    from api.v1.blobs import store_uploads
    return store_uploads(files, max_bytes=max_bytes)


def _logger():
    """The app logger, or None outside an app context (scripts/tests)."""
    try:
        from flask import current_app
        return current_app.logger
    except RuntimeError:
        return None


def create_message(json_data, files=None, *, source=None, max_attachment_bytes=None,
                   dedupe=True):
    """Build (but do not commit) a Message from a payload plus optional files.

    Raises `BlobError` for anything the client can fix (too large, wrong type,
    empty body with no attachment, malformed metadata) and `ValidationError` for
    schema violations, so callers can map them to the right status code.

    `source` is only used for the log line, so an operator reading the journal can
    tell whether the message came in over the API or from the web page.

    `max_attachment_bytes` raises the per-file ceiling for callers whose constraints
    differ from a phone upload (see scripts/import-evernote.py). It is threaded all
    the way down to the validation that enforces it.

    `dedupe=False` disables duplicate detection for callers that do their own
    identity handling and must not silently lose a row — the Evernote importer
    keys notes on the export's own GUID/title hash and passes False.
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

    data = message_create_schema.load(json_data)
    metadata = data.get('metadata', {}) or {}

    # Duplicate check happens *before* any file is written, so a repeated upload
    # costs one SELECT rather than a megabyte of blob storage. The key is computed
    # from the parsed payload, not the raw one, so that it matches the key the
    # backfill computes from a stored row (see message_identity._stamp).
    key = None
    if dedupe:
        key = message_identity.natural_key(
            message_type=data['type'],
            metadata=metadata,
            sender=data['sender'],
            timestamp=data['timestamp'],
            content=data['content'],
        )
        if key:
            existing = Message.query.filter_by(natural_key=key).first()
            if existing is not None:
                return _duplicate_result(existing, data, key, source)

    attachments, rejected = store_attachments(files, max_bytes=max_attachment_bytes)
    if files and not attachments:
        raise BlobError(rejected[0]['error'] if rejected else '附件均未通过校验',
                        status=415)

    has_text = bool((data.get('content') or '').strip())
    if not has_text and not attachments:
        # The one invariant the schema cannot express cleanly.
        raise BlobError('content 不能为空（除非同时上传了附件）')

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
        natural_key=key,
    )

    # metadata JSON is where channel-specific facts live, with one exception: a
    # fact that already has a column must not be copied in. Report (never reject)
    # so client drift shows up in logs instead of quietly creating a second source
    # of truth.
    import metadata_policy as policy
    logger = _logger()
    for issue in policy.lint_metadata(data['type'], message.message_metadata):
        if logger:
            logger.warning('metadata contract: %s (source=%s, device=%s, type=%s)',
                           issue, source or '?', data['source_device_id'], data['type'])

    db.session.add(message)
    return IngestResult(message=message, attachments=attachments, rejected=rejected)


def _duplicate_result(existing, data, key, source):
    """Report a repeat upload without writing anything.

    The stored row wins, with one exception: if the incoming payload carries
    *more* text than the row we already have, the text is replaced. That is not
    hypothetical — a phone notification retried after the app failed to resolve
    its title comes back as "视频标题加载失败" first and the real title second, and
    keeping the first would throw away the only good copy. Nothing else on the
    row is touched, so server-added metadata (attachments, extraction results)
    can never be lost to a re-upload.
    """
    upgraded = False
    incoming = (data.get('content') or '').strip()
    stored = (existing.content or '').strip()
    if len(incoming) > len(stored):
        existing.content = data['content']
        upgraded = True

    logger = _logger()
    if logger:
        logger.info(
            'duplicate message refused: key=%s device=%s type=%s existing=%s '
            '(incoming text %d chars vs stored %d)%s',
            message_identity.describe(key), data['source_device_id'], data['type'],
            existing.id, len(incoming), len(stored),
            ' — stored text replaced with the longer copy' if upgraded else '',
        )
    return IngestResult(message=existing, duplicate=True, upgraded=upgraded)



def source_device_for_web(default='web'):
    """Device id used for messages created in the browser.

    Mirrors the phone's convention (a *device* name, not a user name): a manual
    note has no counterparty, so the honest label is where it was written.
    """
    return os.environ.get('WEB_DEVICE_ID') or default
