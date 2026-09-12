from flask import render_template, request, jsonify, flash, redirect, url_for, current_app
from sqlalchemy import desc, func
from datetime import datetime, timezone, timedelta
from models import db, Message, Device
from . import web
import requests
import json
import os
import threading
import time
import message_filters as _mf

# ---------------------------------------------------------------------------
# Manual "Import Email" trigger (Dashboard button)
#
# POST   /import-email            -> start a background collect (since_days=N)
# GET    /import-email/status     -> {running, last, error, started_at, ...}
#
# The endpoint returns immediately and the collect runs on a daemon thread so a
# single gunicorn worker does not deadlock while the collector POSTs messages
# back to this same server. State lives in-process (fine for WORKERS=1).
# ---------------------------------------------------------------------------

_import_lock = threading.Lock()
_import_state = {
    'running': False,
    'started_at': None,
    'finished_at': None,
    'last': None,      # {label: {imported, failed, skipped}} from last run
    'error': None,
    'accounts': 0,     # number of configured IMAP accounts at trigger time
    'since_days': None,
}


def _is_import_running():
    with _import_lock:
        return bool(_import_state.get('running'))


def _set_import_running(flag):
    with _import_lock:
        _import_state['running'] = flag


@web.route('/import-email', methods=['POST'])
def import_email():
    """Start a background mail collect. Body (JSON): {"since_days": N}."""
    since_days = _mc.configured_since_days() if '_mc' in globals() else 1
    body = request.get_json(silent=True) or {}
    try:
        since_days = int(body.get('since_days', since_days))
    except (TypeError, ValueError):
        return jsonify({'error': 'since_days must be an integer'}), 400
    if since_days < 1:
        return jsonify({'error': 'since_days must be >= 1'}), 400

    if _is_import_running():
        return jsonify({'error': 'A mail import is already running'}), 409

    # How many accounts are configured right now (best-effort).
    try:
        import mail_collector
        accounts = mail_collector.load_accounts()
    except Exception:
        accounts = []
    if not accounts:
        return jsonify({'error': 'No mail accounts configured '
                                 '(set MAIL_ACCOUNTS in the server .env)'}), 400

    with _import_lock:
        _import_state.update({
            'running': True,
            'started_at': time.time(),
            'finished_at': None,
            'last': None,
            'error': None,
            'accounts': len(accounts),
            'since_days': since_days,
        })

    def _worker():
        try:
            result = mail_collector.run_once(since_days=since_days)
            with _import_lock:
                _import_state['last'] = result
        except Exception as exc:  # pragma: no cover - defensive
            with _import_lock:
                _import_state['error'] = str(exc)
        finally:
            with _import_lock:
                _import_state['running'] = False
                _import_state['finished_at'] = time.time()

    threading.Thread(target=_worker, daemon=True, name='mail-import').start()
    return jsonify({'started': True, 'since_days': since_days})


@web.route('/import-email/status')
def import_email_status():
    with _import_lock:
        state = dict(_import_state)
    return jsonify(state)


@web.route('/')
@web.route('/dashboard')
def dashboard():
    """Dashboard showing message overview and statistics - mirrors CLI status command"""
    try:
        # Get total message count
        total_messages = db.session.query(func.count(Message.id)).scalar() or 0
        
        # Get recent messages (last 24 hours)
        last_24h = datetime.now(timezone.utc) - timedelta(hours=24)
        recent_count = db.session.query(func.count(Message.id)).filter(
            Message.timestamp >= last_24h
        ).scalar() or 0
        
        # Get messages by type
        type_stats = db.session.query(
            Message.type,
            func.count(Message.id).label('count')
        ).group_by(Message.type).all()
        
        # Get messages by device (mirrors CLI device stats)
        device_stats = db.session.query(
            Message.source_device_id,
            func.count(Message.id).label('count')
        ).group_by(Message.source_device_id).all()
        
        # Get latest message timestamp
        latest_message = db.session.query(Message).order_by(desc(Message.timestamp)).first()
        latest_timestamp = latest_message.timestamp if latest_message else None
        
        # Get recent messages for preview (mirrors CLI messages --limit 5)
        recent_messages = db.session.query(Message).order_by(
            desc(Message.timestamp)
        ).limit(5).all()
        
        stats = {
            'total_messages': total_messages,
            'recent_count': recent_count,
            'latest_timestamp': latest_timestamp,
            'type_stats': {stat.type: stat.count for stat in type_stats},
            'device_stats': {stat.source_device_id: stat.count for stat in device_stats}
        }
        
        return render_template('dashboard.html', 
                             stats=stats, 
                             recent_messages=recent_messages,
                             import_days=_mc.configured_since_days())
    
    except Exception as e:
        flash(f'Error loading dashboard: {str(e)}', 'error')
        return render_template('dashboard.html', stats={}, recent_messages=[])

