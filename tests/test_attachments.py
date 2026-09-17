"""Attachment storage, upload validation, download auth and extraction."""

import base64
import os

import pytest

import blob_store
import extraction
from blob_store import BlobStore, validate_upload, sniff, BlobError
from models import db, Message

# 1x1 PNG — a real signature so the magic-byte sniffer is actually exercised.
PNG_1PX = base64.b64decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/'
    'q842iQAAAABJRU5ErkJggg==')

MINIMAL_PDF = (b'%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n'
               b'%%EOF\n')


# ---------------------------------------------------------------------------
# Sniffing / validation
# ---------------------------------------------------------------------------

def test_sniff_recognises_allowed_types():
    assert sniff(PNG_1PX) == ('image/png', '.png')
    assert sniff(b'\xff\xd8\xff\xe0' + b'x' * 10) == ('image/jpeg', '.jpg')
    assert sniff(b'GIF89a' + b'x' * 10) == ('image/gif', '.gif')
    assert sniff(b'RIFF\x00\x00\x00\x00WEBP' + b'x' * 4) == ('image/webp', '.webp')
    assert sniff(MINIMAL_PDF) == ('application/pdf', '.pdf')
    assert sniff('中文纯文本'.encode('utf-8')) == ('text/plain', '.txt')


def test_sniff_rejects_unknown_binary():
    assert sniff(b'\x7fELF\x02\x01\x01' + b'\x00' * 8) is None
    assert sniff(b'MZ\x90\x00' + b'\x00' * 8) is None


def test_sniff_ignores_the_declared_content_type():
    """Validation is on the bytes: renaming a binary to .png must not work."""
    with pytest.raises(BlobError):
        validate_upload('evil.png', b'\x7fELF\x02\x01\x01' + b'\x00' * 64)


def test_dangerous_text_extensions_are_refused():
    """SVG/HTML/JS are text, but they execute when rendered in a browser."""
    html = b'<html><script>alert(1)</script></html>'
    with pytest.raises(BlobError):
        validate_upload('x.html', html)
    with pytest.raises(BlobError):
        validate_upload('x.svg', b'<svg xmlns="http://www.w3.org/2000/svg"></svg>')
    # Same bytes with an innocuous name are stored as text and served as a download.
    mime, kind = validate_upload('notes.txt', html)
    assert (mime, kind) == ('text/plain', 'text')


def test_oversized_upload_is_refused_with_413():
    with pytest.raises(BlobError) as exc:
        validate_upload('big.png', PNG_1PX + b'\x00' * blob_store.MAX_ATTACHMENT_BYTES)
    assert exc.value.status == 413


# ---------------------------------------------------------------------------
# Store: content addressing, dedup, atomicity, GC
# ---------------------------------------------------------------------------

def test_key_is_derived_from_content_and_mime(store):
    key, sha256, created = store.put_bytes(PNG_1PX, 'image/png')
    assert created is True
    assert key == '%s/%s/%s.png' % (sha256[:2], sha256[2:4], sha256)
    assert store.exists(key)


def test_identical_content_is_not_stored_twice(store):
    key1, sha1, created1 = store.put_bytes(PNG_1PX, 'image/png')
    key2, sha2, created2 = store.put_bytes(PNG_1PX, 'image/png')
    assert (key1, sha1) == (key2, sha2)
    assert (created1, created2) == (True, False)
    total, files = store.usage()
    assert files == 1


def test_no_temp_files_are_left_behind(store):
    store.put_bytes(PNG_1PX, 'image/png')
    tmp_dir = os.path.join(store.root, '.tmp')
    assert os.listdir(tmp_dir) == [] if os.path.isdir(tmp_dir) else True


def test_garbage_collection_removes_only_unreferenced(store):
    kept, _sha, _ = store.put_bytes(PNG_1PX, 'image/png')
    orphan, _sha2, _ = store.put_bytes(MINIMAL_PDF, 'application/pdf')
    removed = store.collect_garbage({kept}, dry_run=True)
    assert removed == [orphan]
    assert store.exists(orphan)          # dry run must not delete
    removed = store.collect_garbage({kept}, dry_run=False)
    assert removed == [orphan]
    assert store.exists(kept)
    assert not store.exists(orphan)


