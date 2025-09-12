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
        self.load_config()
    
    def load_config(self):
        """Load configuration from file"""
        if CONFIG_FILE.exists():
            try:
                with open(CONFIG_FILE, 'r') as f:
                    config_data = json.load(f)
                    self.server_url = config_data.get('server_url', DEFAULT_SERVER_URL)
            except (json.JSONDecodeError, IOError) as e:
                click.echo(f"Warning: Could not load config: {e}", err=True)
    
    def save_config(self):
        """Save configuration to file"""
        CONFIG_DIR.mkdir(exist_ok=True)
        try:
            with open(CONFIG_FILE, 'w') as f:
                json.dump({
                    'server_url': self.server_url
                }, f, indent=2)
        except IOError as e:
            click.echo(f"Warning: Could not save config: {e}", err=True)

# Global config instance
config = Config()

def make_request(endpoint, method='GET', data=None, params=None):
    """Make HTTP request to the server"""
    url = f"{config.server_url}{endpoint}"
    
    try:
        if method == 'GET':
            response = requests.get(url, params=params, timeout=10)
        elif method == 'POST':
            response = requests.post(url, json=data, timeout=10)
        elif method == 'PUT':
            response = requests.put(url, json=data, timeout=10)
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
    is_read = message.get('is_read', False)
    device = message.get('source_device', 'Unknown')
    
    # Truncate content for non-verbose mode
    if not verbose and len(content) > 50:
        content = content[:47] + "..."
    
    read_status = "✓" if is_read else "○"
    
    if verbose:
        click.echo(f"{read_status} [{msg_id}] {msg_type}")
        click.echo(f"   From: {sender}")
        click.echo(f"   Device: {device}")
        click.echo(f"   Time: {timestamp}")
        click.echo(f"   Content: {content}")
        click.echo()
    else:
        click.echo(f"{read_status} [{msg_id}] {msg_type:15} {sender:20} {content}")

@click.group()
@click.option('--server', '-s', help='Message Hub server URL')
@click.version_option(version='1.0.0', prog_name='message-hub')
def cli(server):
    """Message Hub CLI - Command line interface for the Message Hub Server"""
    if server:
        config.server_url = server

@cli.command()
@click.option('--limit', '-l', default=10, help='Number of messages to show')
@click.option('--type', '-t', help='Filter by message type (SMS, PUSH_NOTIFICATION, EMAIL, CALL_LOG)')
@click.option('--device', '-d', help='Filter by source device')
@click.option('--unread', is_flag=True, help='Show only unread messages')
@click.option('--verbose', '-v', is_flag=True, help='Show detailed message information')
def messages(limit, type, device, unread, verbose):
    """List messages from the hub"""
    
    # Build query parameters
    params = {'per_page': limit, 'page': 1}
    if type:
        params['type'] = type
    if device:
        params['device'] = device
    
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
    
    # Filter unread if requested
    if unread:
        messages = [msg for msg in messages if not msg.get('is_read', False)]
        if not messages:
            click.echo("📭 No unread messages found")
            return
    
    # Display header
    if verbose:
        click.echo(f"📬 Found {len(messages)} messages (total: {total})")
        click.echo("=" * 60)
    else:
        click.echo(f"📬 Messages (showing {len(messages)} of {total}):")
        click.echo(f"{'Status':<2} {'ID':<10} {'Type':<15} {'Sender':<20} {'Content'}")
        click.echo("-" * 80)
    
    # Display messages
    for message in messages:
        format_message(message, verbose)

@cli.command('mark-read')
@click.argument('message_id')
def mark_read(message_id):
    """Mark a message as read"""
    
    response = make_request(f'/api/v1/messages/{message_id}/read', method='PUT')
    if not response:
        return
    
    if response.status_code == 200:
        click.echo(f"✅ Message {message_id[:8]} marked as read")
    elif response.status_code == 404:
        click.echo(f"❌ Message {message_id} not found", err=True)
    else:
        click.echo(f"❌ Error: {response.status_code} - {response.text}", err=True)

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

@cli.command()
@click.option('--server-url', prompt='Server URL', default=DEFAULT_SERVER_URL)
def config_set(server_url):
    """Configure CLI settings"""
    
    config.server_url = server_url
    config.save_config()
    
    click.echo(f"✅ Configuration saved:")
    click.echo(f"   Server URL: {server_url}")
    click.echo(f"   Config file: {CONFIG_FILE}")

@cli.command('config-show')
def config_show():
    """Show current configuration"""
    
    click.echo("⚙️  Current Configuration:")
    click.echo(f"   Server URL: {config.server_url}")
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