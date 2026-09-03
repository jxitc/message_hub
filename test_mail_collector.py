"""Unit tests for mail_collector.py.

Run from the message_hub directory with the project venv:

    source venv/bin/activate && python test_mail_collector.py

Covers: RFC 822 parsing (plain / HTML / multipart / encoded headers / dates),
payload construction, config loading, dedup + full collect flow against a
mocked IMAP server, and MH POST integration (against a live server when one
is reachable).
"""

import json
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mail_collector as mc


TEXT_ONLY_MAIL = b"""\
From: Alice Example <alice@example.com>
To: bob@example.com
Subject: Hello from Alice
Date: Mon, 31 Aug 2026 08:30:00 +0800
Message-ID: <text-1@example.com>
MIME-Version: 1.0
Content-Type: text/plain; charset=utf-8

Hi Bob,

Just checking in. See you tomorrow!
"""

HTML_ONLY_MAIL = b"""\
From: "News <noreply>" <news@example.com>
To: bob@example.com
Subject: Weekly Newsletter
Date: Tue, 01 Sep 2026 02:00:00 +0000
Message-ID: <html-1@example.com>
MIME-Version: 1.0
Content-Type: text/html; charset=utf-8

<html><body><h1>Big Sale!</h1><p>Save <b>50%</b> now.</p>
<script>alert('xss')</script><style>.x{}</style>
<p>Second paragraph.</p></body></html>
"""

MULTIPART_MAIL = """\
From: =?UTF-8?B?5byg5LiJ?= <zhang.san@qq.com>
To: bob@example.com
Subject: =?UTF-8?B?5Lya6K6u6YKA6K+3?=
Date: Wed, 02 Sep 2026 10:15:00 +0800
Message-ID: <mp-1@qq.com>
MIME-Version: 1.0
Content-Type: multipart/alternative; boundary="BOUNDARY"

--BOUNDARY
Content-Type: text/plain; charset=utf-8

纯文本正文部分
--BOUNDARY
Content-Type: text/html; charset=utf-8

<html><body><p>HTML <b>version</b></p></body></html>
--BOUNDARY--
""".encode('utf-8')

ENCODED_SUBJECT_MAIL = """\
From: John Doe <john@example.com>
To: bob@example.com
Subject: =?UTF-8?B?SGVsbG8g8J+YgA==?=
Date: Thu, 03 Sep 2026 12:00:00 +0000
Message-ID: <enc-1@example.com>
Content-Type: text/plain; charset=utf-8

body
""".encode('utf-8')


class TestParsing(unittest.TestCase):

    def test_parse_text_plain(self):
        p = mc.parse_message(TEXT_ONLY_MAIL)
        self.assertEqual(p['sender'], 'alice@example.com')
        self.assertEqual(p['subject'], 'Hello from Alice')
        self.assertEqual(p['message_id'], 'text-1@example.com')
        self.assertIn('Just checking in', p['body'])
        self.assertNotIn('<', p['body'])
        # Date 08:30 +0800 == 00:30 UTC
        self.assertEqual(p['date_iso'], '2026-08-31T00:30:00Z')
        self.assertEqual(p['date_raw'], 'Mon, 31 Aug 2026 08:30:00 +0800')

    def test_parse_html_only_cleans_tags(self):
        p = mc.parse_message(HTML_ONLY_MAIL)
        self.assertEqual(p['sender'], 'news@example.com')
        self.assertEqual(p['subject'], 'Weekly Newsletter')
        body = p['body']
        self.assertIn('Big Sale!', body)
        self.assertIn('Save 50% now.', body)
        self.assertIn('Second paragraph.', body)
        self.assertNotIn('<b>', body)
        self.assertNotIn('alert(', body)      # <script> content dropped
        self.assertNotIn('.x{', body)         # <style> content dropped
        self.assertEqual(p['date_iso'], '2026-09-01T02:00:00Z')

    def test_multipart_alternative_prefers_plain(self):
        p = mc.parse_message(MULTIPART_MAIL)
        self.assertEqual(p['sender'], 'zhang.san@qq.com')
        self.assertEqual(p['subject'], '会议邀请')  # RFC 2047 decoded
        self.assertIn('纯文本正文部分', p['body'])
        self.assertNotIn('HTML', p['body'])
        self.assertEqual(p['date_iso'], '2026-09-02T02:15:00Z')

    def test_encoded_subject_and_no_tz_date(self):
        p = mc.parse_message(ENCODED_SUBJECT_MAIL)
        self.assertEqual(p['subject'], 'Hello 😀')
        self.assertEqual(p['date_iso'], '2026-09-03T12:00:00Z')

    def test_build_payload(self):
        p = mc.parse_message(TEXT_ONLY_MAIL)
        payload = mc.build_payload(p, 'gmail')
        self.assertEqual(payload['type'], 'EMAIL')
        self.assertEqual(payload['source_device_id'], 'mail-gmail')
        self.assertEqual(payload['sender'], 'alice@example.com')
        self.assertEqual(payload['timestamp'], '2026-08-31T00:30:00Z')
        self.assertEqual(payload['metadata']['mailbox'], 'gmail')
        self.assertEqual(payload['metadata']['message_id'], 'text-1@example.com')
        self.assertEqual(payload['metadata']['subject'], 'Hello from Alice')
        content = payload['content']
        self.assertIn('Subject: Hello from Alice', content)
        self.assertIn('From: alice@example.com', content)
        self.assertIn('Date: Mon, 31 Aug 2026 08:30:00 +0800', content)
        self.assertIn('Just checking in', content)

    def test_device_id_sanitized(self):
        p = mc.parse_message(TEXT_ONLY_MAIL)
        payload = mc.build_payload(p, 'My Gmail!')
        self.assertEqual(payload['source_device_id'], 'mail-My-Gmail-')