# ---------------------------------------------------------------------------
# HTTP surface
# ---------------------------------------------------------------------------

def upload(client, headers, files, **fields):
    data = {
        'source_device_id': fields.pop('source_device_id', 'android-phone-1'),
        'type': fields.pop('type', 'NOTE'),
        'sender': fields.pop('sender', 'OPPO PHZ110'),
        'content': fields.pop('content', ''),
        'timestamp': fields.pop('timestamp', '2026-09-13T10:00:00Z'),
        'metadata': fields.pop('metadata', '{}'),
    }
    data.update(fields)
    for name, payload, content_type in files:
        data.setdefault('attachments', [])
    payload = dict(data)
    payload['attachments'] = [(BytesIO(p), n) for n, p, _ct in files]
    return client.post('/api/v1/messages', data=payload,
                       headers=headers, content_type='multipart/form-data')


from io import BytesIO  # noqa: E402  (kept next to its only user for clarity)


def test_multipart_upload_stores_attachment(client, auth_headers, store):
    r = upload(client, auth_headers,
               [('shot.png', PNG_1PX, 'image/png')],
               content='截图说明')
    assert r.status_code == 201, r.get_json()
    body = r.get_json()
    attachment = body['attachments'][0]
    assert attachment['mime'] == 'image/png'
    assert attachment['kind'] == 'image'
    assert attachment['name'] == 'shot.png'
    assert attachment['size'] == len(PNG_1PX)
    assert attachment['extraction']['status'] == 'pending'
    assert attachment['url'].startswith('/api/v1/blobs/')
    assert store.exists(attachment['key'])


def test_multipart_allows_empty_body_when_a_file_is_present(client, auth_headers, store):
    """An image-only note: OCR fills `content` later."""
    r = upload(client, auth_headers, [('shot.png', PNG_1PX, 'image/png')])
    assert r.status_code == 201, r.get_json()
    assert r.get_json()['data']['content'] == ''


def test_empty_body_without_attachments_is_refused(client, auth_headers):
    r = client.post('/api/v1/messages', json={
        'source_device_id': 'd', 'type': 'NOTE', 'sender': 'me', 'content': '',
        'timestamp': '2026-09-13T10:00:00Z'}, headers=auth_headers)
    assert r.status_code == 400
    assert 'content' in r.get_json()['error']


def test_json_post_still_works_unchanged(client, auth_headers):
    """The collector's existing JSON path must not regress."""
    r = client.post('/api/v1/messages', json={
        'source_device_id': 'android-phone-1', 'type': 'SMS', 'sender': '+15550001111',
        'content': 'hello', 'timestamp': '2026-09-13T10:00:00Z',
        'metadata': {'source': 'phone'}}, headers=auth_headers)
    assert r.status_code == 201
    assert r.get_json()['data']['content'] == 'hello'


def test_note_and_document_types_are_accepted(client, auth_headers):
    for message_type in ('NOTE', 'DOCUMENT'):
        r = client.post('/api/v1/messages', json={
            'source_device_id': 'd', 'type': message_type, 'sender': 'me',
            'content': 'x', 'timestamp': '2026-09-13T10:00:00Z'}, headers=auth_headers)
        assert r.status_code == 201, (message_type, r.get_json())


def test_unknown_type_is_rejected(client, auth_headers):
    r = client.post('/api/v1/messages', json={
        'source_device_id': 'd', 'type': 'WAT', 'sender': 'me',
        'content': 'x', 'timestamp': '2026-09-13T10:00:00Z'}, headers=auth_headers)
    assert r.status_code == 400


def test_disallowed_file_type_is_refused(client, auth_headers, store):
    r = upload(client, auth_headers, [('app.bin', b'\x7fELF\x02\x01\x01\x00\x00\x00', 'application/x-binary')],
               content='尝试上传可执行文件')
    assert r.status_code == 415
    assert '不支持' in r.get_json()['error']


def test_oversized_file_is_refused_by_http(client, auth_headers):
    big = PNG_1PX + b'\x00' * blob_store.MAX_ATTACHMENT_BYTES
    r = upload(client, auth_headers, [('big.png', big, 'image/png')], content='x')
    assert r.status_code == 413


