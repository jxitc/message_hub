"""Diagnostics endpoints — crash reports from client apps.

POST /api/v1/diagnostics/crashes        client uploads crash report(s)
GET  /api/v1/diagnostics/crashes        list recent reports (for remote triage)
GET  /api/v1/diagnostics/crashes/<id>   single report with full stack trace

All endpoints sit under /api/v1 and therefore require the X-API-Key header
(the app already sends it for message uploads).

Why this exists: a crash on the phone used to require a USB cable plus adb
logcat to diagnose. Clients now upload the crash (Java uncaught exception via
a handler, or a native crash/ANR read back from ApplicationExitInfo), so it
can be triaged remotely from the web UI or with a single GET.
"""

import hashlib
from datetime import datetime, timezone

from flask import jsonify, request, current_app
from marshmallow import Schema, fields, validate, ValidationError
from sqlalchemy import desc

from . import api_v1
from models import db, CrashReport


class CrashReportSchema(Schema):
    source_device_id = fields.Str(required=True, validate=validate.Length(min=1, max=255))
    reason = fields.Str(required=True, validate=validate.Length(min=1, max=64))
    app_version = fields.Str(load_default=None, allow_none=True)
    build_type = fields.Str(load_default=None, allow_none=True)
    device_model = fields.Str(load_default=None, allow_none=True)
    android_version = fields.Str(load_default=None, allow_none=True)
    reason_code = fields.Int(load_default=None, allow_none=True)
    summary = fields.Str(load_default=None, allow_none=True)
    stacktrace = fields.Str(load_default=None, allow_none=True)
    occurred_at = fields.DateTime(load_default=None, allow_none=True)
    # client-side idempotency key (so a retried upload does not duplicate)
    client_report_id = fields.Str(load_default=None, allow_none=True)


crash_schema = CrashReportSchema()
crash_list_schema = CrashReportSchema(many=True)


def _fingerprint(reason, summary):
    raw = '%s|%s' % (reason or '', summary or '')
    return hashlib.sha256(raw.encode('utf-8')).hexdigest()[:32]


@api_v1.route('/diagnostics/crashes', methods=['POST'])
def upload_crash():
    """Accept one crash report (or a batch via {"reports": [...]})."""
    body = request.get_json(silent=True)
    if not body:
        return jsonify({'error': 'No JSON data provided'}), 400

    items = body.get('reports') if isinstance(body, dict) and 'reports' in body else [body]
    if not isinstance(items, list) or not items:
        return jsonify({'error': 'Expected an object or {"reports": [...]}'}), 400

    saved_ids = []
    try:
        for item in items:
            data = crash_schema.load(item)

            # idempotency: skip if this client_report_id was already stored
            crid = data.get('client_report_id')
            if crid:
                existing = CrashReport.query.filter_by(id=crid).first()
                if existing is not None:
                    saved_ids.append(existing.id)
                    continue

            report = CrashReport(
                source_device_id=data['source_device_id'],
                reason=data['reason'],
                reason_code=data.get('reason_code'),
                app_version=data.get('app_version'),
                build_type=data.get('build_type'),
                device_model=data.get('device_model'),
                android_version=data.get('android_version'),
                summary=(data.get('summary') or '')[:512],
                stacktrace=data.get('stacktrace'),
                occurred_at=data.get('occurred_at') or datetime.now(timezone.utc),
                received_at=datetime.now(timezone.utc),
                fingerprint=_fingerprint(data['reason'], data.get('summary')),
            )
            if crid:
                report.id = crid
            db.session.add(report)
            db.session.flush()
            saved_ids.append(report.id)

        db.session.commit()
        return jsonify({'message': 'Crash report(s) stored',
                        'count': len(saved_ids),
                        'ids': saved_ids}), 201

    except ValidationError as e:
        db.session.rollback()
        return jsonify({'error': 'Validation error', 'details': e.messages}), 400
    except Exception as e:
        current_app.logger.error('Error storing crash report: %s', str(e))
        db.session.rollback()
        return jsonify({'error': 'Internal server error'}), 500


@api_v1.route('/diagnostics/crashes', methods=['GET'])
def list_crashes():
    """List recent crash reports (newest first). `?limit=` `?device=` `?reason=`"""
    limit = min(request.args.get('limit', 50, type=int), 500)
    query = CrashReport.query
    device = request.args.get('device')
    reason = request.args.get('reason')
    if device:
        query = query.filter(CrashReport.source_device_id == device)
    if reason:
        query = query.filter(CrashReport.reason == reason)

    reports = query.order_by(desc(CrashReport.received_at)).limit(limit).all()
    return jsonify({
        'crashes': [r.to_dict(include_trace=False) for r in reports],
        'total': CrashReport.query.count(),
        'returned': len(reports),
    })


@api_v1.route('/diagnostics/crashes/<report_id>', methods=['GET'])
def get_crash(report_id):
    """Full crash report including the stack trace."""
    report = db.session.get(CrashReport, report_id)
    if report is None:
        return jsonify({'error': 'Crash report not found'}), 404
    return jsonify(report.to_dict(include_trace=True))
