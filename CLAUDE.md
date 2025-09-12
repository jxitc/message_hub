# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Message Hub Server is a centralized service for accepting forwarded messages from multiple devices (SMS, push notifications, etc.) and allowing clients to pull and read messages through a unified API.

## Architecture

### Core Components
- **Message Storage Hub**: Centralized storage for messages from various sources
- **Multi-Client Access**: RESTful API for clients to retrieve messages
- **Delta Sync**: Efficient synchronization using sequence-based or timestamp-based approaches
- **Device Management**: Registration and API key authentication system

### Database Schema
- **Messages Table**: Core message storage with UUID, source device, type, content, metadata
- **Devices Table**: Device registration with API keys and sync tracking
- Indexes on timestamp, sequence_id, source_device_id for performance

### Message Types Supported
- SMS
- PUSH_NOTIFICATION  
- CALL_LOG
- EMAIL

## Technology Stack

**Python + Flask** is the chosen implementation stack.

**Key Dependencies:**
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

## API Structure

### Core Endpoints
- `POST /api/v1/messages` - Forward new messages
- `GET /api/v1/messages` - Retrieve messages with delta sync
- `PUT /api/v1/messages/:id/read` - Mark messages as read
- `POST /api/v1/devices/register` - Device registration
- `GET /api/v1/sync/messages?since=timestamp` - Delta sync endpoint

### Authentication
API key based authentication via `Authorization: Bearer API_KEY` header (authentication is stretch goal for MVP per line 78).

## Key Implementation Notes

- **Delta Sync**: Timestamp-based approach preferred with merge/dedup for overlapping ranges
- **Timezone Handling**: Use ISO 8601 format with timezone info (Z for UTC, +/-HH:MM for local offset)  
- **Flexible Metadata**: Metadata is a flexible JSON object to accommodate different message sources
- **Authentication**: MVP skips authentication for faster development (stretch goal)

## CLI and Web Interface

### CLI Commands
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

### Web Interface Features
- Dashboard with recent messages overview and filtering
- Detailed message view with metadata
- Device management for registration and API keys
- Settings and configuration
- Full-text search across message content

## Future Features (Stretch Goals)
- Media forwarding support
- Real-time WebSocket updates
- Message search and analytics
- End-to-end encryption
- API key based authentication

## Development Workflow

### Local Development Setup
```bash
# Start PostgreSQL
docker-compose up -d postgres

# Setup Python environment
python -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
pip install -r requirements.txt

# Run development server
export FLASK_ENV=development
flask run
```

### Implementation Priority
1. Start with MVP implementation using Python + Flask
2. Create basic CRUD operations for messages
3. Implement device registration system
4. Add delta sync endpoint
5. Test integration with message forwarding clients
6. Add CLI and web interface
7. Deploy to staging environment

## Security Considerations

- Input validation for all message content
- HTTPS enforcement in production
- API rate limiting
- Optional PII filtering
- Audit logging for API access