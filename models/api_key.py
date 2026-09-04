from . import db
from datetime import datetime
import uuid

class ApiKey(db.Model):
    """A named API key (like a GitHub personal access token).

    Only the sha256 hash of the secret is stored — the plaintext is shown
    once at creation time and can never be retrieved again. Auth middleware
    hashes the incoming X-API-Key and looks it up here.
    """
    __tablename__ = 'api_keys'

    id = db.Column(db.String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    name = db.Column(db.String(255), nullable=False)       # human label, e.g. "phone" / "cli-laptop"
    key_hash = db.Column(db.String(64), unique=True, nullable=False)   # sha256 hex of the secret
    prefix = db.Column(db.String(16), nullable=False)      # first chars of key for display (mhk_ab12...)
    is_active = db.Column(db.Boolean, default=True)
    created_at = db.Column(db.DateTime(timezone=True), nullable=False, default=datetime.utcnow)
    last_used_at = db.Column(db.DateTime(timezone=True))

    def to_dict(self, include_hash=False):
        d = {
            'id': self.id,
            'name': self.name,
            'prefix': self.prefix,
            'is_active': self.is_active,
            'created_at': self.created_at.isoformat() if self.created_at else None,
            'last_used_at': self.last_used_at.isoformat() if self.last_used_at else None,
        }
        if include_hash:
            d['key_hash'] = self.key_hash
        return d