@web.route('/messages')
def messages():
    """List messages with filtering - mirrors CLI messages command"""
    # Get filter parameters (same as CLI)
    page = int(request.args.get('page', 1))
    per_page = int(request.args.get('limit', 20))
    message_type = request.args.get('type', '').strip()
    device = request.args.get('device', '').strip()
    recipient = request.args.get('recipient', '').strip()
    # The browser converts the user's local day boundaries into instants and
    # sends since_utc/until_utc; bare dates are the fallback (read as UTC days).
    since = request.args.get('since_utc', '').strip() or request.args.get('since', '').strip()
    until = request.args.get('until_utc', '').strip() or request.args.get('until', '').strip()
    
    try:
        # Build query — filtering lives in message_filters so that the rows
        # shown here and the rows a "delete all matching" removes are identical.
        query = _mf.apply_filters(db.session.query(Message),
                                  message_type=message_type,
                                  device=device,
                                  recipient=recipient,
                                  since=since,
                                  until=until)
        
        # Order by timestamp (newest first)
        query = query.order_by(desc(Message.timestamp))
        
        # Paginate
        pagination = query.paginate(
            page=page, 
            per_page=per_page, 
            error_out=False
        )
        
        messages = pagination.items
        
        # Last item index to show (clamp on the final page)
        page_end = min(pagination.page * pagination.per_page, pagination.total)
        
        # Filter options
        message_types = _mf.list_message_types()
        devices = _mf.list_devices()
        recipients = _mf.list_recipients()
        
        active = _mf.normalize_filters(message_type=message_type, device=device,
                                       recipient=recipient, since=since, until=until)
        filter_summary = _mf.describe_filters(active)
        
        return render_template('messages.html',
                             messages=messages,
                             pagination=pagination,
                             page_end=page_end,
                             message_types=message_types,
                             devices=devices,
                             recipients=recipients,
                             has_filters=bool(filter_summary),
                             active_filter_count=len([v for v in active.values() if v]),
                             filter_summary=filter_summary,
                             current_filters={
                                 'type': message_type,
                                 'device': device,
                                 'recipient': recipient,
                                 'since': request.args.get('since', '').strip(),
                                 'until': request.args.get('until', '').strip(),
                                 'limit': per_page
                             })
    
    except Exception as e:
        flash(f'Error loading messages: {str(e)}', 'error')
        return render_template('messages.html',
                             messages=[],
                             pagination=None,
                             message_types=[],
                             devices=[],
                             recipients=[],
                             has_filters=False,
                             active_filter_count=0,
                             filter_summary='',
                             current_filters={})


def _messages_redirect():
    """Send the user back to the list they were looking at, filters intact."""
    args = {}
    for key in ('type', 'device', 'recipient', 'limit', 'page'):
        value = request.form.get(key, '').strip()
        if value:
            args[key] = value
    # Prefer the browser-computed instants: they encode the user's local day
    # boundaries, which a bare date would lose (it would be re-read as UTC).
    for key in ('since', 'until'):
        instant = request.form.get(key + '_utc', '').strip()
        bare = request.form.get(key, '').strip()
        if instant:
            args[key + '_utc'] = instant
        if bare:
            args[key] = bare
    return redirect(url_for('web.messages', **args))


@web.route('/messages/delete', methods=['POST'])
def delete_messages():
    """Delete the messages the user ticked.

    Deliberately takes explicit ids rather than a filter: the count the user
    saw in the confirm dialog is the count that disappears.
    """
    ids = [i.strip() for i in request.form.getlist('ids') if i.strip()]
    if not ids:
        flash('Nothing selected — tick the messages you want to delete first.', 'error')
        return _messages_redirect()

    try:
        # Cap the IN clause: SQLite's default parameter limit makes huge
        # selects fail outright, and a page never holds more than 100 anyway.
        ids = ids[:1000]
        deleted = db.session.query(Message).filter(Message.id.in_(ids)).delete(
            synchronize_session=False)
        db.session.commit()
        flash('Deleted %d message%s.' % (deleted, '' if deleted == 1 else 's'), 'success')
    except Exception as e:
        db.session.rollback()
        current_app.logger.error('Delete messages failed: %s', e)
        flash(f'Delete failed: {str(e)}', 'error')

    return _messages_redirect()