def test_limits_endpoint(client, auth_headers):
    r = client.get('/api/v1/attachments/limits', headers=auth_headers)
    assert r.status_code == 200
    body = r.get_json()
    assert body['max_bytes'] == blob_store.MAX_ATTACHMENT_BYTES == 1024 * 1024
    assert 'image/png' in body['allowed'] and 'application/pdf' in body['allowed']


def test_download_with_api_key(client, auth_headers, store):
    r = upload(client, auth_headers, [('shot.png', PNG_1PX, 'image/png')], content='x')
    key = r.get_json()['attachments'][0]['key']
    got = client.get('/api/v1/blobs/%s' % key, headers=auth_headers)
    assert got.status_code == 200
    assert got.data == PNG_1PX
    assert got.headers['X-Content-Type-Options'] == 'nosniff'
    assert got.headers['Content-Disposition'] == 'inline'      # images render
    assert 'immutable' in got.headers['Cache-Control']


def test_download_requires_auth(client, auth_headers, store):
    r = upload(client, auth_headers, [('shot.png', PNG_1PX, 'image/png')], content='x')
    key = r.get_json()['attachments'][0]['key']
    assert client.get('/api/v1/blobs/%s' % key).status_code == 401


def test_signed_link_works_without_a_key(client, auth_headers, store):
    """Browsers cannot send headers; the HMAC token is how <img> works."""
    from api.v1.blobs import sign
    r = upload(client, auth_headers, [('shot.png', PNG_1PX, 'image/png')], content='x')
    key = r.get_json()['attachments'][0]['key']
    assert client.get('/api/v1/blobs/%s?token=%s' % (key, sign(key))).status_code == 200


def test_bad_or_expired_token_is_refused(client, auth_headers, store):
    from api.v1.blobs import sign
    r = upload(client, auth_headers, [('shot.png', PNG_1PX, 'image/png')], content='x')
    key = r.get_json()['attachments'][0]['key']
    assert client.get('/api/v1/blobs/%s?token=nonsense' % key).status_code == 401
    expired = sign(key, expires_at=1)
    assert client.get('/api/v1/blobs/%s?token=%s' % (key, expired)).status_code == 401


def test_malformed_blob_key_is_refused(client, auth_headers):
    assert client.get('/api/v1/blobs/../../etc/passwd',
                      headers=auth_headers).status_code in (400, 404)
    assert client.get('/api/v1/blobs/aa/bb/short', headers=auth_headers).status_code == 400


def test_non_image_is_forced_to_download(client, auth_headers, store):
    r = upload(client, auth_headers, [('notes.txt', b'hello world', 'text/plain')], content='x')
    key = r.get_json()['attachments'][0]['key']
    got = client.get('/api/v1/blobs/%s' % key, headers=auth_headers)
    assert got.headers['Content-Disposition'] == 'attachment'


# ---------------------------------------------------------------------------
# Extraction plumbing
# ---------------------------------------------------------------------------

def test_apply_result_fills_empty_content():
    message = Message(id='m1', source_device_id='d', type='NOTE', sender='me',
                      content='', message_metadata={'attachments': [
                          {'key': 'aa/bb/x.png', 'kind': 'image', 'mime': 'image/png'}]})
    assert extraction.apply_result(message, 0, {
        'status': 'done', 'engine': 'tesseract', 'text': '识别出来的字'})
    assert message.content == '识别出来的字'
    state = message.message_metadata['attachments'][0]['extraction']
    assert state['status'] == 'done'
    assert state['chars'] == 6
    assert state['applied_to_content'] is True


def test_apply_result_never_overwrites_client_text():
    message = Message(id='m2', source_device_id='d', type='NOTE', sender='me',
                      content='用户自己写的正文',
                      message_metadata={'attachments': [
                          {'key': 'aa/bb/x.png', 'kind': 'image', 'mime': 'image/png'}]})
    extraction.apply_result(message, 0, {'status': 'done', 'text': 'OCR 结果'})
    assert message.content == '用户自己写的正文'
    assert message.message_metadata['attachments'][0]['extraction']['applied_to_content'] is False


