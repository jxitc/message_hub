#!/usr/bin/env python3
"""
Message Hub CLI - Command line interface for the Message Hub Server
"""

import click
import requests
import json
import os
from datetime import datetime, timezone
from pathlib import Path

# Configuration
DEFAULT_SERVER_URL = "http://127.0.0.1:5001"
CONFIG_DIR = Path.home() / ".message-hub"
CONFIG_FILE = CONFIG_DIR / "config.json"

class Config:
    def __init__(self):
        self.server_url = DEFAULT_SERVER_URL
        self.api_key = os.environ.get('MH_CLI_API_KEY') or None
        self.load_config()
    
    def load_config(self):
        """Load configuration from file"""
        if CONFIG_FILE.exists():
            try:
                with open(CONFIG_FILE, 'r') as f:
                    config_data = json.load(f)
                    self.server_url = config_data.get('server_url', DEFAULT_SERVER_URL)
                    # config file api_key only applies if env did not provide one
                    if not self.api_key:
                        self.api_key = config_data.get('api_key') or None
            except (json.JSONDecodeError, IOError) as e:
                click.echo(f"Warning: Could not load config: {e}", err=True)
    
    def save_config(self):
        """Save configuration to file"""
        CONFIG_DIR.mkdir(exist_ok=True)
        try:
            with open(CONFIG_FILE, 'w') as f:
                json.dump({
                    'server_url': self.server_url,
                    'api_key': self.api_key
                }, f, indent=2)
        except IOError as e:
            click.echo(f"Warning: Could not save config: {e}", err=True)

# Global config instance
config = Config()

def _headers():
    """Headers for MH API requests: attach X-API-Key when configured."""
    headers = {'Accept': 'application/json'}
    if config.api_key:
        headers['X-API-Key'] = config.api_key
    return headers

def make_request(endpoint, method='GET', data=None, params=None):
    """Make HTTP request to the server"""
    url = f"{config.server_url}{endpoint}"
    headers = _headers()
    
    try:
        if method == 'GET':
            response = requests.get(url, params=params, headers=headers, timeout=10)
        elif method == 'POST':
            response = requests.post(url, json=data, headers=headers, timeout=10)
        elif method == 'PUT':
            response = requests.put(url, json=data, headers=headers, timeout=10)
        elif method == 'DELETE':
            response = requests.delete(url, json=data, headers=headers, timeout=10)
        else:
            raise ValueError(f"Unsupported method: {method}")
        
        return response
    
    except requests.exceptions.ConnectionError:
        click.echo(f"❌ Error: Could not connect to server at {config.server_url}", err=True)
        click.echo("   Make sure the Message Hub server is running", err=True)
        return None
    except requests.exceptions.Timeout:
        click.echo("❌ Error: Request timed out", err=True)
        return None
    except Exception as e:
        click.echo(f"❌ Error: {str(e)}", err=True)
        return None

def format_timestamp(timestamp_str):
    """Format timestamp for display"""
    if not timestamp_str:
        return "Unknown"
    
    try:
        # Parse ISO timestamp
        dt = datetime.fromisoformat(timestamp_str.replace('Z', '+00:00'))
        # Convert to local timezone for display
        local_dt = dt.astimezone()
        return local_dt.strftime("%Y-%m-%d %H:%M:%S")
    except Exception:
        return timestamp_str

def format_message(message, verbose=False):
    """Format message for display"""
    msg_id = message.get('id', 'Unknown')[:8]  # Short ID
    msg_type = message.get('type', 'Unknown')
    sender = message.get('sender', 'Unknown')
    content = message.get('content', '')
    timestamp = format_timestamp(message.get('timestamp'))
    device = message.get('source_device', 'Unknown')
    
    # Truncate content for non-verbose mode
    if not verbose and len(content) > 50:
        content = content[:47] + "..."
    
    if verbose:
        click.echo(f"[{msg_id}] {msg_type}")
        click.echo(f"   From: {sender}")
        recipients = (message.get('metadata') or {}).get('recipients')
        if recipients:
            click.echo(f"   To: {', '.join(recipients)}")
        click.echo(f"   Device: {device}")
        click.echo(f"   Time: {timestamp}")
        click.echo(f"   Content: {content}")
        click.echo()
    else:
        click.echo(f"[{msg_id}] {msg_type:15} {sender:20} {content}")

