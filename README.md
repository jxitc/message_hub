# Message Hub Server

A centralized server for accepting forwarded messages from multiple devices and allowing clients to pull and read messages through a unified API.

## Quick Start

### Development Setup

1. **Clone and setup**:
   ```bash
   git clone <repository-url>
   cd message-hub
   cp .env.example .env
   ```

2. **Setup Python environment**:
   ```bash
   python3 -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   pip install -r requirements.txt
   ```

3. **Run the application**:
   ```bash
   export FLASK_ENV=development
   python app.py
   ```

The server will be available at http://localhost:5001

### Testing the Server

Once the server is running, you can test the endpoints:

1. **Health Check**:
   ```bash
   curl http://127.0.0.1:5001/health
   ```
   Expected response: `{"status":"healthy","service":"message-hub"}`

2. **API Info**:
   ```bash
   curl http://127.0.0.1:5001/
   ```
   Shows available endpoints and service information

3. **Test API Endpoints**:
   ```bash
   # List messages (currently returns empty array)
   curl http://127.0.0.1:5001/api/v1/messages
   
   # List devices (currently returns empty array)
   curl http://127.0.0.1:5001/api/v1/devices
   
   # Sync messages (placeholder response)
   curl http://127.0.0.1:5001/api/v1/sync/messages
   ```

### Alternative: Using Docker

```bash
docker-compose up postgres  # Only PostgreSQL (not needed for SQLite)
```

**Note:** The application currently uses SQLite instead of PostgreSQL for simpler development setup.

## API Endpoints

- `GET /health` - Health check
- `GET /` - Service information and available endpoints
- `GET /api/v1/messages` - List messages (placeholder)
- `POST /api/v1/messages` - Create message (placeholder)
- `GET /api/v1/messages/:id` - Get single message (placeholder)
- `PUT /api/v1/messages/:id/read` - Mark message as read (placeholder)
- `GET /api/v1/devices` - List devices (placeholder)
- `POST /api/v1/devices/register` - Register device (placeholder)
- `GET /api/v1/sync/messages` - Delta sync messages (placeholder)

## Project Structure

```
message-hub/
├── app.py              # Flask application factory
├── config.py           # Configuration
├── api/                # API routes
│   └── v1/            # API version 1
├── models/            # Database models
├── docs/              # Documentation
└── requirements.txt   # Dependencies
```

## Documentation

See `/docs/` folder for:
- Design document
- Task breakdown
- Development guidelines