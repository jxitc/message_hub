#!/usr/bin/env python3
"""mail_collector.py — Universal IMAP email collector for Message Hub.

Fetches unread mail from one or more IMAP mailboxes (Gmail / QQ / Outlook / any
server speaking IMAP), parses each message into plain text (no attachments),
and POSTs it to Message Hub's ``POST /api/v1/messages`` endpoint with
``type=EMAIL``. Message Hub then acts as the pure collection layer; downstream
consumers (e.g. InfoAgent) read from Message Hub.

Standalone usage (no MH code touched):

    python mail_collector.py --once          # run a single cycle and exit (cron)
    python mail_collector.py --watch 300     # run forever, collect every 300 s
    python mail_collector.py --dry-run       # fetch + parse only, POST nothing

Configuration comes from environment variables (see .env.example):

    MAIL_ACCOUNTS   JSON array of IMAP accounts, e.g.
                    '[{"host":"imap.gmail.com","user":"you@gmail.com",
                       "password":"app-password","port":993,"use_ssl":true,
                       "folder":"INBOX","label":"gmail"}]'
                    (or, alternatively, per-account vars MAIL_0_HOST,
                    MAIL_0_USER, MAIL_0_PASSWORD, ...)
    MH_URL          Message Hub base URL, default http://127.0.0.1:5001
    MAIL_STATE_DIR  where processed_mails.json (dedup state) lives,
                    default ~/.message_hub

Deduplication: each successfully imported mail is recorded by Message-ID (and
UID) in ``processed_mails.json`` under ``~/.message_hub``; already processed
mails are skipped on later runs. A mail is only marked \\Seen on the server
after a successful POST, so a failed import is retried on the next cycle.

Dependencies: standard library (imaplib, email, html.parser) + ``requests``,
which is already in requirements.txt.
"""

import argparse
import email
import email.header
import email.utils
import html.parser
import imaplib
import json
import logging
import os
import re
import sys
import threading
import time
from datetime import datetime, timedelta, timezone

import requests

DEFAULT_MH_URL = 'http://127.0.0.1:5001'
DEFAULT_STATE_DIR = os.path.join(os.path.expanduser('~'), '.message_hub')
STATE_FILE_NAME = 'processed_mails.json'
STATE_RETENTION_DAYS = 90      # prune dedup entries older than this
STATE_MAX_PER_MAILBOX = 10000  # cap dedup entries per mailbox
REQUEST_TIMEOUT = (5, 30)      # (connect, read) seconds for MH POST

log = logging.getLogger('mail_collector')


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

def normalize_account(acc):
    """Validate one account dict and fill in defaults."""
    acc = dict(acc or {})
    for required in ('host', 'user', 'password'):
        if not acc.get(required):
            raise ValueError(
                'mail account missing required field "%s": %r' % (required, acc))
    acc.setdefault('port', 993)
    acc.setdefault('use_ssl', True)
    acc.setdefault('folder', 'INBOX')
    acc.setdefault('label', acc['user'])
    acc['port'] = int(acc['port'])
    if isinstance(acc['use_ssl'], str):
        acc['use_ssl'] = acc['use_ssl'].strip().lower() in ('1', 'true', 'yes', 'on')
    return acc


def load_accounts(env=None):
    """Load mailbox configs from env: MAIL_ACCOUNTS (JSON array) or the
    per-account MAIL_0_HOST / MAIL_0_USER / ... style."""
    env = env if env is not None else os.environ

    raw = (env.get('MAIL_ACCOUNTS') or '').strip()
    if raw:
        data = json.loads(raw)
        if not isinstance(data, list):
            raise ValueError('MAIL_ACCOUNTS must be a JSON array of account objects')
        return [normalize_account(a) for a in data if a]

    accounts = []
    i = 0
    while True:
        host = env.get('MAIL_%d_HOST' % i)
        user = env.get('MAIL_%d_USER' % i)
        if not (host or user):
            break
        acc = {'host': host or '', 'user': user or ''}
        for key in ('password', 'port', 'use_ssl', 'folder', 'label'):
            val = env.get('MAIL_%d_%s' % (i, key.upper()))
            if val is not None:
                acc[key] = val
        accounts.append(normalize_account(acc))
        i += 1
    return accounts


