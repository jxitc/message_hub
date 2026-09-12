"""The migrate.py data migration that removes duplicated recipient keys.

`recipients` is the single machine-readable source for who a mail was addressed
to; the raw to/cc/delivered_to/original_to copies used to sit beside it.
"""
from datetime import datetime

from migrate import strip_redundant_recipient_keys
from models import db, Message


def seed(app, **metadata):
    with app.app_context():
        db.session.add(Message(
            id='mh-migrate-1',
            source_device_id='mail-main',
            type='EMAIL',
            sender='someone@example.com',
            content='Subject: hi\nFrom: someone@example.com\nTo: X <a@b.com>\nDate: now\n\nbody',
            timestamp=datetime(2026, 9, 1, 12, 0, 0),
            received_at=datetime(2026, 9, 1, 12, 0, 0),
            message_metadata=metadata,
        ))
        db.session.commit()


def test_strips_only_the_duplicated_keys(app):
    seed(app,
         mailbox='main',
         message_id='abc@example.com',
         subject='hi',
         to='X <a@b.com>',
         cc='Y <c@d.com>',
         delivered_to='me@gmail.com',
         original_to='real@example.com',
         recipients=['a@b.com', 'c@d.com'])
    with app.app_context():
        engine = db.engine

    assert strip_redundant_recipient_keys(engine) == 1

    with app.app_context():
        message = db.session.query(Message).filter(Message.id == 'mh-migrate-1').one()
        assert message.message_metadata == {
            'mailbox': 'main',
            'message_id': 'abc@example.com',
            'subject': 'hi',
            'recipients': ['a@b.com', 'c@d.com'],
        }
        # The readable To line in content is untouched — that is the provenance.
        assert 'To: X <a@b.com>' in message.content


def test_is_idempotent(app):
    seed(app, mailbox='main', recipients=['a@b.com'])
    with app.app_context():
        engine = db.engine
    # Nothing to strip: no redundant keys present.
    assert strip_redundant_recipient_keys(engine) == 0


def test_leaves_other_channels_alone(app):
    with app.app_context():
        db.session.add(Message(
            id='mh-migrate-sms',
            source_device_id='android-phone-1',
            type='SMS',
            sender='+15550001111',
            content='hello',
            timestamp=datetime(2026, 9, 1, 12, 0, 0),
            received_at=datetime(2026, 9, 1, 12, 0, 0),
            # `to` here is not a duplicate of anything; SMS has its own keys.
            message_metadata={'phone_number': '+15550001111', 'to': 'kept'},
        ))
        db.session.commit()
        engine = db.engine

    assert strip_redundant_recipient_keys(engine) == 0

    with app.app_context():
        message = db.session.query(Message).filter(Message.id == 'mh-migrate-sms').one()
        assert message.message_metadata == {'phone_number': '+15550001111', 'to': 'kept'}
