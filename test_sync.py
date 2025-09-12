#!/usr/bin/env python3
"""
Test script for Delta Sync functionality
Tests timestamp-based synchronization with overlapping ranges
"""

import requests
import json
import time
from datetime import datetime, timezone, timedelta

# Configuration
BASE_URL = "http://127.0.0.1:5001"
# BASE_URL = "http://188.166.172.192:5001"  # Use this for remote testing

def test_sync_status():
    """Test sync status endpoint"""
    print("📊 Testing sync status...")
    response = requests.get(f"{BASE_URL}/api/v1/sync/status")
    print(f"Status: {response.status_code}")
    if response.status_code == 200:
        data = response.json()
        print(f"Total messages: {data.get('total_messages', 0)}")
        print(f"Latest timestamp: {data.get('latest_timestamp', 'None')}")
        print(f"Device stats: {data.get('device_stats', {})}")
        print(f"Server time: {data.get('server_time', 'Unknown')}")
        return data.get('latest_timestamp')
    print()
    return None

def test_full_sync():
    """Test full sync (no since parameter)"""
    print("🔄 Testing full sync (all messages)...")
    response = requests.get(f"{BASE_URL}/api/v1/sync/messages?limit=10")
    print(f"Status: {response.status_code}")
    if response.status_code == 200:
        data = response.json()
        print(f"Returned: {data['sync_info']['returned']} messages")
        print(f"Total available: {data.get('total_count', 0)}")
        print(f"Has more: {data.get('has_more', False)}")
        print(f"Last timestamp: {data.get('last_timestamp', 'None')}")
        return data.get('last_timestamp')
    print()
    return None

def test_delta_sync(since_timestamp):
    """Test delta sync with since parameter"""
    if not since_timestamp:
        print("⚠️  Skipping delta sync test (no timestamp)")
        return
        
    print(f"📅 Testing delta sync since: {since_timestamp}")
    response = requests.get(f"{BASE_URL}/api/v1/sync/messages?since={since_timestamp}&limit=5")
    print(f"Status: {response.status_code}")
    if response.status_code == 200:
        data = response.json()
        print(f"New messages since timestamp: {data['sync_info']['returned']}")
        print(f"Has more: {data.get('has_more', False)}")
        print(f"New last timestamp: {data.get('last_timestamp', 'None')}")
    print()

def test_filtered_sync():
    """Test sync with device and type filters"""
    print("🔍 Testing filtered sync...")
    
    # Test device filter
    response = requests.get(f"{BASE_URL}/api/v1/sync/messages?device=android-phone-1&limit=5")
    if response.status_code == 200:
        data = response.json()
        print(f"Android phone messages: {data['sync_info']['returned']}")
    
    # Test type filter  
    response = requests.get(f"{BASE_URL}/api/v1/sync/messages?type=SMS&limit=5")
    if response.status_code == 200:
        data = response.json()
        print(f"SMS messages: {data['sync_info']['returned']}")
    
    # Test combined filters
    response = requests.get(f"{BASE_URL}/api/v1/sync/messages?device=android-phone-1&type=SMS&limit=5")
    if response.status_code == 200:
        data = response.json()
        print(f"Android SMS messages: {data['sync_info']['returned']}")
    print()

def test_pagination():
    """Test sync pagination"""
    print("📄 Testing sync pagination...")
    
    # Get first page
    response = requests.get(f"{BASE_URL}/api/v1/sync/messages?limit=2")
    if response.status_code == 200:
        data = response.json()
        print(f"Page 1: {data['sync_info']['returned']} messages, has_more: {data.get('has_more')}")
        
        if data.get('has_more') and data.get('last_timestamp'):
            # Get next page using last timestamp
            next_response = requests.get(f"{BASE_URL}/api/v1/sync/messages?since={data['last_timestamp']}&limit=2")
            if next_response.status_code == 200:
                next_data = next_response.json()
                print(f"Page 2: {next_data['sync_info']['returned']} messages")
    print()

def test_overlapping_ranges():
    """Test sync with overlapping time ranges (deduplication)"""
    print("🔀 Testing overlapping sync ranges...")
    
    # Get sync status to get a reference timestamp
    status_response = requests.get(f"{BASE_URL}/api/v1/sync/status")
    if status_response.status_code != 200:
        print("❌ Could not get sync status")
        return
    
    # Create a timestamp 1 hour ago
    one_hour_ago = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat()
    
    # First sync request
    response1 = requests.get(f"{BASE_URL}/api/v1/sync/messages?since={one_hour_ago}&limit=3")
    if response1.status_code == 200:
        data1 = response1.json()
        print(f"Range 1: {data1['sync_info']['returned']} messages")
        
        if data1.get('last_timestamp'):
            # Overlapping sync request (goes back 30 minutes from last timestamp)
            last_time = datetime.fromisoformat(data1['last_timestamp'].replace('Z', '+00:00'))
            overlap_time = (last_time - timedelta(minutes=30)).isoformat()
            
            response2 = requests.get(f"{BASE_URL}/api/v1/sync/messages?since={overlap_time}&limit=5")
            if response2.status_code == 200:
                data2 = response2.json()
                print(f"Range 2 (overlapping): {data2['sync_info']['returned']} messages")
                print("✅ Overlapping ranges handled successfully")
    print()

def test_invalid_timestamp():
    """Test sync with invalid timestamp format"""
    print("❌ Testing invalid timestamp handling...")
    
    response = requests.get(f"{BASE_URL}/api/v1/sync/messages?since=invalid-timestamp")
    print(f"Status: {response.status_code}")
    if response.status_code == 400:
        error_data = response.json()
        print(f"Error message: {error_data.get('error', 'No error message')}")
        print("✅ Invalid timestamp properly rejected")
    print()

def create_test_messages():
    """Create some test messages for sync testing"""
    print("📝 Creating test messages for sync testing...")
    
    test_messages = [
        {
            "source_device_id": "sync-test-device",
            "type": "SMS", 
            "sender": "+1111111111",
            "content": "Sync test message 1",
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "metadata": {"test": "sync1"}
        },
        {
            "source_device_id": "sync-test-device", 
            "type": "PUSH_NOTIFICATION",
            "sender": "TestApp",
            "content": "Sync test notification 2", 
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "metadata": {"test": "sync2"}
        }
    ]
    
    created_count = 0
    for msg in test_messages:
        response = requests.post(
            f"{BASE_URL}/api/v1/messages",
            json=msg,
            headers={"Content-Type": "application/json"}
        )
        if response.status_code == 201:
            created_count += 1
    
    print(f"✅ Created {created_count} test messages")
    time.sleep(1)  # Give server time to process
    print()

def main():
    print("🚀 Starting Delta Sync Tests")
    print("="*50)
    
    try:
        # Create test data
        create_test_messages()
        
        # Test sync status
        latest_timestamp = test_sync_status()
        
        # Test full sync
        full_sync_timestamp = test_full_sync()
        
        # Test delta sync
        test_delta_sync(latest_timestamp)
        
        # Test filtering
        test_filtered_sync()
        
        # Test pagination
        test_pagination()
        
        # Test overlapping ranges
        test_overlapping_ranges()
        
        # Test error handling
        test_invalid_timestamp()
        
        print("✅ All sync tests completed!")
        
    except requests.exceptions.ConnectionError:
        print("❌ Error: Could not connect to server.")
        print(f"   Make sure the server is running at {BASE_URL}")
    except Exception as e:
        print(f"❌ Error during testing: {str(e)}")

if __name__ == "__main__":
    main()