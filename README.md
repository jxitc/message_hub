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

2. **Start PostgreSQL**:
   ```bash
   docker-compose up -d postgres
   ```

3. **Setup Python environment**:
   ```bash
   python -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   pip install -r requirements.txt
   ```

4. **Run the application**:
   ```bash
   export FLASK_ENV=development
   flask run
   ```

The server will be available at http://localhost:5000

### Docker Development

```bash
docker-compose up
```

## API Endpoints

- `GET /health` - Health check
- `GET /api/v1/messages` - List messages
- `POST /api/v1/messages` - Create message
- `GET /api/v1/devices` - List devices
- `POST /api/v1/devices/register` - Register device

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