class TestConfig(unittest.TestCase):

    def test_load_accounts_json(self):
        env = {'MAIL_ACCOUNTS': json.dumps([
            {'host': 'imap.gmail.com', 'user': 'a@gmail.com',
             'password': 'pw', 'port': 993, 'use_ssl': True,
             'folder': 'INBOX', 'label': 'gmail'},
            {'host': 'imap.qq.com', 'user': 'b@qq.com', 'password': 'code'},
        ])}
        accounts = mc.load_accounts(env)
        self.assertEqual(len(accounts), 2)
        self.assertEqual(accounts[1]['port'], 993)          # default
        self.assertEqual(accounts[1]['use_ssl'], True)      # default
        self.assertEqual(accounts[1]['folder'], 'INBOX')    # default
        self.assertEqual(accounts[1]['label'], 'b@qq.com')  # default = user

    def test_load_accounts_per_account_env(self):
        env = {
            'MAIL_0_HOST': 'imap.gmail.com',
            'MAIL_0_USER': 'a@gmail.com',
            'MAIL_0_PASSWORD': 'pw',
            'MAIL_0_LABEL': 'gmail',
            'MAIL_1_HOST': 'imap-mail.outlook.com',
            'MAIL_1_USER': 'b@outlook.com',
            'MAIL_1_PASSWORD': 'pw2',
        }
        accounts = mc.load_accounts(env)
        self.assertEqual(len(accounts), 2)
        self.assertEqual(accounts[0]['label'], 'gmail')
        self.assertEqual(accounts[1]['host'], 'imap-mail.outlook.com')

    def test_missing_required_field_raises(self):
        with self.assertRaises(ValueError):
            mc.normalize_account({'host': 'imap.gmail.com', 'user': 'x'})


class FakeIMAP:
    """Minimal stand-in for imaplib.IMAP4_SSL.

    mails: list of dicts {uid, raw, seen}
    store_noop: if True, STORE does not flip the seen flag (tests dedup path).
    """

    def __init__(self, mails, store_noop=False):
        self.mails = {m['uid']: m for m in mails}
        self.store_noop = store_noop
        self.login_called = None
        self.select_called = None
        self.logged_out = False

    def login(self, user, password):
        self.login_called = (user, password)
        return ('OK', [b'LOGIN completed'])

    def select(self, folder):
        self.select_called = folder
        return ('OK', [b'2'])

    def uid(self, cmd, *args):
        cmd = cmd.upper()
        if cmd == 'SEARCH':
            uids = [u for u, m in self.mails.items() if not m['seen']]
            return ('OK', [' '.join(uids).encode()])
        if cmd == 'FETCH':
            uid = args[0]
            raw = self.mails[uid]['raw']
            return ('OK', [(b'1 (UID %s RFC822 {0}' % uid.encode(), raw)])
        if cmd == 'STORE':
            if not self.store_noop:
                self.mails[args[0]]['seen'] = True
            return ('OK', [b'1'])
        raise AssertionError('unexpected UID command: %s' % cmd)

    def logout(self):
        self.logged_out = True
        return ('OK', [b'BYE'])


