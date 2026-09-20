"""Idempotent ingest — the hub must not store the same event twice.

Context for why this exists: on 2026-09-20 a phone client retried its backlog and
produced 15,745 rows for ~991 real events in a few minutes, because every POST
invented a fresh uuid and nothing ever compared payloads. The database accepted
all of it without complaint. These tests pin the comparison rule, the behaviour of
each front door, and the cleanup that shares the rule.
"""

import importlib.util
import io
import json
import os
from datetime import datetime, timezone
from pathlib import Path

import pytest

import message_identity as ident
from models import db, Message

_SPEC = importlib.util.spec_from_file_location(
    'dedup_messages',
    os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                 'scripts', 'dedup-messages.py'))
dedup = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(dedup)


NOTIFICATION = {
    'source_device_id': 'phone-1',
    'type': 'PUSH_NOTIFICATION',
    'sender': '微信',
    'content': 'WeChat\n1个联系人发来3条消息',
    'timestamp': '2026-09-19T19:03:08.399000Z',
    'metadata': {
        'package_name': 'com.tencent.mm',
        'app_name': '微信',
        'notification_id': '44579564',
        'timestamp': '1758308588399',
    },
}


def post(client, headers, payload):
    return client.post('/api/v1/messages', json=payload, headers=headers)


def variant(**overrides):
    """A deep copy of NOTIFICATION with fields overridden."""
    copy = json.loads(json.dumps(NOTIFICATION))
    for path, value in overrides.items():
        parts = path.split('.')
        target = copy
        for part in parts[:-1]:
            target = target[part]
        target[parts[-1]] = value
    return copy


# ---------------------------------------------------------------------------
# The key itself
# ---------------------------------------------------------------------------

class TestNaturalKey:
    def test_notification_identity_is_package_id_and_instant(self):
        key = ident.natural_key(
            message_type='PUSH_NOTIFICATION',
            metadata={'package_name': 'com.tencent.mm', 'notification_id': '7'},
            sender='微信', timestamp='2026-09-19T19:03:08.399000Z', content='x')
        assert key == 'v1|ntf|com.tencent.mm|7|2026-09-19T19:03:08.399000+00:00'

    def test_datetime_and_iso_string_produce_the_same_key(self):
        """Ingest sees a parsed datetime; the backfill sees what SQLite returned.

        If those disagree, the re-upload check misses exactly the rows the cleanup
        keyed — so this equivalence is the load-bearing part of the design.
        """
        args = dict(
            message_type='PUSH_NOTIFICATION',
            metadata={'package_name': 'p', 'notification_id': '1'},
            sender='s', content='x')
        as_text = ident.natural_key(timestamp='2026-09-19T19:03:08.399000Z', **args)
        as_dt = ident.natural_key(
            timestamp=datetime(2026, 9, 19, 19, 3, 8, 399000, tzinfo=timezone.utc),
            **args)
        # naive = what SQLite hands back (it does not persist the offset)
        naive = ident.natural_key(
            timestamp=datetime(2026, 9, 19, 19, 3, 8, 399000), **args)
        assert as_text == as_dt == naive

    def test_channel_message_id_wins_over_the_text_fallback(self):
        key = ident.natural_key(
            message_type='SMS', metadata={'message_id': 'abc123'},
            sender='10086', timestamp='2026-09-19T19:03:08Z', content='余额')
        assert key == 'v1|id|SMS|abc123'

    def test_sms_without_a_channel_id_falls_back_to_the_text(self):
        args = dict(message_type='SMS', metadata={}, sender='10086',
                    timestamp='2026-09-19T19:03:08Z')
        first = ident.natural_key(content='余额 10 元', **args)
        again = ident.natural_key(content='余额 10 元', **args)
        other = ident.natural_key(content='余额 20 元', **args)
        assert first == again and first != other
        assert first.startswith('v1|txt|SMS|10086|')

    def test_manual_notes_and_documents_have_no_key(self):
        for message_type in ('NOTE', 'DOCUMENT'):
            assert ident.natural_key(
                message_type=message_type, metadata={'title': 't'},
                sender='web', timestamp='2026-09-19T19:03:08Z',
                content='一模一样的两条笔记') is None

    def test_incomplete_notifications_are_not_keyed(self):
        """A key built from one field would over-merge; missing data means no key."""
        assert ident.natural_key(
            message_type='PUSH_NOTIFICATION', metadata={'package_name': 'p'},
            sender='s', timestamp='2026-09-19T19:03:08Z', content='x') is None
        assert ident.natural_key(
            message_type='PUSH_NOTIFICATION',
            metadata={'package_name': 'p', 'notification_id': '1'},
            sender='s', timestamp=None, content='x') is None

    def test_a_separator_inside_a_component_cannot_forge_a_different_key(self):
        a = ident.natural_key(
            message_type='PUSH_NOTIFICATION',
            metadata={'package_name': 'a|b', 'notification_id': 'c'},
            sender='s', timestamp='2026-09-19T19:03:08Z', content='x')
        b = ident.natural_key(
            message_type='PUSH_NOTIFICATION',
            metadata={'package_name': 'a', 'notification_id': 'b|c'},
            sender='s', timestamp='2026-09-19T19:03:08Z', content='x')
        assert a != b

    def test_a_key_longer_than_the_column_is_hashed_not_truncated(self):
        long_a = ident.natural_key(
            message_type='SMS', metadata={'message_id': 'x' * 400 + 'A'},
            sender='s', timestamp='2026-09-19T19:03:08Z', content='c')
        long_b = ident.natural_key(
            message_type='SMS', metadata={'message_id': 'x' * 400 + 'B'},
            sender='s', timestamp='2026-09-19T19:03:08Z', content='c')
        assert len(long_a) <= ident.MAX_KEY_CHARS + 5
        assert long_a != long_b