def test_rerun_replaces_previously_extracted_text():
    """A rebuild must replace derived text, not stack it up."""
    message = Message(id='m3', source_device_id='d', type='NOTE', sender='me',
                      content='旧的 OCR 结果',
                      message_metadata={'attachments': [
                          {'key': 'k', 'kind': 'image', 'mime': 'image/png',
                           'extraction': {'status': 'done', 'applied_to_content': True}}]})
    extraction.apply_result(message, 0, {'status': 'done', 'text': '新的 OCR 结果'})
    assert message.content == '新的 OCR 结果'


def test_extract_text_file_reads_plain_text(tmp_path):
    path = tmp_path / 'a.txt'
    path.write_text('你好 hello', encoding='utf-8')
    result = extraction.extract_text_file(str(path))
    assert result['status'] == 'done'
    assert result['text'] == '你好 hello'


def test_extract_text_file_handles_gb18030(tmp_path):
    path = tmp_path / 'gb.txt'
    path.write_bytes('中文编码'.encode('gb18030'))
    result = extraction.extract_text_file(str(path))
    assert '中文' in result['text']


def test_missing_blob_reports_failure(store):
    result = extraction.extract(store, 'aa/bb/' + 'f' * 64 + '.png', 'image/png', 'image')
    assert result['status'] == 'failed'
    assert '不存在' in result['error']


def test_extract_dispatch_marks_unsupported_kinds_skipped(store):
    key, _sha, _ = store.put_bytes(b'\x00\x01\x02\x03' + b'\x00' * 32, 'application/pdf')
    result = extraction.extract(store, key, 'application/octet-stream', 'file')
    assert result['status'] == 'skipped'


@pytest.mark.skipif(not extraction.available_engines()['tesseract'],
                    reason='本机没有 tesseract，真实 OCR 在服务器上验证')
def test_real_ocr_on_a_png(store):
    key, _sha, _ = store.put_bytes(PNG_1PX, 'image/png')
    result = extraction.extract(store, key, 'image/png', 'image')
    assert result['status'] in ('empty', 'done')   # a 1x1 blank pixel has no text


# ---------------------------------------------------------------------------
# Mail collector side: deciding what to upload vs record
# ---------------------------------------------------------------------------

import email  # noqa: E402

import mail_collector as mc  # noqa: E402

MIME_WITH_ATTACHMENTS = b'''From: a@b.com
To: me@gmail.com
Subject: with attachment
Date: Mon, 1 Sep 2025 10:00:00 +0100
Message-ID: <x@y>
MIME-Version: 1.0
Content-Type: multipart/mixed; boundary="B"

--B
Content-Type: text/plain

hello body
--B
Content-Type: application/pdf; name="invoice.pdf"
Content-Disposition: attachment; filename="invoice.pdf"

%PDF-1.4 fake
--B
Content-Type: application/zip; name="archive.zip"
Content-Disposition: attachment; filename="archive.zip"

PK fake
--B--
'''


def test_collector_uploads_allowed_and_records_the_rest():
    msg = email.message_from_bytes(MIME_WITH_ATTACHMENTS)
    attachments, skipped = mc.split_attachments(
        msg, 1024 * 1024, mc.FALLBACK_ALLOWED_MIME)

    assert [name for name, _mime, _blob in attachments] == ['invoice.pdf']
    # The zip is not uploadable, but the fact that it existed must survive.
    assert skipped == [{'name': 'archive.zip', 'size': 7,
                        'mime': 'application/zip', 'reason': '类型不受支持，未存储'}]


def test_collector_records_oversized_attachments():
    big = (b'--B\nContent-Type: image/png; name="huge.png"\n'
           b'Content-Disposition: attachment; filename="huge.png"\n\n'
           + b'\x89PNG\r\n\x1a\n' + b'x' * 5000 + b'\n--B--\n')
    raw = (b'From: a@b.com\nSubject: big\nMIME-Version: 1.0\n'
           b'Content-Type: multipart/mixed; boundary="B"\n\n'
           b'--B\nContent-Type: text/plain\n\nbody\n' + big)
    attachments, skipped = mc.split_attachments(
        email.message_from_bytes(raw), 1024, mc.FALLBACK_ALLOWED_MIME)

    assert attachments == []
    assert skipped[0]['name'] == 'huge.png'
    assert '超过' in skipped[0]['reason']
    assert skipped[0]['size'] > 1024


