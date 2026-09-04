"""API key auth: /api/v1/* requires X-API-Key; /health stays open."""


def test_health_open_without_key(client):
    r = client.get('/health')
    assert r.status_code == 200
    assert r.get_json()['status'] == 'healthy'


def test_api_requires_key(client):
    assert client.get('/api/v1/messages').status_code == 401
    assert client.get('/api/v1/sync/status').status_code == 401


def test_api_wrong_key_rejected(client):
    assert client.get('/api/v1/messages', headers={'X-API-Key': 'wrong'}).status_code == 401


def test_api_correct_key_allowed(client, auth_headers):
    assert client.get('/api/v1/messages', headers=auth_headers).status_code == 200


def test_post_requires_key(client):
    r = client.post('/api/v1/messages', json={
        'source_device_id': 'd1', 'type': 'SMS', 'sender': '+1',
        'content': 'hi', 'timestamp': '2026-01-01T00:00:00Z',
    })
    assert r.status_code == 401