# ---------------------------------------------------------------------------
# The front door
# ---------------------------------------------------------------------------

class TestIngestIsIdempotent:
    def test_the_same_notification_posted_twice_creates_one_row(
            self, client, auth_headers, app):
        first = post(client, auth_headers, NOTIFICATION)
        second = post(client, auth_headers, NOTIFICATION)

        assert first.status_code == 201
        # 200, not 201 and not 409: a retrying client must read this as success,
        # which is what makes the phone's retry loop finally stop.
        assert second.status_code == 200
        assert second.get_json()['duplicate'] is True
        assert second.get_json()['id'] == first.get_json()['id']

        with app.app_context():
            assert db.session.query(Message).count() == 1

    def test_a_burst_of_retries_collapses_to_one_row(self, client, auth_headers, app):
        """The flood in miniature: 50 identical POSTs, one stored event."""
        codes = [post(client, auth_headers, NOTIFICATION).status_code
                 for _ in range(50)]
        assert codes[0] == 201 and set(codes[1:]) == {200}
        with app.app_context():
            assert db.session.query(Message).count() == 1

    def test_different_events_are_still_separate_rows(self, client, auth_headers, app):
        post(client, auth_headers, NOTIFICATION)
        other = variant(**{'metadata.notification_id': '99999999'})
        assert post(client, auth_headers, other).status_code == 201
        with app.app_context():
            assert db.session.query(Message).count() == 2

    def test_the_stored_row_keeps_its_natural_key(self, client, auth_headers, app):
        """The key is persisted, not recomputed on every read — the index use it."""
        post(client, auth_headers, NOTIFICATION)
        with app.app_context():
            row = db.session.query(Message).one()
            assert row.natural_key == (
                'v1|ntf|com.tencent.mm|44579564|2026-09-19T19:03:08.399000+00:00')

    def test_manual_notes_are_never_deduplicated(self, client, auth_headers, app):
        """Two identical notes are two things the user chose to keep."""
        note = {
            'source_device_id': 'web', 'type': 'NOTE', 'sender': 'web',
            'content': '买牛奶', 'timestamp': '2026-09-19T19:03:08Z',
            'metadata': {},
        }
        assert post(client, auth_headers, note).status_code == 201
        assert post(client, auth_headers, note).status_code == 201
        with app.app_context():
            assert db.session.query(Message).count() == 2

    def test_a_refused_duplicate_leaves_no_stored_blob(
            self, client, auth_headers, app, store):
        """The check runs before files are written, so a retry costs a SELECT."""
        form = {
            'type': 'DOCUMENT', 'timestamp': '2026-09-19T19:03:08Z',
            'source_device_id': 'web', 'sender': 'web', 'content': '看图',
            # a channel id, so this upload *is* keyed despite being a manual type
            'metadata': '{"message_id": "dedupe-blob-1"}',
        }

        def upload():
            return client.post(
                '/api/v1/messages',
                data=dict(form, attachments=(
                    io.BytesIO(b'\x89PNG\r\n\x1a\n' + b'0' * 64), 'a.png')),
                headers=auth_headers, content_type='multipart/form-data')

        assert upload().status_code == 201
        root = Path(store.root)
        before = sorted(p.name for p in root.rglob('*') if p.is_file())
        assert len(before) == 1

        second = upload()
        assert second.status_code == 200
        assert second.get_json()['duplicate'] is True
        after = sorted(p.name for p in root.rglob('*') if p.is_file())
        assert after == before, 'a refused duplicate must not leave a stored blob'

    def test_a_duplicate_carrying_more_text_replaces_the_degraded_copy(
            self, client, auth_headers, app):
        """The real case: a notification re-read after its title finally resolved.

        Keeping the first copy would keep "视频标题加载失败" and throw away the
        only copy that has any value.
        """
        degraded = variant(content='视频标题加载失败\n昵称加载失败')
        better = variant(content='12个负认知模型 #认知 #思维\n于澈')

        assert post(client, auth_headers, degraded).status_code == 201
        second = post(client, auth_headers, better)

        assert second.status_code == 200
        assert second.get_json()['duplicate'] is True
        with app.app_context():
            rows = db.session.query(Message).all()
            assert len(rows) == 1
            assert rows[0].content.startswith('12个负认知模型')

    def test_a_shorter_retry_does_not_clobber_a_longer_stored_copy(
            self, client, auth_headers, app):
        rich = variant(content='完整的标题\n和正文')
        poor = variant(content='加载失败')
        post(client, auth_headers, rich)
        second = post(client, auth_headers, poor)
        assert second.get_json()['duplicate'] is True
        with app.app_context():
            assert db.session.query(Message).one().content == '完整的标题\n和正文'

    def test_the_unique_index_is_the_real_guard(self, app):
        """Enforced by the database, not by a check-then-insert race."""
        from sqlalchemy.exc import IntegrityError
        with app.app_context():
            for _ in range(2):
                db.session.add(Message(
                    source_device_id='phone-1', type='SMS', sender='10086',
                    content='x', timestamp=datetime.utcnow(),
                    received_at=datetime.utcnow(),
                    message_metadata={}, natural_key='v1|id|SMS|same'))
            with pytest.raises(IntegrityError):
                db.session.commit()
            db.session.rollback()

    def test_many_unkeyed_rows_still_coexist(self, app):
        """NULL keys are distinct in SQL, which is what makes NOTE/DOCUMENT safe."""
        with app.app_context():
            for _ in range(5):
                db.session.add(Message(
                    source_device_id='web', type='NOTE', sender='web', content='n',
                    timestamp=datetime.utcnow(), received_at=datetime.utcnow(),
                    message_metadata={}, natural_key=None))
            db.session.commit()
            assert db.session.query(Message).count() == 5


