"""Evernote `.enex` import: parsing, text conversion, and the rules that matter.

The importer is a bulk path, but it must not become a way around the hub's own
rules — it calls the same `message_ingest` the API and web page use, and validates
each resource with the same `blob_store.validate_upload`.
"""

import base64
import hashlib
import importlib.util
import os
import struct
import xml.etree.ElementTree as ET
import zlib

import pytest

import blob_store
import message_ingest
from models import db, Message

_SPEC = importlib.util.spec_from_file_location(
    'import_evernote',
    os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                 'scripts', 'import-evernote.py'))
evernote = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(evernote)


def make_png(width=8, height=8):
    raw = b''.join(b'\x00' + b'\x11\x22\x33' * width for _ in range(height))

    def chunk(tag, data):
        body = tag + data
        return (struct.pack('>I', len(data)) + body
                + struct.pack('>I', zlib.crc32(body) & 0xffffffff))

    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw))
            + chunk(b'IEND', b''))


def note_xml(resource_blob=None, resource_name='whiteboard.png', body=None,
             guid='abc-123', title='会议记录', tags=('工作',), source_url=None):
    """A note shaped like the real export (content is CDATA-embedded ENML)."""
    media = ''
    resource = ''
    if resource_blob is not None:
        digest = hashlib.md5(resource_blob).hexdigest()
        media = '<div><en-media type="image/png" hash="%s"/></div>' % digest
        resource = '''
  <resource>
    <data encoding="base64">
%s</data>
    <mime>image/png</mime>
    <resource-attributes><file-name>%s</file-name></resource-attributes>
  </resource>''' % (base64.encodebytes(resource_blob).decode(), resource_name)

    enml = body if body is not None else (
        '<en-note><div>讨论了<span style="font-weight: bold;">三个方向</span></div>'
        '<div><en-todo checked="true"/>已完成初稿</div>%s<div>上面是白板照片</div></en-note>' % media)
    tag_xml = ''.join('<tag>%s</tag>' % t for t in tags)
    attrs = '<note-attributes><guid>%s</guid>%s</note-attributes>' % (
        guid, '<source-url>%s</source-url>' % source_url if source_url else '')
    return ET.fromstring(
        '<note><title>%s</title><content><![CDATA[%s]]></content>'
        '<created>20260910T020000Z</created><updated>20260911T030000Z</updated>'
        '%s%s%s</note>' % (title, enml, tag_xml, attrs, resource))


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------

def test_evernote_timestamps_are_utc():
    parsed = evernote.parse_evernote_time('20260910T020000Z')
    assert parsed.year == 2026 and parsed.month == 9 and parsed.day == 10
    assert parsed.hour == 2 and parsed.tzinfo is not None
    assert evernote.parse_evernote_time('') is None
    assert evernote.parse_evernote_time('rubbish') is None


def test_enml_becomes_readable_text():
    note = note_xml(body='<en-note><div>讨论了<b>三个方向</b></div>'
                         '<div><en-todo checked="true"/>已完成初稿</div></en-note>')
    text = evernote.note_text(note.find('content'), {})
    # html_to_text 会在块级/内联标签边界插入空格，"讨论了<span>三个方向</span>" 会变成
    # "讨论了 三个方向" —— 这是转换器的既有行为（邮件正文也走它），断言两段都在即可。
    assert '讨论了' in text and '三个方向' in text
    assert '[ ] 已完成初稿' in text          # checkboxes stay visible
    assert '<' not in text and '>' not in text


def test_en_media_is_replaced_by_the_resource_name():
    """ENEX has no hash field on <resource>; the hash in <en-media> is the MD5 of
    the resource bytes, so the importer must compute it to name the attachment."""
    blob = make_png()
    note = note_xml(resource_blob=blob, resource_name='whiteboard.png')
    files, skipped, by_hash = evernote.read_resources(note)
    assert skipped == []
    assert by_hash[hashlib.md5(blob).hexdigest()] == 'whiteboard.png'

    text = evernote.note_text(note.find('content'), by_hash)
    assert '[附件: whiteboard.png]' in text


