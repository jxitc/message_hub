"""Message content schema: clean content + structured metadata round-trip."""


def test_post_clean_content_and_metadata(client, auth_headers):
    payload = {
        'source_device_id': 'test-device-1',
        'type': 'SMS',
        'sender': '+15550001111',
        'content': 'hello world',
        'timestamp': '2026-09-03T00:00:00Z',
        'metadata': {
            'source': 'phone',
            'phone_number': '+15550001111',
            'contact_name': 'Alice',
            'message_id': 'msg-1',
        },
    }
    r = client.post('/api/v1/messages', json=payload, headers=auth_headers)
    assert r.status_code == 201, r.get_json()
    body = r.get_json()['data']
    assert body['content'] == 'hello world'
    assert body['metadata'] == payload['metadata']
    assert body['sender'] == '+15550001111'

    # GET returns the message, still clean
    g = client.get('/api/v1/messages', headers=auth_headers)
    assert g.status_code == 200
    msgs = g.get_json()['messages']
    assert any(m['content'] == 'hello world' for m in msgs)


def test_content_has_no_emoji_or_labels(client, auth_headers):
    # content must not contain the old emoji/prefix formatting
    payload = {
        'source_device_id': 'test-device-2',
        'type': 'PUSH_NOTIFICATION',
        'sender': '微信',
        'content': 'WeChat\nhello',
        'timestamp': '2026-09-03T00:01:00Z',
        'metadata': {'source': 'app', 'app_name': '微信', 'title': 'WeChat'},
    }
    r = client.post('/api/v1/messages', json=payload, headers=auth_headers)
    assert r.status_code == 201
    content = r.get_json()['data']['content']
    assert content == 'WeChat\nhello'
    for bad in ('\U0001F514', '\U0001F4F1', 'SMS Message', 'Notification', 'From:', 'Received:'):
        assert bad not in content