# ---------------------------------------------------------------------------
# The cleanup that shares the rule
# ---------------------------------------------------------------------------

class TestCleanupPlan:
    def _row(self, content, second, key=None):
        row = Message(
            source_device_id='phone-1', type='PUSH_NOTIFICATION', sender='微信',
            content=content, timestamp=datetime(2026, 9, 20, tzinfo=timezone.utc),
            received_at=datetime(2026, 9, 20, 0, 0, second, tzinfo=timezone.utc),
            message_metadata={'package_name': 'com.tencent.mm',
                              'notification_id': '5',
                              'timestamp': '1758308588399'},
            natural_key=key)
        db.session.add(row)
        return row

    def test_survivor_is_the_richest_then_the_earliest(self, app):
        with app.app_context():
            poor_first = self._row('加载失败', 0)
            rich_later = self._row('真正的标题', 5)
            self._row('加载失败', 9)
            db.session.commit()
            group = [poor_first, rich_later] + [
                m for m in db.session.query(Message).all()
                if m not in (poor_first, rich_later)]
            assert dedup.pick_survivor(group) is rich_later

    def test_identical_copies_keep_the_first_one_received(self, app):
        with app.app_context():
            first = self._row('同样的话', 1)
            second = self._row('同样的话', 2)
            db.session.commit()
            assert dedup.pick_survivor([second, first]) is first

    def test_the_row_key_is_derived_from_the_stored_row(self, app):
        """So the cleanup can act as the backfill for rows that have no key yet."""
        with app.app_context():
            row = self._row('正文', 3)
            db.session.commit()
            assert dedup.key_for(row) == (
                'v1|ntf|com.tencent.mm|5|2026-09-20T00:00:00.000000+00:00')
            assert dedup.key_for(row) == ident.natural_key(
                message_type=row.type, metadata=row.message_metadata,
                sender=row.sender, timestamp=row.timestamp, content=row.content)

    def test_plan_splits_a_group_into_one_survivor_and_the_rest(self, app):
        with app.app_context():
            rows = [self._row('x', n) for n in range(6)]
            db.session.commit()
            groups = {dedup.key_for(rows[0]): rows}
            survivors, doomed = dedup.plan(groups)
            assert len(survivors) == 1 and len(doomed) == 5
            assert survivors[0] not in doomed

    def test_a_row_with_no_channel_identity_is_never_doomed(self, app):
        with app.app_context():
            row = Message(
                source_device_id='web', type='NOTE', sender='web', content='笔记',
                timestamp=datetime(2026, 9, 20, tzinfo=timezone.utc),
                received_at=datetime(2026, 9, 20, tzinfo=timezone.utc),
                message_metadata={})
            db.session.add(row)
            db.session.commit()
            assert dedup.key_for(row) is None

    def test_apply_survives_a_key_already_held_by_a_row_it_deletes(self, app):
        """The state right after a deploy: new uploads are keyed, old ones are not.

        A re-upload of a historical event inserts a row that already carries the
        key, while the flood rows it belongs with still have NULL. Assigning the
        survivor's key before deleting the doomed rows trips the unique index.
        """
        with app.app_context():
            key = 'v1|ntf|com.tencent.mm|5|2026-09-20T00:00:00.000000+00:00'
            # the newcomer: has the key, but the shortest text
            newcomer = self._row('新', 9, key=key)
            # the historical copy that should win: no key, much richer text
            historical = self._row('很长的正文，这才是真正的内容', 1)
            db.session.commit()

            groups, unkeyed, _ = dedup.collect_groups()
            assert groups[key] and len(groups[key]) == 2
            assert unkeyed == []
            survivors, doomed = dedup.plan(groups)
            assert survivors == [historical]

            deleted, keyed = dedup.apply_plan(groups, survivors, doomed)

            assert (deleted, keyed) == (1, 1)
            rows = db.session.query(Message).all()
            assert len(rows) == 1
            assert rows[0].id == historical.id
            assert rows[0].natural_key == key
            assert rows[0].content == '很长的正文，这才是真正的内容'