class TestCollectFlow(unittest.TestCase):

    def _two_unseen(self):
        return [
            {'uid': '101', 'raw': TEXT_ONLY_MAIL, 'seen': False},
            {'uid': '102', 'raw': MULTIPART_MAIL, 'seen': False},
        ]

    def test_full_flow_imports_and_marks_seen(self):
        fake = FakeIMAP(self._two_unseen())
        state = {}
        with mock.patch('mail_collector.imaplib.IMAP4_SSL', return_value=fake), \
             mock.patch('mail_collector.requests.post') as post:
            post.return_value = mock.Mock(
                status_code=201, text='{"id":"abc"}')
            stats = mc.process_mailbox(
                {'host': 'imap.gmail.com', 'user': 'a@gmail.com',
                 'password': 'pw', 'port': 993, 'use_ssl': True,
                 'folder': 'INBOX', 'label': 'gmail'},
                state, 'http://127.0.0.1:5001')

        self.assertEqual(stats['imported'], 2)
        self.assertEqual(stats['failed'], 0)
        self.assertEqual(stats['skipped'], 0)
        self.assertEqual(fake.login_called, ('a@gmail.com', 'pw'))
        self.assertEqual(fake.select_called, 'INBOX')
        self.assertTrue(fake.logged_out)
        self.assertEqual(post.call_count, 2)
        # both mails marked seen on the server
        self.assertTrue(all(m['seen'] for m in fake.mails.values()))
        # dedup state recorded under both message-id and uid keys
        self.assertIn('text-1@example.com', state['gmail'])
        self.assertIn('uid:101', state['gmail'])
        # payload shape posted to MH
        payload = post.call_args_list[0][1]['json']
        self.assertEqual(payload['type'], 'EMAIL')
        self.assertEqual(payload['source_device_id'], 'mail-gmail')
        self.assertEqual(payload['metadata']['mailbox'], 'gmail')

    def test_dedup_skips_already_processed(self):
        # store_noop=True simulates a server where \Seen marking silently fails:
        # the mail stays UNSEEN, but the local state file still prevents dupes.
        fake = FakeIMAP(self._two_unseen(), store_noop=True)
        state = {'gmail': {'text-1@example.com': 1234.0, 'uid:101': 1234.0}}
        with mock.patch('mail_collector.imaplib.IMAP4_SSL', return_value=fake), \
             mock.patch('mail_collector.requests.post') as post:
            post.return_value = mock.Mock(status_code=201, text='{}')
            stats = mc.process_mailbox(
                {'host': 'imap.gmail.com', 'user': 'a@gmail.com',
                 'password': 'pw', 'port': 993, 'use_ssl': True,
                 'folder': 'INBOX', 'label': 'gmail'},
                state, 'http://127.0.0.1:5001')

        self.assertEqual(stats['imported'], 1)   # only uid:102 (new)
        self.assertEqual(stats['skipped'], 1)    # uid:101 already processed
        self.assertEqual(stats['failed'], 0)
        self.assertEqual(post.call_count, 1)

    def test_failed_post_does_not_record_state_or_mark_seen(self):
        fake = FakeIMAP(self._two_unseen())
        state = {}
        with mock.patch('mail_collector.imaplib.IMAP4_SSL', return_value=fake), \
             mock.patch('mail_collector.requests.post') as post:
            post.return_value = mock.Mock(status_code=500, text='boom')
            stats = mc.process_mailbox(
                {'host': 'imap.gmail.com', 'user': 'a@gmail.com',
                 'password': 'pw', 'port': 993, 'use_ssl': True,
                 'folder': 'INBOX', 'label': 'gmail'},
                state, 'http://127.0.0.1:5001')

        self.assertEqual(stats['imported'], 0)
        self.assertEqual(stats['failed'], 2)
        self.assertEqual(state, {})                       # nothing recorded
        self.assertFalse(any(m['seen'] for m in fake.mails.values()))  # retry next time

    def test_dry_run_posts_nothing_and_touches_nothing(self):
        fake = FakeIMAP(self._two_unseen())
        state = {}
        with mock.patch('mail_collector.imaplib.IMAP4_SSL', return_value=fake), \
             mock.patch('mail_collector.requests.post') as post:
            stats = mc.process_mailbox(
                {'host': 'imap.gmail.com', 'user': 'a@gmail.com',
                 'password': 'pw', 'port': 993, 'use_ssl': True,
                 'folder': 'INBOX', 'label': 'gmail'},
                state, 'http://127.0.0.1:5001', dry_run=True)

        self.assertEqual(stats['imported'], 2)
        post.assert_not_called()
        self.assertEqual(state, {})
        self.assertFalse(any(m['seen'] for m in fake.mails.values()))

    def test_run_once_saves_state_file(self):
        fake = FakeIMAP(self._two_unseen())
        with tempfile.TemporaryDirectory() as tmpdir:
            with mock.patch('mail_collector.imaplib.IMAP4_SSL', return_value=fake), \
                 mock.patch('mail_collector.requests.post') as post:
                post.return_value = mock.Mock(status_code=201, text='{}')
                stats = mc.run_once(
                    state_dir=tmpdir,
                    accounts=[{'host': 'imap.gmail.com', 'user': 'a@gmail.com',
                               'password': 'pw', 'port': 993, 'use_ssl': True,
                               'folder': 'INBOX', 'label': 'gmail'}])

            self.assertEqual(stats['gmail']['imported'], 2)
            state_path = os.path.join(tmpdir, 'processed_mails.json')
            self.assertTrue(os.path.exists(state_path))
            with open(state_path) as fh:
                saved = json.load(fh)
            self.assertIn('text-1@example.com', saved['gmail'])

    def test_report_message_ok_and_failure(self):
        with mock.patch('mail_collector.requests.post') as post:
            post.return_value = mock.Mock(status_code=201, text='{"id":"x"}')
            ok, _ = mc.report_message({'a': 1})
            self.assertTrue(ok)

            post.return_value = mock.Mock(status_code=400, text='bad')
            ok, detail = mc.report_message({'a': 1})
            self.assertFalse(ok)
            self.assertIn('HTTP 400', detail)

        with mock.patch('mail_collector.requests.post',
                        side_effect=mc.requests.exceptions.ConnectionError(
                            'connection refused')):
            ok, detail = mc.report_message({'a': 1})
            self.assertFalse(ok)
            self.assertIn('connection refused', detail)


