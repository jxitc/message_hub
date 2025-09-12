# Message Hub Server - Design Document

## Overview
A centralized server that accepts forwarded messages from multiple devices and allows clients to pull and read the latest messages. This creates a unified message hub for SMS, push notifications, and other message types.

## Key Features
1. **Message Storage Hub** - Store forwarded messages from any source (SMS, push notifications, etc.)
2. **Multi-Client Access** - Allow other clients to pull and read messages
3. **Delta Sync** - Efficient synchronization between hub and clients
4. **Stretch Goal** - Support media forwarding in the future
5. **CLI and Web Interface** - Command-line tool and web dashboard for easy message access and management 

## Architecture Overview

### Message Schema
```json
{
  "id": "uuid",
  "source_device": "android-phone-1",
  "type": "SMS|PUSH_NOTIFICATION|CALL_LOG|EMAIL",
  "sender": "phone_number_or_app_name",
  "content": "message_text",
  "timestamp": "2024-01-01T12:00:00Z",  // ISO 8601 with timezone info (Z for UTC, or +/-HH:MM for local offset)
  "received_at": "2024-01-01T12:00:01Z",
  "metadata": {  // Flexible JSON object to accommodate different message sources and types
    "app_package": "com.whatsapp", // for push notifications
    "media_urls": [], // for future media support
    "location": {}, // optional location data
    "priority": "high|normal|low",
    "category": "personal|work|system"
  },
  "is_read": false,
  "tags": ["important", "family"]
}
```

### Database Tables

#### Messages Table
```sql
CREATE TABLE messages (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_device_id VARCHAR(255) NOT NULL,
  type VARCHAR(50) NOT NULL,
  sender VARCHAR(255) NOT NULL,
  content TEXT NOT NULL,
  timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
  received_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  metadata JSONB DEFAULT '{}',
  is_read BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  
  INDEX idx_messages_timestamp (timestamp DESC),
  INDEX idx_messages_source_device (source_device_id),
  INDEX idx_messages_type (type),
  INDEX idx_messages_received_at (received_at DESC)
);
```

#### Devices Table
```sql
CREATE TABLE devices (
  id VARCHAR(255) PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  type VARCHAR(50) NOT NULL, -- android, ios, web, api
  api_key VARCHAR(255) UNIQUE NOT NULL,
  last_sync_at TIMESTAMP WITH TIME ZONE,
  is_active BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

## API Design

### Authentication
// Note: Authentication is a stretch goal. MVP will skip authentication for faster development
- **API Key based** - Each device gets a unique API key  
- **Header**: `Authorization: Bearer API_KEY`
- **Device registration** required before message forwarding

### REST Endpoints

#### Message Operations
```
POST /api/v1/messages
GET /api/v1/messages
GET /api/v1/messages/:id
PUT /api/v1/messages/:id/read
DELETE /api/v1/messages/:id
```

#### Device Management
```
POST /api/v1/devices/register
GET /api/v1/devices
PUT /api/v1/devices/:id
DELETE /api/v1/devices/:id
```

#### Sync Operations
```
GET /api/v1/sync/messages?since=timestamp&limit=50
POST /api/v1/sync/acknowledge
```

### Delta Sync Strategy

#### Option 1: Timestamp-based
// Note: Timestamp-based sync preferred. Implementation must handle merge/dedup for overlapping time ranges
```
GET /api/v1/messages?since=2024-01-01T12:00:00Z&limit=50

Response:
{
  "messages": [...],
  "has_more": true,
  "last_timestamp": "2024-01-01T13:00:00Z",
  "total_count": 1250
}
```

#### Option 2: Sequence-based (Recommended)
```sql
ALTER TABLE messages ADD COLUMN sequence_id BIGSERIAL;
CREATE INDEX idx_messages_sequence_id ON messages(sequence_id);
```

```
GET /api/v1/messages?since_sequence=12345&limit=50