@click.group()
@click.option('--server', '-s', help='Message Hub server URL')
@click.option('--api-key', '-k', help='Message Hub API key (or set MH_CLI_API_KEY / config-set)')
@click.version_option(version='1.0.0', prog_name='message-hub')
def cli(server, api_key):
    """Message Hub CLI - Command line interface for the Message Hub Server"""
    if server:
        config.server_url = server
    if api_key:
        config.api_key = api_key

@cli.command()
@click.option('--limit', '-l', default=10, help='Number of messages to show')
@click.option('--type', '-t', help='Filter by message type (SMS, PUSH_NOTIFICATION, EMAIL, CALL_LOG)')
@click.option('--device', '-d', help='Filter by source device')
@click.option('--recipient', '-r', help='Filter email by recipient address (as delivered, e.g. jxitc@hotmail.com)')
@click.option('--since', help='Only messages at/after this date (YYYY-MM-DD or ISO 8601)')
@click.option('--until', help='Only messages at/before this date (YYYY-MM-DD = whole day)')
@click.option('--verbose', '-v', is_flag=True, help='Show detailed message information')
def messages(limit, type, device, recipient, since, until, verbose):
    """List messages from the hub"""
    
    # Build query parameters
    params = {'per_page': limit, 'page': 1}
    if type:
        params['type'] = type
    if device:
        params['device'] = device
    if recipient:
        params['recipient'] = recipient
    if since:
        params['since'] = since
    if until:
        params['until'] = until
    
    response = make_request('/api/v1/messages', params=params)
    if not response:
        return
    
    if response.status_code != 200:
        click.echo(f"❌ Error getting messages: {response.status_code}", err=True)
        try:
            error_data = response.json()
            error_msg = error_data.get('error', 'Unknown error')
            click.echo(f"   Server error: {error_msg}", err=True)
            
            if response.status_code == 500:
                click.echo("   💡 Try running: python init_db.py (on server)", err=True)
        except:
            click.echo(f"   Raw response: {response.text}", err=True)
        return
    
    data = response.json()
    messages = data.get('messages', [])
    total = data.get('total', 0)
    
    if not messages:
        click.echo("📭 No messages found")
        return
    
    # Display header
    if verbose:
        click.echo(f"📬 Found {len(messages)} messages (total: {total})")
        click.echo("=" * 60)
    else:
        click.echo(f"📬 Messages (showing {len(messages)} of {total}):")
        click.echo(f"{'ID':<10} {'Type':<15} {'Sender':<20} {'Content'}")
        click.echo("-" * 80)
    
    # Display messages
    for message in messages:
        format_message(message, verbose)


