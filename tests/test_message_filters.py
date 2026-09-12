"""Filters, recipient matching and the delete paths.

These cover the guarantee the UI depends on: the rows a filter preview shows are
exactly the rows a filtered delete removes.
"""
from datetime import datetime, timedelta

import pytest

from models import db, Message

BASE = datetime(2026, 9, 1, 12, 0, 0)  # naive UTC, matching how SQLite stores it

ROWS = [
    # (type, device, days_before_base, recipients)
    ('PUSH_NOTIFICATION', 'android-phone-1', 0, None),
    ('PUSH_NOTIFICATION', 'android-phone-1', 1, None),
    ('SMS', 'android-phone-1', 2, None),
    ('EMAIL', 'mail-main', 3, ['jxitc@hotmail.com']),
    ('EMAIL', 'mail-main', 4, ['jiangxjx@gmail.com']),
    ('EMAIL', 'mail-main', 9, ['jxitc@hotmail.com', 'alias@jxitc.com']),
]


@pytest.fixture
def seeded(app):
    with app.app_context():
        for i, (mtype, device, days, recipients) in enumerate(ROWS):
            metadata = {'subject': 'row %d' % i}
            if recipients:
                metadata['recipients'] = recipients
            db.session.add(Message(
                id='mh-test-%04d' % i,
                source_device_id=device,
                type=mtype,
                sender='sender-%d' % i,
                content='content %d' % i,
                timestamp=BASE - timedelta(days=days),
                received_at=BASE - timedelta(days=days),
                message_metadata=metadata,
            ))
        db.session.commit()
    return app


def count(app):
    with app.app_context():
        return db.session.query(Message).count()


# ---------------------------------------------------------------------------
# API filters
# ---------------------------------------------------------------------------

def test_recipient_filter_api(seeded, client, auth_headers):
    r = client.get('/api/v1/messages?recipient=jxitc@hotmail.com', headers=auth_headers)
    assert r.status_code == 200
    assert r.get_json()['total'] == 2


def test_recipient_filter_is_case_insensitive(seeded, client, auth_headers):
    r = client.get('/api/v1/messages?recipient=JXITC@Hotmail.COM', headers=auth_headers)
    assert r.get_json()['total'] == 2


def test_recipient_filter_matches_secondary_address(seeded, client, auth_headers):
    r = client.get('/api/v1/messages?recipient=alias@jxitc.com', headers=auth_headers)
    assert r.get_json()['total'] == 1


def test_recipient_filter_ignores_non_email(seeded, client, auth_headers):
    r = client.get('/api/v1/messages?recipient=sender-0', headers=auth_headers)
    assert r.get_json()['total'] == 0


def test_type_and_device_filters(seeded, client, auth_headers):
    assert client.get('/api/v1/messages?type=EMAIL', headers=auth_headers).get_json()['total'] == 3
    assert client.get('/api/v1/messages?device=mail-main', headers=auth_headers).get_json()['total'] == 3
    combined = client.get('/api/v1/messages?device=mail-main&type=EMAIL', headers=auth_headers)
    assert combined.get_json()['total'] == 3


def test_date_range_filters(seeded, client, auth_headers):
    # A bare date means the whole UTC day.
    same_day = client.get('/api/v1/messages?since=2026-09-01&until=2026-09-01',
                          headers=auth_headers)
    assert same_day.get_json()['total'] == 1

    # 09-01 12:00 is the newest row; a later instant excludes it.
    later = client.get('/api/v1/messages?since=2026-09-01T13:00:00Z', headers=auth_headers)
    assert later.get_json()['total'] == 0

    # `until` is an upper bound over everything older, so the whole-day bound on
    # 09-01 covers all six rows (the oldest is 08-23)...
    until = client.get('/api/v1/messages?until=2026-09-01', headers=auth_headers)
    assert until.get_json()['total'] == 6

    # ...and one day earlier drops exactly the 09-01 row.
    until_earlier = client.get('/api/v1/messages?until=2026-08-31', headers=auth_headers)
    assert until_earlier.get_json()['total'] == 5

    # Both bounds together select the single 08-30 row.
    window = client.get('/api/v1/messages?since=2026-08-30&until=2026-08-30',
                        headers=auth_headers)
    assert window.get_json()['total'] == 1

    combined = client.get(
        '/api/v1/messages?device=mail-main&since=2026-08-25', headers=auth_headers)
    assert combined.get_json()['total'] == 2


# ---------------------------------------------------------------------------
# Filtered delete
# ---------------------------------------------------------------------------

def test_delete_by_filter_defaults_to_dry_run(seeded, client, auth_headers):
    r = client.post('/api/v1/messages/delete', json={'device': 'mail-main'},
                    headers=auth_headers)
    assert r.status_code == 200
    body = r.get_json()
    assert body['dry_run'] is True
    assert body['would_delete'] == 3
    assert count(seeded) == 6


def test_delete_by_filter_is_exact(seeded, client, auth_headers):
    r = client.post('/api/v1/messages/delete',
                    json={'recipient': 'jxitc@hotmail.com', 'dry_run': False},
                    headers=auth_headers)
    assert r.get_json()['deleted'] == 2
    assert count(seeded) == 4

    # The other recipient's mail is untouched.
    left = client.get('/api/v1/messages?recipient=jiangxjx@gmail.com', headers=auth_headers)
    assert left.get_json()['total'] == 1