Response:
{
  "messages": [...],
  "has_more": true,
  "last_sequence_id": 12395,
  "total_count": 1250
}
```

## Technology Stack Options

### Option 1: Node.js + Express (Recommended for MVP)
**Pros:**
- Fast development
- Rich ecosystem
- Good TypeScript support
- Easy Docker deployment

**Dependencies:**
```json
{
  "express": "^4.18.0",
  "cors": "^2.8.5",
  "helmet": "^7.0.0",
  "pg": "^8.11.0",
  "joi": "^17.9.0",
  "uuid": "^9.0.0",
  "winston": "^3.10.0",
  "dotenv": "^16.3.0"
}
```

### Option 2: Go + Gin
**Pros:**
- High performance
- Single binary deployment
- Excellent concurrency
- Strong typing

### Chosen Stack: Python + Flask
**Pros:**
- Simple and lightweight framework
- Flexible and unopinionated
- Great ecosystem with SQLAlchemy, Marshmallow
- Easy to extend and customize
- Good performance for API workloads

**Dependencies:**
```python
Flask==2.3.3
Flask-SQLAlchemy==3.0.5
Flask-CORS==4.0.0
psycopg2-binary==2.9.7
marshmallow==3.20.1
uuid==1.30
werkzeug==2.3.7
python-dotenv==1.0.0
```
**Pros:**
- Rich ecosystem
- Auto-generated OpenAPI docs
- Great for data processing
- Easy ML integration later

## API Examples

### Forward Message
```http
POST /api/v1/messages
Authorization: Bearer device_api_key_here
Content-Type: application/json

{
  "type": "SMS",
  "sender": "+1234567890",
  "content": "Hello from SMS",
  "timestamp": "2024-01-01T12:00:00Z",
  "metadata": {
    "thread_id": "thread_123"
  }
}
```

### Get Delta Messages
```http
GET /api/v1/messages?since_sequence=12345&limit=20&device_filter=android-phone-1
Authorization: Bearer client_api_key_here

Response:
{
  "messages": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "source_device": "android-phone-1",
      "type": "SMS",
      "sender": "+1234567890",
      "content": "Hello from SMS",
      "timestamp": "2024-01-01T12:00:00Z",
      "sequence_id": 12346
    }
  ],
  "has_more": false,
  "last_sequence_id": 12346
}
```

### Mark as Read
```http
PUT /api/v1/messages/550e8400-e29b-41d4-a716-446655440000/read
Authorization: Bearer client_api_key_here
```

### UI Interface

### CLI Interface
```bash
# View recent messages
message-hub messages --limit 10

# Filter by device or type
message-hub messages --device android-phone-1 --type SMS

# Mark messages as read
message-hub mark-read --message-id uuid

# Device management
message-hub devices list
message-hub devices register --name "My Phone" --type android
```

### Web Interface
- **Dashboard**: Recent messages overview with filtering
- **Message View**: Detailed message content with metadata
- **Device Management**: Register/manage devices and API keys
- **Settings**: Configuration and preferences
- **Search**: Full-text search across message content

## Deployment Strategy

### Development
```bash
# Local development with Docker Compose
docker-compose up -d postgres
python -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
pip install -r requirements.txt
export FLASK_ENV=development
flask run
```

### Production Options
1. **Heroku** - Easy deployment with PostgreSQL addon
2. **Railway/Fly.io** - Modern alternatives to Heroku
3. **VPS + Docker** - Full control
4. **AWS/GCP** - Scalable cloud deployment

## Security Considerations

1. **API Rate Limiting** - Prevent abuse
2. **Input Validation** - Validate all message content
3. **HTTPS Only** - Enforce TLS in production
4. **API Key Rotation** - Support key rotation
5. **Content Filtering** - Optional PII filtering
6. **Audit Logging** - Track all API access

## Future Enhancements

1. **Real-time Updates** - WebSocket support for live updates
2. **Media Support** - File upload and storage
3. **Message Search** - Full-text search capabilities
4. **Analytics** - Message statistics and insights
5. **Multi-tenancy** - Support multiple organizations
6. **Webhooks** - Push notifications to clients
7. **Message Encryption** - End-to-end encryption
8. **Backup/Export** - Data export capabilities

## Getting Started

1. **Create new project folder**: `mkdir message-hub-server && cd message-hub-server`
2. **Choose tech stack** and initialize project
3. **Setup database** (PostgreSQL recommended)
4. **Implement core API endpoints**
5. **Add authentication**
6. **Test with Android client**
7. **Deploy to staging environment**

## Next Steps

1. Start with MVP implementation using Python + Flask
2. Create basic CRUD operations for messages
3. Implement device registration and API key authentication
4. Add delta sync endpoint
5. Test integration with existing Android SMS app
6. Add WebSocket support for real-time updates
7. Deploy to production environment

---