def test_collector_ignores_inline_preview_images():
    """HTML mails carry logo/preview images; those are not attachments."""
    raw = (b'From: a@b.com\nSubject: html mail\nMIME-Version: 1.0\n'
           b'Content-Type: multipart/related; boundary="B"\n\n'
           b'--B\nContent-Type: text/html\n\n<p>hi</p>\n'
           b'--B\nContent-Type: image/png\nContent-ID: <logo>\n\n'
           + PNG_1PX + b'\n--B--\n')
    attachments, skipped = mc.split_attachments(
        email.message_from_bytes(raw), 1024 * 1024, mc.FALLBACK_ALLOWED_MIME)
    assert attachments == []
    assert skipped == []


def test_collector_metadata_carries_skipped_list():
    parsed = mc.parse_message(MIME_WITH_ATTACHMENTS)
    _atts, skipped = mc.split_attachments(
        email.message_from_bytes(MIME_WITH_ATTACHMENTS), 1024 * 1024,
        mc.FALLBACK_ALLOWED_MIME)
    parsed['attachments_skipped'] = skipped
    metadata = mc.build_payload(parsed, 'main')['metadata']
    assert metadata['attachments_skipped'][0]['name'] == 'archive.zip'


# ---------------------------------------------------------------------------
# Persistence: metadata edits must survive a commit
# ---------------------------------------------------------------------------

def test_reset_status_actually_persists(app):
    """Regression: a shallow copy made SQLAlchemy see "no change" and skip the
    UPDATE, so re-extraction silently did nothing. Only a fresh query catches it."""
    import importlib.util
    from models import db, Message

    spec = importlib.util.spec_from_file_location(
        'reextract', os.path.join(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))), 'scripts', 'reextract.py'))
    reextract = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reextract)

    with app.app_context():
        db.session.add(Message(
            id='m-reset', source_device_id='d', type='DOCUMENT', sender='me',
            content='旧文本', timestamp=__import__('datetime').datetime(2026, 9, 13),
            received_at=__import__('datetime').datetime(2026, 9, 13),
            message_metadata={'attachments': [
                {'key': 'aa/bb/' + 'a' * 64 + '.png', 'kind': 'image',
                 'mime': 'image/png',
                 'extraction': {'status': 'done', 'applied_to_content': True}}]}))
        db.session.commit()

        message = db.session.get(Message, 'm-reset')
        assert reextract.reset_status(message) == 1
        db.session.commit()
        db.session.expunge_all()                      # force a real re-read
        stored = db.session.get(Message, 'm-reset')
        assert stored.message_metadata['attachments'][0]['extraction']['status'] == 'pending'


def test_apply_result_actually_persists(app):
    from datetime import datetime
    from models import db, Message

    with app.app_context():
        db.session.add(Message(
            id='m-apply', source_device_id='d', type='DOCUMENT', sender='me',
            content='', timestamp=datetime(2026, 9, 13),
            received_at=datetime(2026, 9, 13),
            message_metadata={'attachments': [
                {'key': 'aa/bb/' + 'b' * 64 + '.txt', 'kind': 'text',
                 'mime': 'text/plain', 'extraction': {'status': 'pending'}}]}))
        db.session.commit()

        message = db.session.get(Message, 'm-apply')
        extraction.apply_result(message, 0, {'status': 'done', 'engine': 'plain',
                                             'text': '提取出来的字'})
        db.session.commit()
        db.session.expunge_all()
        stored = db.session.get(Message, 'm-apply')
        assert stored.content == '提取出来的字'
        assert stored.message_metadata['attachments'][0]['extraction']['status'] == 'done'


def test_extraction_text_is_kept_when_content_already_has_text():
    """Regression: an email body + an attached PDF used to lose the PDF text
    entirely — it was stripped from metadata and not written to content, so the
    AI would never see it."""
    message = Message(id='m4', source_device_id='d', type='EMAIL', sender='a@b.com',
                      content='请看附件的发票',
                      message_metadata={'attachments': [
                          {'key': 'k', 'kind': 'pdf', 'mime': 'application/pdf',
                           'extraction': {'status': 'pending'}}]})
    extraction.apply_result(message, 0, {'status': 'done', 'engine': 'pdftotext',
                                         'text': 'INVOICE TOTAL 42.00'})
    assert message.content == '请看附件的发票'          # body untouched
    state = message.message_metadata['attachments'][0]['extraction']
    assert state['text'] == 'INVOICE TOTAL 42.00'        # kept in metadata
    assert state['applied_to_content'] is False