# ---------------------------------------------------------------------------
# Message parsing (RFC 822 -> plain text)
# ---------------------------------------------------------------------------

class _HTMLToText(html.parser.HTMLParser):
    """Minimal HTML -> text converter (stdlib only). Strips tags, ignores
    <script>/<style>, collapses whitespace, and keeps block-level breaks."""

    BLOCK_TAGS = {
        'p', 'div', 'br', 'li', 'tr', 'td', 'table', 'ul', 'ol', 'blockquote',
        'section', 'article', 'header', 'footer', 'pre', 'h1', 'h2', 'h3',
        'h4', 'h5', 'h6', 'hr',
    }

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self._parts = []
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style'):
            self._skip += 1
        elif tag in self.BLOCK_TAGS and self._parts and not self._parts[-1].endswith('\n'):
            self._parts.append('\n')

    def handle_endtag(self, tag):
        if tag in ('script', 'style') and self._skip > 0:
            self._skip -= 1
        elif tag in self.BLOCK_TAGS and self._parts and not self._parts[-1].endswith('\n'):
            self._parts.append('\n')

    def handle_data(self, data):
        if not self._skip:
            text = ' '.join(data.split())
            if text:
                if self._parts and not self._parts[-1].endswith((' ', '\n')):
                    self._parts.append(' ')  # keep spacing across inline tags
                self._parts.append(text)

    def result(self):
        lines = [ln.strip() for ln in ''.join(self._parts).split('\n')]
        return '\n'.join(ln for ln in lines if ln)


def html_to_text(html):
    """Convert an HTML string to readable plain text."""
    if not html:
        return ''
    parser = _HTMLToText()
    try:
        parser.feed(html)
        parser.close()
    except Exception:  # malformed HTML should never kill collection
        return re.sub(r'<[^>]+>', ' ', html)
    return parser.result()


def decode_mime_header(value):
    """Decode RFC 2047 encoded-words in a header value (Subject, From, ...)."""
    if not value:
        return ''
    out = []
    for raw, charset in email.header.decode_header(value):
        if isinstance(raw, bytes):
            try:
                out.append(raw.decode(charset or 'utf-8', errors='replace'))
            except LookupError:
                out.append(raw.decode('utf-8', errors='replace'))
        else:
            out.append(raw)
    return ' '.join(''.join(out).split())


def get_email_address(value):
    """Extract the bare address from a From-style header."""
    name, addr = email.utils.parseaddr(value or '')
    addr = (addr or '').strip()
    if addr:
        return addr
    match = re.search(r'<([^>]+)>', value or '')
    if match:
        return match.group(1).strip()
    return (value or '').strip()


def _decode_part(part):
    """Decode a message part's payload to str using its charset."""
    payload = part.get_payload(decode=True)
    if payload is None:
        return ''
    charset = part.get_content_charset() or 'utf-8'
    try:
        return payload.decode(charset, errors='replace')
    except LookupError:
        return payload.decode('utf-8', errors='replace')


def extract_text_body(msg):
    """Recursively extract the plain-text body. Prefers text/plain, falls back
    to cleaned text/html. Attachments are ignored (text-only collector)."""
    plain_parts, html_parts = [], []
    for part in msg.walk():
        if part.get_content_maintype() == 'multipart':
            continue
        if part.get_content_disposition() == 'attachment':
            continue
        ctype = part.get_content_type()
        if ctype == 'text/plain':
            plain_parts.append(_decode_part(part))
        elif ctype == 'text/html':
            html_parts.append(html_to_text(_decode_part(part)))

    if plain_parts:
        return '\n'.join(p.strip() for p in plain_parts).strip()
    if html_parts:
        return '\n'.join(p for p in html_parts).strip()

    # Fallback: a bare non-multipart message with no walked text part.
    if not msg.is_multipart():
        payload = msg.get_payload(decode=True)
        if payload:
            charset = msg.get_content_charset() or 'utf-8'
            try:
                return payload.decode(charset, errors='replace').strip()
            except LookupError:
                return payload.decode('utf-8', errors='replace').strip()
    return ''