def test_delete_without_filters_is_refused(seeded, client, auth_headers):
    r = client.post('/api/v1/messages/delete', json={}, headers=auth_headers)
    assert r.status_code == 400
    assert count(seeded) == 6


def test_delete_by_ids(seeded, client, auth_headers):
    r = client.delete('/api/v1/messages', json={'ids': ['mh-test-0000']}, headers=auth_headers)
    assert r.get_json()['deleted'] == 1
    assert count(seeded) == 5

    missing = client.delete('/api/v1/messages', json={'ids': ['nope']}, headers=auth_headers)
    assert missing.get_json()['deleted'] == 0

    empty = client.delete('/api/v1/messages', json={'ids': []}, headers=auth_headers)
    assert empty.status_code == 400


# ---------------------------------------------------------------------------
# Web UI paths
# ---------------------------------------------------------------------------

def test_web_filter_page_renders(seeded, client):
    # rows 3 and 5 carry the hotmail address; all three EMAIL rows are mail-main
    r = client.get('/messages?type=EMAIL&recipient=jxitc@hotmail.com')
    assert r.status_code == 200
    body = r.get_data(as_text=True)
    assert 'Received at (To)' in body
    assert 'Select all on this page' in body
    assert 'Delete all 2 matching' in body
    assert 'to jxitc@hotmail.com' in body
    assert 'filtered: type=EMAIL, to=jxitc@hotmail.com' in body


def test_web_delete_selected(seeded, client):
    r = client.post('/messages/delete', data={'ids': ['mh-test-0001']},
                    follow_redirects=True)
    assert r.status_code == 200
    assert count(seeded) == 5


def test_web_delete_filtered_needs_confirm(seeded, client):
    r = client.post('/messages/delete-filtered', data={'type': 'SMS'},
                    follow_redirects=True)
    assert 'confirmation was not provided' in r.get_data(as_text=True)
    assert count(seeded) == 6


def test_web_delete_filtered_refuses_without_filters(seeded, client):
    r = client.post('/messages/delete-filtered', data={'confirm': 'yes'},
                    follow_redirects=True)
    assert 'Refusing to delete' in r.get_data(as_text=True)
    assert count(seeded) == 6


def test_web_delete_filtered_matches_preview_total(seeded, client, auth_headers):
    preview = client.get('/messages?device=mail-main&type=EMAIL')
    total = client.get('/api/v1/messages?device=mail-main&type=EMAIL',
                       headers=auth_headers).get_json()['total']
    assert total == 3
    assert ('Delete all %d matching' % total) in preview.get_data(as_text=True)

    client.post('/messages/delete-filtered',
                data={'device': 'mail-main', 'type': 'EMAIL', 'confirm': 'yes'},
                follow_redirects=True)
    assert count(seeded) == 6 - total


def test_web_redirect_keeps_filters(seeded, client):
    r = client.post('/messages/delete',
                    data={'ids': ['mh-test-0000'], 'type': 'EMAIL',
                          'recipient': 'jxitc@hotmail.com'},
                    follow_redirects=False)
    assert r.status_code == 302
    assert 'type=EMAIL' in r.headers['Location']
    assert 'recipient=jxitc' in r.headers['Location']


# ---------------------------------------------------------------------------
# Parsing boundaries
# ---------------------------------------------------------------------------

def test_parse_instant_boundaries():
    import message_filters as mf

    assert mf.parse_instant('2026-09-01') == datetime(2026, 9, 1)
    assert mf.parse_instant('2026-09-01', end_of_day=True) == \
        datetime(2026, 9, 1, 23, 59, 59, 999999)
    # Offset-bearing instants come back as naive UTC (what SQLite compare needs).
    assert mf.parse_instant('2026-09-01T10:00:00+02:00') == datetime(2026, 9, 1, 8, 0, 0)
    assert mf.parse_instant('2026-09-01T10:00:00Z') == datetime(2026, 9, 1, 10, 0, 0)
    assert mf.parse_instant('not-a-date') is None
    assert mf.parse_instant('') is None
    assert mf.parse_instant(None) is None


def test_normalize_filters_strips_blanks():
    import message_filters as mf

    assert mf.normalize_filters('  ', '', None) == {
        'type': None, 'device': None, 'recipient': None, 'since': None, 'until': None}
    assert mf.normalize_filters('EMAIL', ' mail-main ', '', ' 2026-09-01 ')['type'] == 'EMAIL'
    assert mf.normalize_filters(device=' mail-main ')['device'] == 'mail-main'


def test_describe_filters_is_human_readable():
    import message_filters as mf

    summary = mf.describe_filters(mf.normalize_filters('EMAIL', 'mail-main',
                                                       'a@b.com', '2026-09-01', None))
    assert summary == 'type=EMAIL, device=mail-main, to=a@b.com, from=2026-09-01'


def test_apply_filter_dict_accepts_normalized_dict(seeded, app):
    """Regression: normalize_filters keys the type filter as 'type', which is not
    a valid kwarg — `apply_filters(query, **filters)` raised TypeError and the
    delete silently did nothing."""
    import message_filters as mf

    with app.app_context():
        filters = mf.normalize_filters('EMAIL')
        query = mf.apply_filter_dict(db.session.query(Message), filters)
        assert query.count() == 3


def test_list_recipients_deduplicates(seeded, app):
    import message_filters as mf

    with app.app_context():
        recipients = mf.list_recipients()
    assert recipients == ['alias@jxitc.com', 'jiangxjx@gmail.com', 'jxitc@hotmail.com']