@web.route('/messages/delete-filtered', methods=['POST'])
def delete_messages_filtered():
    """Delete every message matching the current filters (not just this page).

    This is the "clean up all 抖音 notifications from last month" path. It runs
    through the same apply_filters() the list page used, so the total shown in
    the confirm dialog is exactly what gets removed.
    """
    if request.form.get('confirm') != 'yes':
        flash('Delete cancelled — confirmation was not provided.', 'error')
        return _messages_redirect()

    filters = _mf.normalize_filters(
        message_type=request.form.get('type'),
        device=request.form.get('device'),
        recipient=request.form.get('recipient'),
        since=request.form.get('since_utc') or request.form.get('since'),
        until=request.form.get('until_utc') or request.form.get('until'),
    )
    if not any(filters.values()):
        # No filters means "delete everything" — far too easy to click by
        # accident, so it needs an explicit second signal.
        flash('Refusing to delete: no filters selected (that would erase everything).', 'error')
        return _messages_redirect()

    try:
        query = _mf.apply_filter_dict(db.session.query(Message), filters)
        deleted = query.delete(synchronize_session=False)
        db.session.commit()
        summary = _mf.describe_filters(filters)
        flash('Deleted %d message%s matching %s.' % (
            deleted, '' if deleted == 1 else 's', summary), 'success')
    except Exception as e:
        db.session.rollback()
        current_app.logger.error('Delete filtered messages failed: %s', e)
        flash(f'Delete failed: {str(e)}', 'error')

    return _messages_redirect()

@web.route('/messages/<message_id>')
def message_detail(message_id):
    """Show detailed message view"""
    try:
        message = db.session.query(Message).filter(Message.id == message_id).first()
        if not message:
            flash('Message not found', 'error')
            return redirect(url_for('web.messages'))
        
        return render_template('message_detail.html', message=message)
    
    except Exception as e:
        flash(f'Error loading message: {str(e)}', 'error')
        return redirect(url_for('web.messages'))

@web.route('/status')
def status():
    """Show server status and statistics - mirrors CLI status command"""
    try:
        # Get sync status (same as CLI)
        total_messages = db.session.query(func.count(Message.id)).scalar() or 0
        latest_message = db.session.query(Message).order_by(desc(Message.timestamp)).first()
        latest_timestamp = latest_message.timestamp if latest_message else None
        
        # Get device stats (mirrors CLI status device stats)
        device_stats = db.session.query(
            Message.source_device_id,
            func.count(Message.id).label('count')
        ).group_by(Message.source_device_id).all()
        
        # Get message type distribution
        type_stats = db.session.query(
            Message.type,
            func.count(Message.id).label('count')
        ).group_by(Message.type).all()
        
        # Get recent activity (last 7 days)
        activity_data = []
        for i in range(7):
            day = datetime.now(timezone.utc) - timedelta(days=i)
            day_start = day.replace(hour=0, minute=0, second=0, microsecond=0)
            day_end = day_start + timedelta(days=1)
            
            count = db.session.query(func.count(Message.id)).filter(
                Message.timestamp >= day_start,
                Message.timestamp < day_end
            ).scalar() or 0
            
            activity_data.append({
                'date': day_start.strftime('%Y-%m-%d'),
                'count': count
            })
        
        activity_data.reverse()  # Oldest first for chart
        
        status_data = {
            'healthy': True,
            'total_messages': total_messages,
            'latest_timestamp': latest_timestamp,
            'device_stats': {stat.source_device_id: stat.count for stat in device_stats},
            'type_stats': {stat.type: stat.count for stat in type_stats},
            'activity_data': activity_data
        }
        
        return render_template('status.html', status=status_data)
    
    except Exception as e:
        flash(f'Error loading status: {str(e)}', 'error')
        return render_template('status.html', status={'healthy': False})


# ---------------------------------------------------------------------------
# Settings: API key management (page is protected by Cloudflare Access)
# ---------------------------------------------------------------------------

import hashlib
import secrets as _secrets
from models import ApiKey as ApiKeyModel

KEY_PREFIX = 'mhk_'