class TestLiveMH(unittest.TestCase):
    """Optional: POST one EMAIL to a running Message Hub (http://127.0.0.1:5001).
    Skips silently when no server is reachable."""

    MH_URL = os.environ.get('MH_URL', 'http://127.0.0.1:5001')

    def _reachable(self):
        try:
            resp = mc.requests.get(self.MH_URL + '/health', timeout=3)
            return resp.status_code == 200
        except Exception:
            return False

    def test_post_email_to_live_mh(self):
        if not self._reachable():
            self.skipTest('Message Hub not reachable at %s' % self.MH_URL)
        p = mc.parse_message(TEXT_ONLY_MAIL)
        payload = mc.build_payload(p, 'test-mailbox')
        ok, detail = mc.report_message(payload, self.MH_URL)
        self.assertTrue(ok, 'POST failed: %s' % detail)
        # and it is queryable back
        resp = mc.requests.get(
            self.MH_URL + '/api/v1/messages',
            params={'type': 'EMAIL', 'device': 'mail-test-mailbox'}, timeout=5)
        self.assertEqual(resp.status_code, 200)
        items = resp.json().get('messages', [])
        self.assertTrue(any(m['metadata'].get('message_id') == 'text-1@example.com'
                            for m in items))


if __name__ == '__main__':
    unittest.main(verbosity=2)