@cli.command()
@click.option('--id', 'ids', multiple=True, help='Delete this message id (repeatable)')
@click.option('--type', '-t', help='Delete messages of this type')
@click.option('--device', '-d', help='Delete messages from this device')
@click.option('--recipient', '-r', help='Delete email delivered to this address')
@click.option('--since', help='Delete messages at/after this date')
@click.option('--until', help='Delete messages at/before this date')
@click.option('--yes', is_flag=True, help='Actually delete; without it this is a dry run')
def delete(ids, type, device, recipient, since, until, yes):
    """Delete messages by id, or by filter (dry run unless --yes)

    Deletion is permanent — there is no trash. Filter deletes always show the
    count first; add --yes to carry them out.
    """
    if ids:
        response = make_request('/api/v1/messages', method='DELETE', data={'ids': list(ids)})
        if not response:
            return
        if response.status_code != 200:
            click.echo(f"❌ Delete failed: {response.status_code} {response.text}", err=True)
            return
        result = response.json()
        click.echo(f"🗑️  Deleted {result.get('deleted', 0)} of {result.get('requested', 0)} requested")
        return

    filters = {}
    for key, value in (('type', type), ('device', device), ('recipient', recipient),
                       ('since', since), ('until', until)):
        if value:
            filters[key] = value
    if not filters:
        click.echo("❌ Give either --id, or at least one filter (--type/--device/--recipient/--since/--until).", err=True)
        click.echo("   Refusing to run unfiltered: that would delete every message.", err=True)
        return

    payload = dict(filters)
    payload['dry_run'] = not yes
    response = make_request('/api/v1/messages/delete', method='POST', data=payload)
    if not response:
        return
    if response.status_code != 200:
        click.echo(f"❌ Delete failed: {response.status_code} {response.text}", err=True)
        return

    result = response.json()
    summary = result.get('filters', ', '.join(f'{k}={v}' for k, v in filters.items()))
    if result.get('dry_run'):
        click.echo(f"🔎 {result.get('would_delete', 0)} messages match [{summary}] — nothing deleted.")
        click.echo("   Re-run with --yes to delete them.")
    else:
        click.echo(f"🗑️  Deleted {result.get('deleted', 0)} messages matching [{summary}]")


@cli.command()
@click.option('--verbose', '-v', is_flag=True, help='Show detailed error information')
def status(verbose):
    """Show server status and statistics"""
    
    # Get health status
    health_response = make_request('/health')
    if not health_response:
        return
    
    if health_response.status_code != 200:
        click.echo(f"❌ Server unhealthy: {health_response.status_code}", err=True)
        if verbose:
            try:
                error_data = health_response.json()
                click.echo(f"   Error details: {error_data}", err=True)
            except:
                click.echo(f"   Raw response: {health_response.text}", err=True)
        return
    
    # Get sync status
    sync_response = make_request('/api/v1/sync/status')
    if not sync_response:
        return
    
    if sync_response.status_code != 200:
        click.echo(f"❌ Could not get sync status: {sync_response.status_code}", err=True)
        
        # Show detailed error information
        try:
            error_data = sync_response.json()
            error_msg = error_data.get('error', 'Unknown error')
            click.echo(f"   Server error: {error_msg}", err=True)
            
            if verbose or sync_response.status_code == 500:
                click.echo(f"   Full response: {sync_response.text}", err=True)
                
            # Provide helpful hints for common errors
            if sync_response.status_code == 500:
                click.echo("   💡 Common causes:", err=True)
                click.echo("      - Database not initialized (run: python init_db.py)", err=True)
                click.echo("      - Missing dependencies (run: pip install -r requirements.txt)", err=True)
                click.echo("      - Server configuration issues", err=True)
        except:
            click.echo(f"   Raw response: {sync_response.text}", err=True)
        return
    
    sync_data = sync_response.json()
    
    click.echo("🚀 Message Hub Server Status")
    click.echo("=" * 40)
    click.echo(f"Server URL: {config.server_url}")
    click.echo(f"Status: ✅ Healthy")
    click.echo(f"Total Messages: {sync_data.get('total_messages', 0)}")
    click.echo(f"Latest Message: {format_timestamp(sync_data.get('latest_timestamp'))}")
    
    device_stats = sync_data.get('device_stats', {})
    if device_stats:
        click.echo(f"\n📱 Messages by Device:")
        for device, count in device_stats.items():
            click.echo(f"  {device}: {count} messages")