def _hash_key(key):
    return hashlib.sha256(key.encode('utf-8')).hexdigest()


@web.route('/settings')
def settings():
    """Settings page — lists API keys and lets the owner generate/revoke."""
    keys = ApiKeyModel.query.order_by(ApiKeyModel.created_at.desc()).all()
    from flask import current_app as _app
    legacy_configured = bool((_app.config.get('API_KEY') or '').strip())
    return render_template('settings.html', keys=keys,
                           legacy_configured=legacy_configured,
                           releases=_list_releases(),
                           mail_accounts=_mail_accounts_for_display(),
                           mail_since_days=_mc.configured_since_days())


@web.route('/settings/api-keys/generate', methods=['POST'])
def generate_api_key():
    """Create a new API key. Plaintext is returned once and never again."""
    name = (request.form.get('name') or '').strip()
    if not name:
        flash('Please provide a label for the key (e.g. "phone" or "cli")', 'error')
        return redirect(url_for('web.settings'))
    if len(name) > 255:
        flash('Label too long (max 255 chars)', 'error')
        return redirect(url_for('web.settings'))

    secret = KEY_PREFIX + _secrets.token_urlsafe(32)
    ak = ApiKeyModel(
        name=name,
        key_hash=_hash_key(secret),
        prefix=secret[:12],
        is_active=True,
    )
    db.session.add(ak)
    db.session.commit()
    # show the new key once (flash survives the redirect)
    flash(f'Your new API key (shown once, store it safely): {secret}', 'success')
    return redirect(url_for('web.settings'))


@web.route('/settings/api-keys/<key_id>/revoke', methods=['POST'])
def revoke_api_key(key_id):
    ak = db.session.get(ApiKeyModel, key_id)
    if not ak:
        flash('API key not found', 'error')
    else:
        ak.is_active = False
        db.session.commit()
        flash(f'API key "{ak.name}" revoked', 'success')
    return redirect(url_for('web.settings'))

# ---------------------------------------------------------------------------
# Crash reports (uploaded by the Android client; see api/v1/diagnostics.py)
# ---------------------------------------------------------------------------

from models import CrashReport as CrashReportModel


@web.route('/crashes')
def crashes():
    """Crash reports uploaded by client apps — full stack traces inline."""
    limit = min(int(request.args.get('limit', 50)), 200)
    reports = (CrashReportModel.query
               .order_by(desc(CrashReportModel.received_at))
               .limit(limit).all())
    total = CrashReportModel.query.count()

    # group counts by fingerprint so repeated crashes are obvious
    from sqlalchemy import func as _func
    grouped = (db.session.query(CrashReportModel.fingerprint,
                                _func.count(CrashReportModel.id))
               .group_by(CrashReportModel.fingerprint).all())
    dup_counts = {fp: n for fp, n in grouped if fp}

    return render_template('crashes.html', reports=reports, total=total,
                           dup_counts=dup_counts)


# ---------------------------------------------------------------------------
# APK distribution: files live in instance/releases/ (that dir is excluded
# from the rsync deploy, so `--delete` never wipes uploaded builds).
# ---------------------------------------------------------------------------

import os as _os
from flask import send_from_directory as _send_from_directory


def _releases_dir():
    path = _os.path.join(current_app.instance_path, 'releases')
    _os.makedirs(path, exist_ok=True)
    return path


@web.route('/downloads/<path:filename>')
def download_release(filename):
    """Serve an uploaded APK (or other release artifact) for phone download."""
    return _send_from_directory(_releases_dir(), filename, as_attachment=True)


def _list_releases():
    """Newest-first list of (filename, size_mb, mtime) for the Settings page."""
    from datetime import datetime as _dt
    out = []
    for name in _os.listdir(_releases_dir()):
        if not name.lower().endswith('.apk'):
            continue
        full = _os.path.join(_releases_dir(), name)
        st = _os.stat(full)
        out.append({
            'name': name,
            'size_mb': round(st.st_size / 1048576, 1),
            'mtime': _dt.fromtimestamp(st.st_mtime),
        })
    return sorted(out, key=lambda r: r['mtime'], reverse=True)


# ---------------------------------------------------------------------------
# Mail (IMAP) settings — managed from the web so the owner never has to SSH in
# and the password never travels through anyone else.
# Accounts live in instance/mail_accounts.json (0600, gitignored).
# ---------------------------------------------------------------------------

import mail_collector as _mc