def _to_iso8601(dt):
    if dt is None:
        dt = datetime.now(timezone.utc)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')


def parse_message(raw):
    """Parse raw RFC 822 bytes into a dict with the fields MH needs."""
    if isinstance(raw, bytes):
        msg = email.message_from_bytes(raw)
    else:
        msg = email.message_from_string(raw)

    from_hdr = msg.get('From', '')
    subject = decode_mime_header(msg.get('Subject', ''))
    sender = get_email_address(from_hdr) or from_hdr.strip()

    message_id = (msg.get('Message-ID') or '').strip()
    if message_id.startswith('<') and message_id.endswith('>'):
        message_id = message_id[1:-1]

    date_raw = (msg.get('Date') or '').strip()
    dt = email.utils.parsedate_to_datetime(date_raw) if date_raw else None

    return {
        'sender': sender[:255],
        'subject': subject,
        'date_raw': date_raw or '',
        'date_iso': _to_iso8601(dt),
        'date_dt': dt.astimezone(timezone.utc) if dt else None,
        'message_id': message_id,
        'body': extract_text_body(msg),
    }


def build_payload(parsed, label):
    """Build the MH POST /api/v1/messages payload from a parsed mail."""
    content = 'Subject: {subject}\nFrom: {sender}\nDate: {date}\n\n{body}'.format(
        subject=parsed['subject'] or '(no subject)',
        sender=parsed['sender'],
        date=parsed['date_raw'] or parsed['date_iso'],
        body=parsed['body'],
    )
    device_id = 'mail-' + re.sub(r'[^A-Za-z0-9_-]', '-', label)
    return {
        'source_device_id': device_id,
        'type': 'EMAIL',
        'sender': parsed['sender'],
        'content': content,
        'timestamp': parsed['date_iso'],
        'metadata': {
            'mailbox': label,
            'message_id': parsed['message_id'],
            'subject': parsed['subject'],
        },
    }


# ---------------------------------------------------------------------------
# Dedup state (~/.message_hub/processed_mails.json)
# ---------------------------------------------------------------------------

def state_file_path(state_dir=None):
    if state_dir is None:
        state_dir = os.environ.get('MAIL_STATE_DIR') or DEFAULT_STATE_DIR
    return os.path.join(os.path.expanduser(state_dir), STATE_FILE_NAME)


def load_state(path):
    try:
        with open(path, 'r', encoding='utf-8') as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def save_state(state, path):
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    tmp_path = path + '.tmp'
    with open(tmp_path, 'w', encoding='utf-8') as fh:
        json.dump(state, fh, indent=2, ensure_ascii=False)
    os.replace(tmp_path, path)  # atomic on POSIX


def prune_state(state, retention_days=STATE_RETENTION_DAYS,
                max_per_mailbox=STATE_MAX_PER_MAILBOX):
    cutoff = time.time() - retention_days * 86400
    for label, processed in list(state.items()):
        if not isinstance(processed, dict):
            state[label] = {}
            continue
        kept = {k: v for k, v in processed.items()
                if isinstance(v, (int, float)) and v >= cutoff}
        state[label] = dict(
            sorted(kept.items(), key=lambda kv: kv[1], reverse=True)[:max_per_mailbox])
    return state


# ---------------------------------------------------------------------------
# IMAP fetching
# ---------------------------------------------------------------------------

def connect_account(account):
    """Connect + login + select folder. Returns the imaplib connection."""
    if account['use_ssl']:
        conn = imaplib.IMAP4_SSL(account['host'], account['port'], timeout=30)
    else:
        conn = imaplib.IMAP4(account['host'], account['port'], timeout=30)
    conn.login(account['user'], account['password'])
    status, data = conn.select(account['folder'])
    if status != 'OK':
        raise RuntimeError('select %s failed: %s' % (account['folder'], data))
    return conn