def test_extraction_text_is_not_duplicated_when_it_fills_content():
    message = Message(id='m5', source_device_id='d', type='NOTE', sender='me',
                      content='', message_metadata={'attachments': [
                          {'key': 'k', 'kind': 'image', 'mime': 'image/png',
                           'extraction': {'status': 'pending'}}]})
    extraction.apply_result(message, 0, {'status': 'done', 'engine': 'tesseract',
                                         'text': '识别结果'})
    state = message.message_metadata['attachments'][0]['extraction']
    assert message.content == '识别结果'
    assert 'text' not in state                            # exactly one copy


def test_blob_url_switches_to_the_separate_origin(app, client, auth_headers, store):
    """With a blob host configured, browser-facing URLs must leave the web origin.

    Serving user-uploaded files from the same origin as the web UI is the risk the
    separate origin exists to remove, so the switch has to be visible in the API.
    """
    app.config['BLOB_PUBLIC_BASE'] = 'https://mhblob.example.com'
    r = upload(client, auth_headers, [('shot.png', PNG_1PX, 'image/png')], content='x')
    attachment = r.get_json()['attachments'][0]

    assert attachment['url'] == 'https://mhblob.example.com/%s' % attachment['key']
    # The API-key path still works against that host (same auth, different origin).
    assert client.get(attachment['url'], headers=auth_headers).status_code == 404  # key not in this store view


def test_blob_url_stays_relative_without_a_blob_host(client, auth_headers, store):
    r = upload(client, auth_headers, [('shot.png', PNG_1PX, 'image/png')], content='x')
    attachment = r.get_json()['attachments'][0]
    assert attachment['url'] == '/api/v1/blobs/%s' % attachment['key']


# ---------------------------------------------------------------------------
# The PDF "is this a scan?" heuristic
# ---------------------------------------------------------------------------

def test_short_but_real_text_layer_is_usable():
    """Regression: an 18-char text layer on one page used to fall under an absolute
    20-char bar and get rendered + OCR'd for nothing."""
    assert extraction.pdf_text_is_usable('MH ACCEPTANCE 2026') is True


def test_watermark_only_text_is_not_usable():
    """Below the floor we assume a scan rather than store a stray watermark."""
    assert extraction.pdf_text_is_usable('Page 1') is False
    assert extraction.pdf_text_is_usable('DRAFT') is False


def test_thin_layer_over_many_pages_is_still_reported_usable():
    """A header-only scan is the deliberate cost of the floor: the text is used, but
    chars_per_page is recorded so it is visible, and --ocr redoes it on demand."""
    scan = 'Page 1 of 5\n\x0cPage 2 of 5\n\x0cPage 3 of 5\n\x0cPage 4 of 5\n\x0cPage 5 of 5'
    assert extraction.pdf_text_is_usable(scan) is True
    assert extraction.count_pages(scan) == 5


def test_realistic_invoice_text_layer_is_usable():
    assert extraction.pdf_text_is_usable('Invoice INV-830425\nTotal 42.00\n' * 5) is True


def test_empty_pdf_text_is_not_usable():
    assert extraction.pdf_text_is_usable('') is False
    assert extraction.pdf_text_is_usable('   \n\f  \n') is False


def test_count_pages_uses_form_feeds():
    assert extraction.count_pages('one page') == 1
    assert extraction.count_pages('p1\fp2\fp3') == 3


# ---------------------------------------------------------------------------
# Attachment detail page (a view over the attachment record)
# ---------------------------------------------------------------------------

def upload_then_index(client, auth_headers, store, name='shot.png', payload=None,
                      content=''):
    import io
    r = client.post('/api/v1/messages', data={
        'source_device_id': 'd', 'type': 'NOTE', 'sender': 'OPPO PHZ110',
        'content': content, 'timestamp': '2026-09-13T10:00:00Z',
        'metadata': '{}',
        'attachments': (io.BytesIO(payload or PNG_1PX), name),
    }, headers=auth_headers, content_type='multipart/form-data')
    assert r.status_code == 201, r.get_json()
    return r.get_json()['id']


