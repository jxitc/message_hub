from . import db
from datetime import datetime
import uuid


class CrashReport(db.Model):
    """A crash report uploaded by a client app (currently the Android client).

    Reports are written by the app on the next launch (Java uncaught
    exceptions are caught by a handler; native crashes / ANRs / process kills
    are read back from ApplicationExitInfo), then POSTed to
    ``/api/v1/diagnostics/crashes``. They exist so that a crash can be
    diagnosed without attaching adb — the web UI lists them with the full
    stack trace.
    """
    __tablename__ = 'crash_reports'

    id = db.Column(db.String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    # client identity
    source_device_id = db.Column(db.String(255), nullable=False, index=True)
    app_version = db.Column(db.String(64))          # e.g. "1.0 (1)"
    build_type = db.Column(db.String(32))           # debug / release
    device_model = db.Column(db.String(128))        # e.g. PHZ110
    android_version = db.Column(db.String(32))      # e.g. 16
    # crash facts
    reason = db.Column(db.String(64), nullable=False, index=True)  # CRASH_NATIVE / CRASH / ANR / ...
    reason_code = db.Column(db.Integer)             # ApplicationExitInfo.getReason()
    summary = db.Column(db.String(512))             # short description / abort message
    stacktrace = db.Column(db.Text)                 # full trace (may be large)
    occurred_at = db.Column(db.DateTime(timezone=True), index=True)  # when it crashed
    received_at = db.Column(db.DateTime(timezone=True), nullable=False,
                            default=datetime.utcnow, index=True)
    fingerprint = db.Column(db.String(64), index=True)  # sha256 of reason+summary for grouping

    def to_dict(self, include_trace=True):
        d = {
            'id': self.id,
            'source_device': self.source_device_id,
            'app_version': self.app_version,
            'build_type': self.build_type,
            'device_model': self.device_model,
            'android_version': self.android_version,
            'reason': self.reason,
            'reason_code': self.reason_code,
            'summary': self.summary,
            'occurred_at': self.occurred_at.isoformat() if self.occurred_at else None,
            'received_at': self.received_at.isoformat() if self.received_at else None,
            'fingerprint': self.fingerprint,
        }
        if include_trace:
            d['stacktrace'] = self.stacktrace
        else:
            d['stacktrace_length'] = len(self.stacktrace or '')
        return d
