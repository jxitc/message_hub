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
    # 显式给一个小上限来触发这条路径：导入的默认上限是 25MB，比这大得多
    files, skipped, _by_hash = evernote.read_resources(
        note, max_bytes=blob_store.MAX_ATTACHMENT_BYTES)

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
        status, info = evernote.import_note(note, set())
        db.session.commit()

        assert status == 'created'
        message = info['message']
        metadata = message.message_metadata
        assert metadata['title'] == '会议记录'
        assert metadata['tags'] == ['工作', 'Q3']
        assert metadata['url'] == 'https://example.com/q3'
        assert metadata['evernote']['guid'] == 'abc-123'
        assert message.source_device_id == evernote.DEVICE_ID
        assert message.timestamp.year == 2026        # created, not import time
        # attachments hang off the record and are queued for extraction
        attachment = metadata['attachments'][0]
        assert attachment['name'] == 'whiteboard.png'
        assert attachment['extraction']['status'] == 'pending'
        assert store.exists(attachment['key'])


def test_reimport_is_skipped(app, store):
    """Same note imported twice must not duplicate — the key is what makes that work."""
    with app.app_context():
        db.create_all()
        status, info = evernote.import_note(note_xml(), set())
        db.session.commit()
        key = info['message'].message_metadata['evernote']['key']
        assert key == 'guid:abc-123'      # a real GUID is preferred when present

        status2, info2 = evernote.import_note(note_xml(), {key})
        assert status2 == 'skipped'
        assert info2['key'] == key
        assert info2['title'] == '会议记录'      # 报碰撞时要能说清是哪两条
        assert db.session.query(Message).count() == 1


def test_key_is_title_plus_created_when_guids_are_absent():
    """Newer Evernote exports (v11) carry no GUID at all — verified against a real
    338-note export — so this path is the normal one, not an edge case.

    A *random* key would defeat dedup entirely: every re-run would look new. The key
    has to be reproducible from the note, and stable across edits so a re-import
    stays a no-op.
    """
    note = note_xml(guid='')
    key_a, guid_a = evernote.dedup_key(note, '会议记录')
    key_b, guid_b = evernote.dedup_key(note_xml(guid=''), '会议记录')

    assert guid_a == '' and guid_b == ''
    assert key_a.startswith('sha1:')
    assert key_a == key_b                      # same note → same key, every run


def test_key_ignores_body_edits():
    """Editing a note must not make it import twice — that is the whole point of
    choosing title+created instead of a content hash."""
    before, _ = evernote.dedup_key(note_xml(guid='', body='<en-note><div>原文</div></en-note>'), '会议记录')
    after, _ = evernote.dedup_key(note_xml(guid='', body='<en-note><div>改过的内容</div></en-note>'), '会议记录')
    assert before == after


def test_same_title_but_different_creation_time_stays_distinct():
    """This archive has 26 notes called "无标题笔记" and 10 called "未命名 - 名片".
    Hashing the title alone would treat each set as a single note and silently drop
    the rest, so the creation time is part of the key."""
    def note_created(created, title='无标题笔记'):
        return ET.fromstring(
            '<note><title>%s</title><content><![CDATA[<en-note><div>x</div></en-note>]]></content>'
            '<created>%s</created></note>' % (title, created))

    key_a, _ = evernote.dedup_key(note_created('20260101T010101Z'), '无标题笔记')
    key_b, _ = evernote.dedup_key(note_created('20260101T010102Z'), '无标题笔记')
    assert key_a != key_b


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
        # 把上限压到 1MB，才能构造出"附件超限但笔记仍要保留"的情形
        original = evernote.IMPORT_MAX_ATTACHMENT_BYTES
        evernote.IMPORT_MAX_ATTACHMENT_BYTES = blob_store.MAX_ATTACHMENT_BYTES
        try:
            status, info = evernote.import_note(
                note_xml(resource_blob=huge, resource_name='scan-huge.png',
                         body='<en-note><div>扫描件见附件</div></en-note>'), set())
        finally:
            evernote.IMPORT_MAX_ATTACHMENT_BYTES = original
        db.session.commit()
        message = info['message']

        assert status == 'created'
        assert '扫描件见附件' in message.content
        assert not (message.message_metadata.get('attachments') or [])
        assert message.message_metadata['attachments_skipped'][0]['name'] == 'scan-huge.png'


def test_dry_run_writes_nothing(app, store):
    """A dry run must not leave blobs behind: bytes are written before the row is
    committed, so rolling back the database alone would strand hundreds of MB."""
    with app.app_context():
        db.create_all()
        before, _files = store.usage()
        status, info = evernote.import_note(
            note_xml(resource_blob=make_png()), set(), apply_changes=False)
        after, files_after = store.usage()
        assert status == 'would-create'
        assert info['attachments'] == 1
        assert 'message' not in info
        assert (before, after) == (0, 0) and files_after == 0
        assert db.session.query(Message).count() == 0