def test_detail_page_shows_original_and_processing_info(app, client, auth_headers, store):
    message_id = upload_then_index(client, auth_headers, store, content='')
    with app.app_context():
        from models import db, Message
        message = db.session.get(Message, message_id)
        extraction.apply_result(message, 0, {
            'status': 'done', 'engine': 'tesseract', 'text': '识别出来的中文'})
        db.session.commit()

    page = client.get('/messages/%s/attachments/0' % message_id)
    assert page.status_code == 200
    body = page.get_data(as_text=True)
    assert 'shot.png' in body                       # the original, named
    assert '原件预览' in body and '处理信息' in body
    assert 'tesseract' in body                      # which engine ran
    assert '识别出来的中文' in body                   # the extracted text, readable
    assert 'sha256' in body
    # Human-readable size, not raw bytes only
    assert '70 B' in body or 'B<' in body or 'B ' in body


def test_detail_page_explains_where_the_text_went(app, client, auth_headers, store):
    """With a body already present the extracted text stays in the record; the page
    must say so rather than imply the message text is the OCR result."""
    message_id = upload_then_index(client, auth_headers, store, content='邮件正文在此')
    with app.app_context():
        from models import db, Message
        message = db.session.get(Message, message_id)
        extraction.apply_result(message, 0, {'status': 'done', 'engine': 'pdftotext',
                                             'text': 'PDF 抽出来的字'})
        db.session.commit()

    body = client.get('/messages/%s/attachments/0' % message_id).get_data(as_text=True)
    assert 'PDF 抽出来的字' in body
    assert '没覆盖' in body or '保留在附件记录' in body


def test_detail_page_rejects_a_bad_index(app, client, auth_headers, store):
    message_id = upload_then_index(client, auth_headers, store)
    assert client.get('/messages/%s/attachments/9' % message_id).status_code == 302


def test_reextract_marks_pending_and_survives_a_fresh_query(app, client, auth_headers, store):
    """The button must actually queue work — a shallow metadata edit would produce a
    redirect and a success flash while changing nothing."""
    message_id = upload_then_index(client, auth_headers, store)
    with app.app_context():
        from models import db, Message
        message = db.session.get(Message, message_id)
        extraction.apply_result(message, 0, {'status': 'done', 'engine': 'tesseract',
                                             'text': '旧结果'})
        db.session.commit()

    r = client.post('/messages/%s/attachments/0/reextract' % message_id,
                    data={'force_ocr': 'yes'}, follow_redirects=True)
    assert r.status_code == 200

    with app.app_context():
        from models import db, Message
        db.session.expunge_all()
        state = db.session.get(Message, message_id).message_metadata['attachments'][0]['extraction']
    assert state['status'] == 'pending'
    assert state['requested_ocr'] is True
    assert 'text' not in state          # stale text must not linger


def test_requested_ocr_forces_the_ocr_path(monkeypatch, store):
    """A per-attachment request has to reach the extractor, or the button is a no-op
    for exactly the scanned-PDF case it exists for."""
    seen = {}

    def fake_extract(store_, key, mime, kind, force_ocr=False):
        seen['force_ocr'] = force_ocr
        return {'status': 'done', 'text': 'x'}

    monkeypatch.setattr(extraction, 'extract', fake_extract)

    class FakeSession:
        def commit(self):
            pass

    message = Message(id='m-req', source_device_id='d', type='DOCUMENT', sender='me',
                      content='', message_metadata={'attachments': [
                          {'key': 'aa/bb/' + 'c' * 64 + '.pdf', 'kind': 'pdf',
                           'mime': 'application/pdf',
                           'extraction': {'status': 'pending', 'requested_ocr': True}}]})
    extraction.process_message(FakeSession(), store, message)
    assert seen['force_ocr'] is True


def test_json_is_not_ascii_escaped(app):
    """Regression: Flask's default escaped Chinese into \\uXXXX, which made the
    metadata panel (and every API response) unreadable."""
    with app.app_context():
        from flask import jsonify
        payload = jsonify({'content': '中文测试'}).get_data(as_text=True)
    assert '中文测试' in payload
    assert '\\u4e2d' not in payload