def fetch_unseen_uids(conn):
    """Legacy: search UIDs of all UNSEEN mails."""
    status, data = conn.uid('SEARCH', None, '(UNSEEN)')
    if status != 'OK':
        raise RuntimeError('IMAP SEARCH failed: %r' % (data,))
    if not data or not data[0]:
        return []
    return [uid.decode() for uid in data[0].split()]


def fetch_recent_uids(conn, since_days):
    """Search UIDs of mails received since ~since_days ago.

    IMAP's SINCE is day-granular (server-local dates), so we ask for one
    extra day back here and let the caller do the precise local filter on the
    parsed Date header.
    """
    since = (datetime.now(timezone.utc) - timedelta(days=since_days + 1)) \
        .strftime('%d-%b-%Y')
    status, data = conn.uid('SEARCH', None, '(SINCE %s)' % since)
    if status != 'OK':
        raise RuntimeError('IMAP SEARCH failed: %r' % (data,))
    if not data or not data[0]:
        return []
    return [uid.decode() for uid in data[0].split()]


def fetch_raw(conn, uid):
    status, data = conn.uid('FETCH', uid, '(RFC822)')
    if status != 'OK' or not data:
        raise RuntimeError('IMAP FETCH %s failed: %r' % (uid, data))
    for part in data:
        if isinstance(part, tuple):
            return part[1]
    raise RuntimeError('no RFC822 payload returned for uid %s' % uid)


def mark_seen(conn, uid):
    """Mark a mail \\Seen on the server. Best-effort: failures are logged but
    do not fail the import (the dedup file still protects against duplicates)."""
    try:
        conn.uid('STORE', uid, '+FLAGS', r'(\Seen)')
    except Exception:
        log.warning('could not mark uid %s as seen', uid, exc_info=True)


def report_message(payload, mh_url=DEFAULT_MH_URL, timeout=REQUEST_TIMEOUT):
    """POST one message to MH. Returns (ok: bool, detail: str)."""
    url = '%s/api/v1/messages' % mh_url.rstrip('/')
    headers = {}
    api_key = os.environ.get('MH_API_KEY')
    if api_key:
        headers['X-API-Key'] = api_key
    try:
        resp = requests.post(url, json=payload, headers=headers, timeout=timeout)
        if resp.status_code == 201:
            return True, resp.text
        return False, 'HTTP %s: %s' % (resp.status_code, resp.text[:500])
    except requests.RequestException as exc:
        return False, str(exc)


def process_mailbox(account, state, mh_url, dry_run=False, since_days=None):
    """Collect one mailbox. Returns {imported, failed, skipped}.

    since_days=None  -> legacy UNSEEN mode (marks mails \\Seen as it goes).
    since_days=N     -> "recent N days" mode: pulls ALL mails (seen or not)
                        whose Date header is within the window and does NOT
                        touch the mailbox's \\Seen flags (it is usually the
                        user's primary mailbox). Dedup is handled purely by
                        the local state file.
    """
    label = account['label']
    stats = {'imported': 0, 'failed': 0, 'skipped': 0}
    processed = state.get(label)  # do NOT insert into state unless something
    if processed is None:         # was imported (failed/dry runs stay clean)
        processed = {}
    now = time.time()

    conn = connect_account(account)
    try:
        if since_days is not None:
            cutoff = now - since_days * 86400
            uids = fetch_recent_uids(conn, since_days)
        else:
            cutoff = None
            uids = fetch_unseen_uids(conn)

        for uid in uids:
            uid_key = 'uid:' + uid
            try:
                raw = fetch_raw(conn, uid)
                parsed = parse_message(raw)
                msg_key = parsed['message_id'] or uid_key

                if msg_key in processed or uid_key in processed:
                    stats['skipped'] += 1
                    if since_days is None:
                        mark_seen(conn, uid)  # idempotent; keep mailbox tidy
                    continue

                # Precise local window filter (IMAP SINCE is day-granular).
                if cutoff is not None:
                    d = parsed['date_dt']
                    if d is None or d.timestamp() < cutoff:
                        stats['skipped'] += 1
                        continue

                payload = build_payload(parsed, label)
                if dry_run:
                    log.info('[dry-run] would POST mailbox=%s uid=%s '
                             'sender=%s subject=%r',
                             label, uid, parsed['sender'], parsed['subject'])
                    stats['imported'] += 1
                    continue

                ok, detail = report_message(payload, mh_url)
                if ok:
                    processed[msg_key] = now
                    processed[uid_key] = now
                    if since_days is None:
                        mark_seen(conn, uid)
                    stats['imported'] += 1
                    log.info('imported uid=%s sender=%s subject=%r -> %s',
                             uid, parsed['sender'], parsed['subject'], label)
                else:
                    stats['failed'] += 1
                    log.error('POST failed for uid=%s (%s): %s',
                              uid, label, detail)
            except Exception:
                stats['failed'] += 1
                log.exception('failed processing uid=%s on mailbox %s', uid, label)
    finally:
        try:
            conn.logout()
        except Exception:
            pass
    if stats['imported'] > 0 and not dry_run:
        state[label] = processed
    return stats


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------

