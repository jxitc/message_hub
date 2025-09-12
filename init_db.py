from app import create_app
from models import db, Message, Device
from datetime import datetime, timezone
import uuid

def init_database():
    app = create_app()
    
    with app.app_context():
        # Create all tables
        db.create_all()
        
        # Create sample devices
        device1 = Device(
            id='android-phone-1',
            name='John\'s Android Phone',
            type='android',
            api_key='dev-key-android-phone-1',
            is_active=True
        )
        
        device2 = Device(
            id='iphone-1',
            name='Jane\'s iPhone',
            type='ios',
            api_key='dev-key-iphone-1',
            is_active=True
        )
        
        # Create sample messages
        message1 = Message(
            source_device_id='android-phone-1',
            type='SMS',
            sender='+1234567890',
            content='Hello from SMS! This is a test message.',
            timestamp=datetime.now(timezone.utc),
            message_metadata={
                'thread_id': 'thread_123',
                'priority': 'normal'
            }
        )
        
        message2 = Message(
            source_device_id='android-phone-1',
            type='PUSH_NOTIFICATION',
            sender='WhatsApp',
            content='New message from Alice: Hey, are we still meeting today?',
            timestamp=datetime.now(timezone.utc),
            message_metadata={
                'app_package': 'com.whatsapp',
                'priority': 'high',
                'category': 'personal'
            }
        )
        
        message3 = Message(
            source_device_id='iphone-1',
            type='EMAIL',
            sender='team@company.com',
            content='Weekly team meeting scheduled for tomorrow at 2 PM',
            timestamp=datetime.now(timezone.utc),
            is_read=True,
            message_metadata={
                'subject': 'Weekly Team Meeting',
                'category': 'work'
            }
        )
        
        # Add to session and commit
        db.session.add_all([device1, device2, message1, message2, message3])
        db.session.commit()
        
        print("Database initialized successfully!")
        print(f"Created {Device.query.count()} devices")
        print(f"Created {Message.query.count()} messages")

if __name__ == '__main__':
    init_database()