def _mail_accounts_for_display():
    """Accounts with the password masked."""
    out = []
    for a in _mc.load_accounts_from_file():
        out.append({
            'host': a.get('host'), 'user': a.get('user'), 'label': a.get('label'),
            'port': a.get('port'), 'folder': a.get('folder'),
            'has_password': bool(a.get('password')),
        })
    return out


@web.route('/settings/mail', methods=['POST'])
def save_mail_account():
    """Add or update one IMAP account."""
    host = (request.form.get('host') or '').strip()
    user = (request.form.get('user') or '').strip()
    password = request.form.get('password') or ''
    label = (request.form.get('label') or user).strip()
    folder = (request.form.get('folder') or 'INBOX').strip()
    original = (request.form.get('original_user') or '').strip()

    try:
        port = int(request.form.get('port') or 993)
        since_days = max(1, int(request.form.get('since_days') or 1))
    except ValueError:
        flash('Port and days must be numbers', 'error')
        return redirect(url_for('web.settings'))

    if not host or not user:
        flash('Host and mailbox address are required', 'error')
        return redirect(url_for('web.settings'))

    accounts = _mc.load_accounts_from_file()
    existing = next((a for a in accounts if a.get('user') == (original or user)), None)

    if existing is not None:
        # keep the stored password when the field is left blank
        existing.update({'host': host, 'user': user, 'label': label,
                         'port': port, 'folder': folder, 'use_ssl': True})
        if password:
            existing['password'] = password
        flash(f'Mailbox {user} updated', 'success')
    else:
        if not password:
            flash('Password is required for a new mailbox', 'error')
            return redirect(url_for('web.settings'))
        accounts.append({'host': host, 'user': user, 'password': password,
                         'port': port, 'use_ssl': True, 'folder': folder,
                         'label': label})
        flash(f'Mailbox {user} added', 'success')

    _mc.save_accounts_to_file(accounts, since_days=since_days)
    return redirect(url_for('web.settings'))


@web.route('/settings/mail/<int:index>/delete', methods=['POST'])
def delete_mail_account(index):
    accounts = _mc.load_accounts_from_file()
    if 0 <= index < len(accounts):
        removed = accounts.pop(index)
        _mc.save_accounts_to_file(accounts, since_days=_mc.configured_since_days())
        flash(f'Mailbox {removed.get("user")} removed', 'success')
    else:
        flash('Mailbox not found', 'error')
    return redirect(url_for('web.settings'))


@web.route('/settings/mail/test', methods=['POST'])
def test_mail_account():
    """Try an IMAP login with the submitted (or stored) credentials, read-only."""
    import imaplib
    from datetime import datetime, timedelta, timezone

    host = (request.form.get('host') or '').strip()
    user = (request.form.get('user') or '').strip()
    password = request.form.get('password') or ''
    original = (request.form.get('original_user') or '').strip()
    try:
        port = int(request.form.get('port') or 993)
        since_days = max(1, int(request.form.get('since_days') or 1))
    except ValueError:
        return jsonify({'ok': False, 'message': 'Port/days must be numbers'}), 400

    if password == '' and original:
        stored = next((a for a in _mc.load_accounts_from_file()
                       if a.get('user') == original), None)
        password = (stored or {}).get('password', '')

    if not (host and user and password):
        return jsonify({'ok': False, 'message': 'Host, address and password are required'}), 400

    try:
        conn = imaplib.IMAP4_SSL(host, port, timeout=30)
        conn.login(user, password)
    except Exception as exc:
        return jsonify({'ok': False,
                        'message': f'{type(exc).__name__}: {exc}'}), 200

    try:
        status, data = conn.select('INBOX')
        total = int(data[0]) if data and data[0] else 0
        since = (datetime.now(timezone.utc) - timedelta(days=since_days + 1)).strftime('%d-%b-%Y')
        _, sdata = conn.uid('SEARCH', None, f'(SINCE {since})')
        uids = sdata[0].split() if sdata and sdata[0] else []
        conn.logout()
        return jsonify({'ok': True,
                        'message': f'Connected. INBOX has {total} messages; '
                                   f'{len(uids)} arrived since {since}.',
                        'total': total, 'candidates': len(uids)})
    except Exception as exc:
        try:
            conn.logout()
        except Exception:
            pass
        return jsonify({'ok': False, 'message': f'Login ok but SELECT failed: {exc}'}), 200