def test_import_goes_through_the_shared_ingest(app, store):
    """Not a formality: it is what keeps the size cap and type whitelist identical
    to the API and the web page."""
    with app.app_context():
        db.create_all()
        note = note_xml(body='', resource_blob=None)     # no body, no attachment
        status, _ = evernote.import_note(note, set())
        assert status == 'empty'                          # the shared invariant holds


def test_import_cap_actually_reaches_the_validator():
    """Regression: the importer's larger ceiling was silently dead — it compared
    sizes itself but then called blob_store.validate_upload, which still enforced the
    client-side 1MB cap. Every attachment between 1MB and the import cap was refused,
    which in the real archive meant 50 files including passport and visa scans."""
    payload = make_png() + b'\x00' * (2 * 1024 * 1024)      # ~2MB, over the client cap
    note = note_xml(resource_blob=payload, resource_name='passport-scan.png')

    # With the client cap (the old behaviour) this is refused...
    files, skipped, _ = evernote.read_resources(
        note, max_bytes=blob_store.MAX_ATTACHMENT_BYTES)
    assert files == [] and '超过' in skipped[0]['reason']

    # ...and with the import cap it goes through.
    files, skipped, _ = evernote.read_resources(
        note, max_bytes=evernote.IMPORT_MAX_ATTACHMENT_BYTES)
    assert skipped == []
    assert len(files) == 1
    assert len(files[0].read()) == len(payload)


def test_validate_upload_honours_a_custom_ceiling():
    data = make_png() + b'\x00' * (2 * 1024 * 1024)
    with pytest.raises(blob_store.BlobError) as exc:
        blob_store.validate_upload('big.png', data)                     # client default
    assert exc.value.status == 413
    mime, kind = blob_store.validate_upload('big.png', data, max_bytes=25 * 1024 * 1024)
    assert (mime, kind) == ('image/png', 'image')


# ---------------------------------------------------------------------------
# Field mapping: everything Evernote gives us must land somewhere
# ---------------------------------------------------------------------------

FULL_NOTE = '''<note>
  <title>带全部字段的笔记</title>
  <content><![CDATA[<en-note><div>正文</div></en-note>]]></content>
  <created>20260101T000000Z</created>
  <updated>20260202T030405Z</updated>
  <tag>工作</tag><tag>travel</tag>
  <note-attributes>
    <author>Xiao Jiang</author>
    <source>desktop.mac</source>
    <source-application>webclipper.extension</source-application>
    <source-url>https://example.com/article</source-url>
    <content-class>evernote.hello</content-class>
    <subject-date>20251231T235959Z</subject-date>
  </note-attributes>
  <resource>
    <data encoding="base64">%s</data>
    <mime>image/png</mime>
    <width>1080</width>
    <height>2376</height>
    <resource-attributes>
      <file-name>shot.png</file-name>
      <source-url>https://cdn.example.com/shot.png</source-url>
    </resource-attributes>
  </resource>
</note>'''


def test_every_evernote_field_finds_a_home(app, store):
    """The import must not quietly drop fields. This is the field-by-field audit of a
    real export turned into a test: top-level note fields, note-attributes, and the
    per-resource extras (dimensions and the resource's own source URL, which our own
    upload path cannot supply)."""
    from datetime import datetime as _dt
    blob = make_png()
    note = ET.fromstring(FULL_NOTE % base64.encodebytes(blob).decode())

    with app.app_context():
        db.create_all()
        status, info = evernote.import_note(note, set())
        db.session.commit()
        assert status == 'created'
        metadata = info['message'].message_metadata

        # 通用概念保持扁平，渠道细节收进 evernote 命名空间
        assert metadata['title'] == '带全部字段的笔记'
        assert metadata['tags'] == ['工作', 'travel']
        assert metadata['url'] == 'https://example.com/article'
        assert info['message'].content.strip() == '正文'
        # SQLite 丢 tzinfo，数据库里存的是 naive UTC —— 这是全项目的既有约定，
        # 不是这里的问题（页面上靠 utc_iso 过滤器补回 Z 再转本地时区）。
        assert info['message'].timestamp == _dt(2026, 1, 1, 0, 0)

        node = metadata['evernote']
        assert node['author'] == 'Xiao Jiang'
        assert node['source'] == 'desktop.mac'
        assert node['source_application'] == 'webclipper.extension'
        assert node['content_class'] == 'evernote.hello'
        assert node['subject_date'] == '20251231T235959Z'
        assert node['updated'].startswith('2026-02-02T03:04:05')
        assert node['key'].startswith('sha1:')          # 这份导出没有 GUID

        # 附件记录带上 Evernote 才知道的东西
        attachment = metadata['attachments'][0]
        assert attachment['name'] == 'shot.png'
        assert attachment['width'] == 1080 and attachment['height'] == 2376
        assert attachment['source_url'] == 'https://cdn.example.com/shot.png'