def run_once(state_dir=None, dry_run=False, accounts=None, mh_url=None,
             since_days=None):
    """Collect every configured mailbox once. Returns
    {mailbox_label: {imported, failed, skipped}}."""
    accounts = accounts if accounts is not None else load_accounts()
    if not accounts:
        log.warning('no mail accounts configured (MAIL_ACCOUNTS unset) - nothing to do')
        return {}

    if mh_url is None:
        mh_url = os.environ.get('MH_URL') or DEFAULT_MH_URL
    path = state_file_path(state_dir)
    state = load_state(path)

    stats = {}
    for acc in accounts:
        label = acc['label']
        try:
            stats[label] = process_mailbox(acc, state, mh_url, dry_run=dry_run,
                                           since_days=since_days)
        except Exception:
            stats[label] = {'imported': 0, 'failed': 0, 'skipped': 0,
                            'error': 'mailbox failed'}
            log.exception('mailbox %s failed', label)

    if not dry_run:
        prune_state(state)
        save_state(state, path)
    return stats


def run_collector_loop(interval=300, state_dir=None, dry_run=False, logger=None,
                       since_days=None):
    """Run forever: collect, sleep, repeat. `interval` is seconds."""
    global log
    if logger is not None:
        log = logger
    while True:
        try:
            stats = run_once(state_dir=state_dir, dry_run=dry_run,
                             since_days=since_days)
            log.info('collect cycle finished: %s', stats)
        except Exception:
            log.exception('collect cycle crashed')
        log.info('sleeping %ss until next cycle', interval)
        time.sleep(interval)


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog='mail_collector.py',
        description='Collect IMAP mail into Message Hub (type=EMAIL).')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--once', action='store_true',
                      help='run a single cycle and exit (for cron)')
    mode.add_argument('--watch', type=int, metavar='N',
                      help='run forever, collecting every N seconds')
    parser.add_argument('--dry-run', action='store_true',
                        help='fetch + parse only; do not POST to MH or mark seen')
    parser.add_argument('--since-days', type=int, default=None, metavar='N',
                        help='pull ALL mail (seen or not) from the last N days '
                             'instead of only UNSEEN mail; does not alter '
                             '\\Seen flags on the mailbox')
    parser.add_argument('--state-dir', default=None,
                        help='directory for processed_mails.json '
                             '(default: ~/.message_hub or $MAIL_STATE_DIR)')
    parser.add_argument('--log-level', default='INFO',
                        help='logging level (DEBUG/INFO/WARNING/ERROR)')
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format='%(asctime)s %(levelname)s %(name)s: %(message)s')

    if args.watch:
        log.info('starting watch loop (interval=%ss, dry_run=%s, since_days=%s)',
                 args.watch, args.dry_run, args.since_days)
        run_collector_loop(interval=args.watch, state_dir=args.state_dir,
                           dry_run=args.dry_run, since_days=args.since_days)
    else:
        stats = run_once(state_dir=args.state_dir, dry_run=args.dry_run,
                         since_days=args.since_days)
        print(json.dumps(stats, ensure_ascii=False, indent=2))
        log.info('cycle done: %s', stats)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
