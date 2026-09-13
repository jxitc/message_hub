"""Attachment storage API: upload helpers, limits, and the download endpoint.

Split of responsibility:

* `store_uploads()` turns Flask's uploaded files into stored blobs plus the
  metadata that goes into `messages.message_metadata.attachments`. It writes
  bytes **before** the caller commits the message row, so a crash can only leave
  an unreferenced file (swept by `BlobStore.collect_garbage`), never a message
  pointing at bytes that are not there.
* `GET /api/v1/blobs/<sha256>` is the **stable client-facing URL**. Phone apps and
  browsers only ever use this path; where the bytes physically live (this disk
  today, R2 tomorrow) stays a server-side detail. That is what keeps an installed
  APK working across a storage migration.
* `GET /api/v1/attachments/limits` publishes the size cap and allowed types so the
  client compresses *before* uploading instead of discovering the limit from a
  413.

Auth for downloads: the API key header, or a signed URL. A browser `<img>` tag
cannot send headers, and we already hit that problem with APK downloads — but
unlike the APK we do not want these files public, so links carry an HMAC token
bound to the blob key and an expiry.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import time

from flask import current_app, jsonify, request, send_file

from . import api_v1
from blob_store import (BlobError, BlobStore, MAX_ATTACHMENT_BYTES,
                        ext_to_mime, kind_for, validate_upload)

#: How long a signed download link stays valid.
SIGNED_URL_TTL = int(os.environ.get('BLOB_URL_TTL') or 24 * 3600)

ALLOWED_MIME = ('image/png', 'image/jpeg', 'image/gif', 'image/webp',
                'application/pdf', 'text/plain')

#: Images render inline (the app shows screenshots); everything else is forced to
#: download. Together with nosniff this is what stops uploaded content from ever
#: executing in a browser context.
_INLINE_MIME = ('image/png', 'image/jpeg', 'image/gif', 'image/webp')


def _secret():
    """Signing key, usable outside a request (scripts, CLI, tests)."""
    try:
        value = current_app.config.get('SECRET_KEY')
    except RuntimeError:
        value = os.environ.get('SECRET_KEY')
    return (value or 'mh-blob').encode('utf-8')


def sign(key, expires_at=None):
    expires_at = int(expires_at or (time.time() + SIGNED_URL_TTL))
    payload = '%s.%d' % (key, expires_at)
    mac = hmac.new(_secret(), payload.encode('utf-8'), hashlib.sha256).hexdigest()
    return '%d.%s' % (expires_at, mac)


def verify(key, token):
    try:
        expires_at, mac = (token or '').split('.', 1)
        expires_at = int(expires_at)
    except (ValueError, AttributeError):
        return False
    if expires_at < time.time():
        return False
    expected = hmac.new(_secret(), ('%s.%d' % (key, expires_at)).encode('utf-8'),
                        hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, mac)


def blob_url(key, signed=False):
    """Relative URL for a blob. `signed=True` adds a token for header-less clients."""
    url = '/api/v1/blobs/%s' % key
    if signed:
        return '%s?token=%s' % (url, sign(key))
    return url


def store_uploads(files, store=None):
    """Validate and store uploaded files. Returns (attachments, errors).

    Attachments are ordered and carry everything the UI and the extractor need:
    content hash, human-readable name, sniffed mime, size, and an extraction slot
    set to `pending` so the background worker will pick it up.
    """
    store = store or BlobStore()
    attachments, errors = [], []
    store.check_quota()
    for uploaded in files:
        name = (uploaded.filename or '').strip()
        data = uploaded.read()
        try:
            mime, kind = validate_upload(name, data)
        except BlobError as exc:
            # Size is a hard constraint the client must satisfy before retrying
            # (compress, or pick another file), so it fails the whole request.
            # A wrong *type* is per-file: the rest of a multi-file upload can
            # still succeed, so it is reported as a rejection instead.
            if exc.status == 413 or getattr(exc, 'fatal', False):
                raise
            errors.append({'name': name, 'error': exc.message})
            continue
        key, sha256, created = store.put_bytes(data, mime)
        attachments.append({
            'key': key,
            'sha256': sha256,
            'kind': kind,
            'mime': mime,
            'size': len(data),
            'name': os.path.basename(name) or ('attachment%s' % os.path.splitext(key)[1]),
            'deduplicated': not created,
            'extraction': {'status': 'pending'},
        })
    return attachments, errors


@api_v1.route('/attachments/limits')
def attachment_limits():
    """Publish the limits so clients can compress before uploading."""
    return jsonify({
        'max_bytes': MAX_ATTACHMENT_BYTES,
        'allowed': list(ALLOWED_MIME),
        'note': '图片超过 max_bytes 请在客户端压缩；其他类型无法压缩，超限会被拒绝。',
    })


def _api_key_ok():
    """True when the request carries a usable key (mirrors the global guard)."""
    provided = (request.headers.get('X-API-Key') or '').strip()
    if not provided:
        return False
    from models import ApiKey
    try:
        if ApiKey.query.filter_by(
                key_hash=hashlib.sha256(provided.encode()).hexdigest(),
                is_active=True).first() is not None:
            return True
    except Exception:
        pass
    expected = current_app.config.get('API_KEY')
    return bool(expected and provided == expected)


@api_v1.route('/blobs/<path:key>')
def download_blob(key):
    """Serve one stored blob. Key is `<aa>/<bb>/<sha256><ext>`."""
    # Reject anything that is not exactly the key shape we generate. The key is
    # derived from a content hash, so this is a second line of defence behind the
    # fact that no user input ever reaches the path.
    parts = key.split('/')
    if (len(parts) != 3 or len(parts[0]) != 2 or len(parts[1]) != 2
            or not parts[2][:64].isalnum() or len(parts[2]) < 64):
        return jsonify({'error': 'Invalid blob key'}), 400

    if not _api_key_ok():
        token = request.args.get('token', '')
        if not verify(key, token):
            return jsonify({'error': 'Unauthorized',
                            'message': 'Send X-API-Key or a valid ?token='}), 401

    store = BlobStore()
    if not store.exists(key):
        return jsonify({'error': 'Blob not found'}), 404

    mime = ext_to_mime(key)
    response = send_file(store.path(key), mimetype=mime, conditional=True)
    response.headers['X-Content-Type-Options'] = 'nosniff'
    if mime in _INLINE_MIME:
        response.headers['Content-Disposition'] = 'inline'
    else:
        response.headers['Content-Disposition'] = 'attachment'
    # Content-addressed keys never change, so caching is always safe — this is the
    # property that makes a separate blob origin (and Cloudflare) worth having.
    response.headers['Cache-Control'] = 'private, max-age=31536000, immutable'
    return response


@api_v1.route('/blobs')
def blob_index():
    """Storage accounting: how much is stored and what the quota is."""
    from blob_store import BLOB_SOFT_QUOTA_BYTES
    total, files = BlobStore().usage()
    return jsonify({
        'files': files,
        'bytes': total,
        'quota_bytes': BLOB_SOFT_QUOTA_BYTES,
        'max_attachment_bytes': MAX_ATTACHMENT_BYTES,
        'engines': _engines(),
    })


def _engines():
    try:
        import extraction
        return extraction.available_engines()
    except Exception:
        return {}


def attachment_public(attachment, signed=False):
    """Attachment as exposed by the API (adds a usable URL, hides internals)."""
    out = {
        'sha256': attachment.get('sha256'),
        'key': attachment.get('key'),
        'kind': attachment.get('kind'),
        'mime': attachment.get('mime'),
        'size': attachment.get('size'),
        'name': attachment.get('name'),
        'url': blob_url(attachment.get('key'), signed=signed)
        if attachment.get('key') else None,
    }
    if attachment.get('extraction'):
        out['extraction'] = attachment['extraction']
    return out