# ---------------------------------------------------------------------------
# Web "add" page — same rules as the API, because it shares message_ingest
# ---------------------------------------------------------------------------

def test_web_add_page_renders(client):
    page = client.get('/messages/new')
    assert page.status_code == 200
    body = page.get_data(as_text=True)
    assert '添加' in body
    assert '选图片' in body and '选文件' in body
    assert '1048576' in body or '1024 KB' in body      # the limit is published to the page


def test_web_add_creates_a_text_note(client):
    from models import db, Message
    r = client.post('/messages/new', data={
        'content': '从网页记一笔', 'sender': 'web', 'type': 'NOTE'}, follow_redirects=False)
    assert r.status_code == 302
    with client.application.app_context():
        message = db.session.query(Message).order_by(Message.received_at.desc()).first()
        assert message.content == '从网页记一笔'
        assert message.source_device_id == 'web'
        assert message.type == 'NOTE'


def test_web_add_with_an_image_stores_the_blob(client, store):
    import io
    from models import db, Message
    r = client.post('/messages/new', data={
        'content': '', 'sender': 'web', 'type': 'NOTE',
        'attachments': (io.BytesIO(PNG_1PX), 'shot.png'),
    }, content_type='multipart/form-data', follow_redirects=False)
    assert r.status_code == 302
    with client.application.app_context():
        message = db.session.query(Message).order_by(Message.received_at.desc()).first()
        attachments = message.message_metadata['attachments']
        assert len(attachments) == 1
        assert attachments[0]['extraction']['status'] == 'pending'
        assert store.exists(attachments[0]['key'])


def test_web_add_records_source_url_and_title(client):
    from models import db, Message
    client.post('/messages/new', data={
        'content': '摘录', 'sender': 'web', 'type': 'NOTE',
        'source_url': 'https://example.com/a', 'title': '示例标题'})
    with client.application.app_context():
        message = db.session.query(Message).order_by(Message.received_at.desc()).first()
        assert message.message_metadata['url'] == 'https://example.com/a'
        assert message.message_metadata['title'] == '示例标题'


def test_web_add_refuses_an_empty_submission(client):
    r = client.post('/messages/new', data={'content': '', 'sender': 'web', 'type': 'NOTE'},
                    follow_redirects=True)
    assert 'content 不能为空' in r.get_data(as_text=True)


def test_web_add_refuses_a_binary_file_and_keeps_the_typed_text(client):
    import io
    r = client.post('/messages/new', data={
        'content': '我写的字还在吗', 'sender': 'web', 'type': 'NOTE',
        'attachments': (io.BytesIO(b'\x7fELF\x02\x01\x01\x00\x00'), 'evil.bin'),
    }, content_type='multipart/form-data', follow_redirects=True)
    body = r.get_data(as_text=True)
    assert '不支持' in body
    assert '我写的字还在吗' in body        # the page re-renders with the text preserved


def test_web_add_rejects_an_over_quota_file(client):
    import io
    big = PNG_1PX + b'\x00' * blob_store.MAX_ATTACHMENT_BYTES
    r = client.post('/messages/new', data={
        'content': 'x', 'sender': 'web', 'type': 'NOTE',
        'attachments': (io.BytesIO(big), 'big.png'),
    }, content_type='multipart/form-data', follow_redirects=True)
    assert '太大' in r.get_data(as_text=True)


def test_ingest_accepts_a_datetime_object_for_timestamp():
    """Regression: marshmallow's DateTime only parses strings, so the web form's
    `datetime.now(...)` blew up with "Not a valid datetime" — a confusing error for
    a perfectly correct value. The shared ingest normalises it."""
    import message_ingest
    from models import db, Message
    from app import create_app

    app = create_app()
    with app.app_context():
        db.create_all()
        message, attachments, rejected = message_ingest.create_message({
            'source_device_id': 'web', 'type': 'NOTE', 'sender': 'web',
            'content': '带 datetime 的调用方', 'timestamp': __import__('datetime').datetime.now(
                __import__('datetime').timezone.utc)}, [], source='test')
        db.session.commit()
        assert message.timestamp is not None
        assert message.source_device_id == 'web'