def test_oversized_resource_is_recorded_not_fatal():
    """A bulk import must keep the note. The upload API treats 413 as fatal because
    a client can compress and retry; a script cannot, so the attachment is recorded
    in attachments_skipped instead of losing the whole note."""
    huge = make_png() + b'\x00' * blob_store.MAX_ATTACHMENT_BYTES
    note = note_xml(resource_blob=huge, resource_name='scan-huge.png')
    files, skipped, _by_hash = evernote.read_resources(note)

    assert files == []
    assert skipped[0]['name'] == 'scan-huge.png'
    assert skipped[0]['size'] > blob_store.MAX_ATTACHMENT_BYTES
    assert '超过' in skipped[0]['reason']


def test_unsupported_resource_type_is_recorded():
    payload = b'MZ\x90\x00' + b'\x00' * 64          # an executable, not on the whitelist
    note = note_xml(resource_blob=payload, resource_name='thing.exe')
    files, skipped, _ = evernote.read_resources(note)
    assert files == []
    assert '类型不受支持' in skipped[0]['reason']


# ---------------------------------------------------------------------------
# Importing
# ---------------------------------------------------------------------------

def test_import_stores_metadata_and_attachment(app, store):
    with app.app_context():
        db.create_all()
        note = note_xml(resource_blob=make_png(), source_url='https://example.com/q3',
                        tags=('工作', 'Q3'))
        status, message = evernote.import_note(note, set())
        db.session.commit()

        assert status == 'created'
        metadata = message.message_metadata
        assert metadata['title'] == '会议记录'
        assert metadata['tags'] == ['工作', 'Q3']
        assert metadata['url'] == 'https://example.com/q3'
        assert metadata['evernote_guid'] == 'abc-123'
        assert message.source_device_id == evernote.DEVICE_ID
        assert message.timestamp.year == 2026        # created, not import time
        # attachments hang off the record and are queued for extraction
        attachment = metadata['attachments'][0]
        assert attachment['name'] == 'whiteboard.png'
        assert attachment['extraction']['status'] == 'pending'
        assert store.exists(attachment['key'])


def test_reimport_is_skipped_by_guid(app, store):
    with app.app_context():
        db.create_all()
        note = note_xml()
        status, message = evernote.import_note(note, set())
        db.session.commit()
        known = {message.message_metadata['evernote_guid']}

        status2, guid2 = evernote.import_note(note_xml(), known)
        assert status2 == 'skipped'
        assert guid2 == 'abc-123'
        assert db.session.query(Message).count() == 1


def test_empty_note_does_not_clutter_the_timeline(app, store):
    with app.app_context():
        db.create_all()
        status, _guid = evernote.import_note(
            note_xml(body='<en-note><div><br/></div></en-note>'), set())
        assert status == 'empty'
        assert db.session.query(Message).count() == 0


def test_note_with_only_a_skipped_attachment_still_keeps_its_text(app, store):
    """The whole point of recording skips: the note is worth keeping even when the
    file could not be."""
    huge = make_png() + b'\x00' * blob_store.MAX_ATTACHMENT_BYTES
    with app.app_context():
        db.create_all()
        status, message = evernote.import_note(
            note_xml(resource_blob=huge, resource_name='scan-huge.png',
                     body='<en-note><div>扫描件见附件</div></en-note>'), set())
        db.session.commit()

        assert status == 'created'
        assert '扫描件见附件' in message.content
        assert not (message.message_metadata.get('attachments') or [])
        assert message.message_metadata['attachments_skipped'][0]['name'] == 'scan-huge.png'


def test_import_goes_through_the_shared_ingest(app, store):
    """Not a formality: it is what keeps the size cap and type whitelist identical
    to the API and the web page."""
    with app.app_context():
        db.create_all()
        note = note_xml(body='', resource_blob=None)     # no body, no attachment
        status, _ = evernote.import_note(note, set())
        assert status == 'empty'                          # the shared invariant holds
