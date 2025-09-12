from . import db
from datetime import datetime
import uuid

class Message(db.Model):
    __tablename__ = 'messages'
    
    id = db.Column(db.String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    source_device_id = db.Column(db.String(255), nullable=False)
    type = db.Column(db.String(50), nullable=False)
    sender = db.Column(db.String(255), nullable=False)
    content = db.Column(db.Text, nullable=False)
    timestamp = db.Column(db.DateTime(timezone=True), nullable=False)
    received_at = db.Column(db.DateTime(timezone=True), nullable=False, default=datetime.utcnow)
    metadata = db.Column(db.JSON, default={})
    is_read = db.Column(db.Boolean, default=False)
    created_at = db.Column(db.DateTime(timezone=True), nullable=False, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime(timezone=True), nullable=False, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    def to_dict(self):
        return {
            'id': self.id,
            'source_device': self.source_device_id,
            'type': self.type,
            'sender': self.sender,
            'content': self.content,
            'timestamp': self.timestamp.isoformat() if self.timestamp else None,
            'received_at': self.received_at.isoformat() if self.received_at else None,
            'metadata': self.metadata or {},
            'is_read': self.is_read
        }