@cli.command()
def sync():
    """Perform a delta sync to show new messages"""
    
    # Get sync status first
    status_response = make_request('/api/v1/sync/status')
    if not status_response or status_response.status_code != 200:
        click.echo("❌ Could not get sync status", err=True)
        return
    
    status_data = status_response.json()
    latest_timestamp = status_data.get('latest_timestamp')
    
    if not latest_timestamp:
        click.echo("📭 No messages to sync")
        return
    
    # Perform sync - get messages from 1 hour ago to now
    from datetime import timedelta
    one_hour_ago = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat()
    
    params = {'since': one_hour_ago, 'limit': 20}
    sync_response = make_request('/api/v1/sync/messages', params=params)
    
    if not sync_response or sync_response.status_code != 200:
        click.echo("❌ Sync failed", err=True)
        return
    
    sync_data = sync_response.json()
    messages = sync_data.get('messages', [])
    sync_info = sync_data.get('sync_info', {})
    
    click.echo(f"🔄 Delta Sync Results")
    click.echo(f"Since: {one_hour_ago}")
    click.echo(f"Found: {sync_info.get('returned', 0)} new messages")
    click.echo(f"Has more: {sync_data.get('has_more', False)}")
    
    if messages:
        click.echo("\n📬 Recent Messages:")
        click.echo("-" * 50)
        for message in messages[-5:]:  # Show last 5
            format_message(message, verbose=False)

@cli.command('config-set')
@click.option('--server-url', default=None, help='Message Hub server URL (default: keep current)')
@click.option('--api-key', default=None, help='API key (default: keep current)')
def config_set(server_url, api_key):
    """Configure CLI settings (server URL and API key)"""
    
    if server_url:
        config.server_url = server_url
    if api_key:
        config.api_key = api_key
    config.save_config()
    
    click.echo(f"✅ Configuration saved:")
    click.echo(f"   Server URL: {config.server_url}")
    click.echo(f"   API key: {'<set>' if config.api_key else '(none)'}")
    click.echo(f"   Config file: {CONFIG_FILE}")

@cli.command('config-show')
def config_show():
    """Show current configuration"""
    
    click.echo("⚙️  Current Configuration:")
    click.echo(f"   Server URL: {config.server_url}")
    click.echo(f"   API key: {'<set>' if config.api_key else '(none)'}")
    click.echo(f"   Config file: {CONFIG_FILE}")
    
    if CONFIG_FILE.exists():
        click.echo("   ✅ Config file exists")
    else:
        click.echo("   ⚠️  Config file not found (using defaults)")

@cli.command()
def test():
    """Test connectivity to the server"""
    
    click.echo(f"🔍 Testing connection to: {config.server_url}")
    
    # Test basic connectivity
    response = make_request('/health')
    if not response:
        click.echo("❌ Cannot connect to server")
        return
    
    if response.status_code == 200:
        click.echo("✅ Health endpoint working")
        health_data = response.json()
        click.echo(f"   Service: {health_data.get('service', 'Unknown')}")
        click.echo(f"   Status: {health_data.get('status', 'Unknown')}")
    else:
        click.echo(f"❌ Health endpoint failed: {response.status_code}")
        return
    
    # Test sync status
    sync_response = make_request('/api/v1/sync/status')
    if sync_response and sync_response.status_code == 200:
        click.echo("✅ Sync status endpoint working")
        sync_data = sync_response.json()
        click.echo(f"   Total messages: {sync_data.get('total_messages', 0)}")
    else:
        click.echo("❌ Sync status endpoint failed")
        if sync_response:
            click.echo(f"   Status code: {sync_response.status_code}")
            try:
                error_data = sync_response.json()
                click.echo(f"   Error: {error_data.get('error', 'Unknown')}")
            except:
                click.echo(f"   Raw response: {sync_response.text}")
    
    # Test messages endpoint
    messages_response = make_request('/api/v1/messages', params={'per_page': 1})
    if messages_response and messages_response.status_code == 200:
        click.echo("✅ Messages endpoint working")
    else:
        click.echo("❌ Messages endpoint failed")
        if messages_response:
            click.echo(f"   Status code: {messages_response.status_code}")
    
    click.echo("\n🎯 Connection test complete!")

if __name__ == '__main__':
    cli()