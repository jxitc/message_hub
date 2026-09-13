"""Content-addressed blob storage for message attachments.

Design notes (kept here because they are the reasons the code looks like this):

* **Bytes never go in the database.** Metadata lives in `messages.message_metadata`
  (JSON); the bytes live under a storage root. That keeps DB backups small and,
  more importantly, makes "switch to object storage later" a config change
  rather than a data migration. See docs/attachments.md.
* **Keys are derived from content, never from user input.** A blob's key is
  `sha256` plus an extension derived from *sniffed magic bytes* — not from the
  client's Content-Type and not from the uploaded filename. That kills path
  traversal by construction, makes re-uploads idempotent, and de-duplicates
  identical files for free.
* **Uploads are validated against the bytes, not the claim.** A file is accepted
  only if its magic bytes match the allow-list, so renaming a binary to `.png`
  does not get it stored.
* **Write bytes first, then the database row.** A crash between the two leaves an
  orphan file (harmless, collected by `collect_garbage`), never a row pointing at
  a file that is not there.
* **Blobs are immutable.** Replacing content produces a new key, so no locking is
  needed and HTTP caching is always safe (that is what makes a separate blob
  origin worth having).
"""

from __future__ import annotations

import hashlib
import os
import shutil
import tempfile
import uuid

#: Hard cap per file. The phone compresses images above this; anything else is
#: rejected with a clear reason rather than silently truncated.
MAX_ATTACHMENT_BYTES = int(os.environ.get('MAX_ATTACHMENT_BYTES') or 1024 * 1024)

#: Refuse new uploads once the blob directory passes this, instead of filling the
#: disk — a full disk breaks SQLite writes for the whole hub, not just uploads.
BLOB_SOFT_QUOTA_BYTES = int(os.environ.get('BLOB_SOFT_QUOTA_BYTES') or 8 * 1024 ** 3)

#: magic bytes -> (mime, extension). Order matters: longest prefixes first.
_SIGNATURES = (
    (b'\x89PNG\r\n\x1a\n', 'image/png', '.png'),
    (b'\xff\xd8\xff', 'image/jpeg', '.jpg'),
    (b'GIF87a', 'image/gif', '.gif'),
    (b'GIF89a', 'image/gif', '.gif'),
    (b'%PDF-', 'application/pdf', '.pdf'),
)

#: RIFF....WEBP needs an offset check, so it is handled separately.
_WEBP_MIME, _WEBP_EXT = 'image/webp', '.webp'

#: Extensions we refuse outright even when the bytes look like plain text.
#: SVG and HTML can execute script when rendered; JS is obvious. They are only
#: dangerous if served inline, and `blob_store` also forces attachment
#: disposition for non-images — this is the second layer of that defence.
_DANGEROUS_TEXT_EXT = {'.svg', '.html', '.htm', '.xhtml', '.js', '.mjs', '.xml'}


class BlobError(Exception):
    """Rejection with a message meant to be shown to the client."""

    def __init__(self, message, status=400):
        super().__init__(message)
        self.message = message
        self.status = status


def sniff(data):
    """Return (mime, extension) from the leading bytes, or None if not allowed.

    Only the allow-list is recognised; anything else is rejected. Plain text is
    accepted when the payload decodes as UTF-8 and contains no NUL bytes, which
    is what separates a text file from a binary we do not support.
    """
    for prefix, mime, ext in _SIGNATURES:
        if data.startswith(prefix):
            return mime, ext
    if len(data) >= 12 and data[:4] == b'RIFF' and data[8:12] == b'WEBP':
        return _WEBP_MIME, _WEBP_EXT
    if b'\x00' not in data:
        try:
            data.decode('utf-8')
        except UnicodeDecodeError:
            return None
        return 'text/plain', '.txt'
    return None


