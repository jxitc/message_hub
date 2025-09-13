# Message Hub Server

A centralized server for accepting forwarded messages from multiple devices and allowing clients to pull and read messages through a unified API and web interface.

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

3. **Initialize the database**:
   ```bash
   python init_db.py
   ```

4. **Run the application**:
   ```bash
   export FLASK_ENV=development
   python app.py
   ```

The server will be available at:
- **Web Interface**: http://localhost:5001 (opens dashboard in browser)  
- **API Endpoints**: http://localhost:5001/api/v1/
- **Health Check**: http://localhost:5001/health

### Testing the Server

#### 1. Install/Update Dependencies
```bash
source venv/bin/activate
pip install -r requirements.txt  # Includes all dependencies (requests, dateutil, click, etc.)
```

**Note:** If you get import errors after pulling updates, make sure to run `pip install -r requirements.txt` to install new dependencies.

#### 2. Initialize Database with Sample Data
```bash
python init_db.py
```
This creates the database tables and adds sample messages and devices for testing.

#### 3. Quick Health Check
```bash
curl http://127.0.0.1:5001/health
```
Expected response: `{"status":"healthy","service":"message-hub"}`

#### 4. Automated API Testing
```bash
python test_api.py
```
Runs comprehensive tests of all API endpoints including:
- Device registration and listing
- Message creation, retrieval, and filtering
- Marking messages as read
- Pagination testing

**Note:** Test data is NOT deleted - it remains in the database for inspection.

#### 5. Manual API Testing

**Register a Device:**
```bash
curl -X POST http://127.0.0.1:5001/api/v1/devices/register \
  -H "Content-Type: application/json" \
  -d '{
    "id": "my-phone",
    "name": "My Test Phone", 
    "type": "android"
  }'
```

**Create a Message:**
```bash
curl -X POST http://127.0.0.1:5001/api/v1/messages \
  -H "Content-Type: application/json" \
  -d '{
    "source_device_id": "my-phone",
    "type": "SMS",
    "sender": "+1234567890",
    "content": "Hello World!",
    "timestamp": "2024-01-01T12:00:00Z",
    "metadata": {"priority": "normal"}
  }'
```

**List Messages:**
```bash
# All messages
curl http://127.0.0.1:5001/api/v1/messages

# Filter by type
curl http://127.0.0.1:5001/api/v1/messages?type=SMS

# Filter by device
curl http://127.0.0.1:5001/api/v1/messages?device=my-phone

# Pagination
curl http://127.0.0.1:5001/api/v1/messages?page=1&per_page=10
```

**Mark Message as Read:**
```bash
curl -X PUT http://127.0.0.1:5001/api/v1/messages/{message_id}/read
```

**Delta Sync (Efficient Synchronization):**
```bash
# Get sync status
curl http://127.0.0.1:5001/api/v1/sync/status

# Full sync (all messages)  
curl http://127.0.0.1:5001/api/v1/sync/messages?limit=50

# Delta sync (only new messages since timestamp)
curl http://127.0.0.1:5001/api/v1/sync/messages?since=2024-01-01T12:00:00Z&limit=50

# Filtered sync
curl http://127.0.0.1:5001/api/v1/sync/messages?device=my-phone&type=SMS&limit=50
```

**Specialized Testing:**
```bash
# Test delta sync functionality
python test_sync.py

# Test performance with large datasets
python test_performance.py

# Test CLI functionality
python test_cli.py
```

## Web Interface

The Message Hub includes a modern web interface that mirrors all CLI functionality:

### Accessing the Web Interface

1. **Start the server** (same command as for API):
   ```bash
   source venv/bin/activate
   python app.py
   ```

2. **Open in browser**: http://localhost:5001

### Web Interface Features

- **Dashboard** (`/dashboard`): Message overview, statistics, and recent messages
- **Messages** (`/messages`): List all messages with filtering and pagination  
- **Message Details** (`/messages/<id>`): View complete message content and metadata
- **Status** (`/status`): Server health monitoring and system statistics

### Web Interface Capabilities

The web interface provides the same functionality as the CLI:

| CLI Command | Web Equivalent | Description |
|-------------|----------------|-------------|
| `./message-hub status` | `/status` | Server health and statistics |
| `./message-hub messages --limit 10` | `/messages` | List messages with filtering |
| `./message-hub messages --type SMS` | `/messages?type=SMS` | Filter by message type |
| `./message-hub messages --unread` | `/messages?unread=on` | Show only unread messages |
| `./message-hub mark-read <id>` | Click "Mark as Read" button | Mark messages as read |

### Configuration

The web interface uses the same server configuration:
- **Default URL**: http://localhost:5001
- **Port**: 5001 (same as API)
- **Database**: Same SQLite database as CLI/API

**Note**: The web interface runs on the **same server** as the API - no separate server needed.

## CLI Usage

The Message Hub includes a command-line interface for easy access:

```bash
# Make CLI executable (first time only)
chmod +x message-hub

# Show help
./message-hub --help

# Test server connectivity
./message-hub test

# Show server status
./message-hub status

# List messages
./message-hub messages --limit 10

# List messages with filters
./message-hub messages --type SMS --limit 5
./message-hub messages --device android-phone-1 --verbose
./message-hub messages --unread

# Mark message as read
./message-hub mark-read <message-id>

# Perform delta sync
./message-hub sync

# Configure CLI
./message-hub config-set --server-url http://your-server:5001
./message-hub config-show
```

### Alternative: Using Docker

```bash
docker-compose up postgres  # Only PostgreSQL (not needed for SQLite)
```

**Note:** The application currently uses SQLite instead of PostgreSQL for simpler development setup.

## Endpoints

### Web Interface Routes
- `GET /` - Redirects to dashboard (or JSON for API clients)
- `GET /dashboard` - Web dashboard with message overview
- `GET /messages` - Web interface for listing messages
- `GET /messages/<id>` - Web interface for message details  
- `GET /status` - Web interface for server status
- `POST /messages/<id>/read` - Mark message as read (web)

### API Endpoints
- `GET /health` - Health check
- `GET /api/v1/messages` - List messages with pagination and filtering
- `POST /api/v1/messages` - Create/forward new message
- `GET /api/v1/messages/:id` - Get single message by ID
- `PUT /api/v1/messages/:id/read` - Mark message as read
- `GET /api/v1/devices` - List registered devices
- `POST /api/v1/devices/register` - Register new device with API key
- `GET /api/v1/sync/messages` - Delta sync messages with timestamp-based filtering
- `GET /api/v1/sync/status` - Get sync status and statistics

## Project Structure

```
message-hub/
├── app.py              # Flask application factory
├── config.py           # Configuration
├── api/                # API routes
│   └── v1/            # API version 1 endpoints
├── web/                # Web interface routes and views
├── models/            # Database models (SQLAlchemy)
├── templates/         # HTML templates (Jinja2)
├── static/            # CSS, JavaScript, and static files
├── cli/               # Command-line interface
├── schemas/           # Data validation schemas
├── docs/              # Documentation
├── init_db.py         # Database initialization
├── test_*.py          # Test scripts
└── requirements.txt   # Dependencies
```

## Documentation

See `/docs/` folder for:
- Design document
- Task breakdown
- Development guidelines