def describe_rejection(name):
    """Human-readable reason a file was refused, for the client to display."""
    return ('不支持的文件类型：%s。允许图片（PNG/JPEG/GIF/WebP）、PDF、纯文本；'
            '上限 %d KB' % (name or '(未知)', MAX_ATTACHMENT_BYTES // 1024))


class BlobStore:
    """Local-filesystem backend.

    The public surface is deliberately tiny and backend-neutral
    (`put`/`open`/`exists`/`delete`/`path`) so an R2/S3 backend can be dropped in
    behind it later: every method takes a *key*, never a full path, and no caller
    ever stores a path.
    """

    def __init__(self, root=None):
        self.root = root or default_root()

    # -- keys -----------------------------------------------------------------

    @staticmethod
    def key(sha256, mime):
        """Key = content hash + extension from the (sniffed) mime type.

        Deriving the extension here means the mime is recoverable from the key
        alone, so serving a blob needs no database lookup.
        """
        ext = mime_to_ext(mime)
        return '%s/%s/%s%s' % (sha256[:2], sha256[2:4], sha256, ext)

    def path(self, key):
        return os.path.join(self.root, key)

    # -- operations -----------------------------------------------------------

    def exists(self, key):
        return os.path.exists(self.path(key))

    def open(self, key):
        return open(self.path(key), 'rb')

    def size(self, key):
        try:
            return os.path.getsize(self.path(key))
        except OSError:
            return 0

    def put_bytes(self, data, mime):
        """Store bytes, returning (key, sha256, created).

        `created` is False when identical content was already stored — the caller
        can log that as a de-duplication rather than a fresh write.
        """
        sha256 = hashlib.sha256(data).hexdigest()
        key = self.key(sha256, mime)
        target = self.path(key)
        if os.path.exists(target):
            return key, sha256, False

        os.makedirs(os.path.dirname(target), exist_ok=True)
        # Temp file in the same directory tree, then an atomic rename: a reader
        # never sees a half-written blob, and two concurrent uploads of the same
        # content cannot corrupt each other (last rename wins, same bytes).
        tmp_dir = os.path.join(self.root, '.tmp')
        os.makedirs(tmp_dir, exist_ok=True)
        tmp_path = os.path.join(tmp_dir, uuid.uuid4().hex)
        try:
            with open(tmp_path, 'wb') as fh:
                fh.write(data)
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(tmp_path, target)
        finally:
            if os.path.exists(tmp_path):
                try:
                    os.remove(tmp_path)
                except OSError:
                    pass
        return key, sha256, True

    def delete(self, key):
        try:
            os.remove(self.path(key))
            return True
        except OSError:
            return False

    # -- housekeeping ---------------------------------------------------------

    def usage(self):
        """Total bytes stored, plus the number of files."""
        total = files = 0
        for dirpath, _dirnames, filenames in os.walk(self.root):
            if os.path.basename(dirpath) == '.tmp':
                continue
            for name in filenames:
                try:
                    total += os.path.getsize(os.path.join(dirpath, name))
                    files += 1
                except OSError:
                    pass
        return total, files

    def check_quota(self):
        """Raise if the soft quota is reached, so uploads fail loudly and early."""
        total, _files = self.usage()
        if total >= BLOB_SOFT_QUOTA_BYTES:
            raise BlobError(
                '附件存储已达上限（%.1f GB / %.1f GB），先清理再上传'
                % (total / 1024 ** 3, BLOB_SOFT_QUOTA_BYTES / 1024 ** 3), status=507)

    def iter_keys(self):
        for dirpath, _dirnames, filenames in os.walk(self.root):
            if os.path.basename(dirpath) == '.tmp':
                continue
            for name in filenames:
                full = os.path.join(dirpath, name)
                yield os.path.relpath(full, self.root).replace(os.sep, '/')

    def collect_garbage(self, referenced, dry_run=True):
        """Delete blobs no message references (mark-and-sweep, run explicitly).

        Deletion of a message deliberately does **not** delete bytes immediately:
        the same content can be referenced by several messages, and an immediate
        delete would need reference counting, which is easy to get wrong. Sweeping
        on demand against the set of referenced keys is O(files) and trivially
        verifiable — and at this scale it is milliseconds.
        """
        referenced = set(referenced)
        removed = []
        for key in list(self.iter_keys()):
            if os.path.basename(key).startswith('.'):
                continue
            if key not in referenced:
                removed.append(key)
                if not dry_run:
                    self.delete(key)
        return removed


def mime_to_ext(mime):
    if mime == 'image/png':
        return '.png'
    if mime == 'image/jpeg':
        return '.jpg'
    if mime == 'image/gif':
        return '.gif'
    if mime == _WEBP_MIME:
        return _WEBP_EXT
    if mime == 'application/pdf':
        return '.pdf'
    return '.txt'


def ext_to_mime(path_or_key):
    ext = os.path.splitext(path_or_key)[1].lower()
    return {
        '.png': 'image/png', '.jpg': 'image/jpeg', '.gif': 'image/gif',
        '.webp': _WEBP_MIME, '.pdf': 'application/pdf', '.txt': 'text/plain',
    }.get(ext, 'application/octet-stream')


def default_root():
    here = os.path.dirname(os.path.abspath(__file__))
    return os.environ.get('BLOB_ROOT') or os.path.join(here, 'instance', 'blobs')


def kind_for(mime):
    """Coarse kind used by the UI and the extractor dispatch."""
    if mime.startswith('image/'):
        return 'image'
    if mime == 'application/pdf':
        return 'pdf'
    if mime.startswith('text/'):
        return 'text'
    return 'file'


def validate_upload(filename, data):
    """Validate one uploaded file: returns (mime, kind) or raises BlobError."""
    if not data:
        raise BlobError('文件是空的')
    if len(data) > MAX_ATTACHMENT_BYTES:
        raise BlobError(
            '文件太大（%.0f KB > %d KB）。图片请在手机端压缩后再传，其他类型请换小一些的文件。'
            % (len(data) / 1024, MAX_ATTACHMENT_BYTES // 1024), status=413)
    ext = os.path.splitext(filename or '')[1].lower()
    if ext in _DANGEROUS_TEXT_EXT:
        raise BlobError(describe_rejection(filename))
    sniffed = sniff(data)
    if not sniffed:
        raise BlobError(describe_rejection(filename))
    mime, _ext = sniffed
    return mime, kind_for(mime)
