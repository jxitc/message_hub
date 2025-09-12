#!/usr/bin/env python3
"""
Test script for Message Hub API endpoints
Run this after starting the server to test functionality
"""

import requests
import json
from datetime import datetime, timezone

# Configuration
BASE_URL = "http://127.0.0.1:5001"
# BASE_URL = "http://188.166.172.192:5001"  # Use this for remote testing

def test_health_check():
    """Test health check endpoint"""
    print("🔍 Testing health check...")
    response = requests.get(f"{BASE_URL}/health")
    print(f"Status: {response.status_code}")
    print(f"Response: {response.json()}")
    print()

def test_register_device():
    """Test device registration"""
    print("📱 Testing device registration...")
    device_data = {
        "id": "test-device-1",
        "name": "Test Android Phone",
        "type": "android"
    }
    
    response = requests.post(
        f"{BASE_URL}/api/v1/devices/register",
        json=device_data,
        headers={"Content-Type": "application/json"}
    )
    print(f"Status: {response.status_code}")
    print(f"Response: {response.json()}")
    
    if response.status_code == 201:
        return response.json().get('api_key')
    print()
    return None

def test_list_devices():
    """Test listing devices"""
    print("📋 Testing device list...")
    response = requests.get(f"{BASE_URL}/api/v1/devices")
    print(f"Status: {response.status_code}")
    print(f"Response: {response.json()}")
    print()

def test_create_message():
    """Test message creation"""
    print("💬 Testing message creation...")
    message_data = {
        "source_device_id": "test-device-1",
        "type": "SMS",
        "sender": "+1234567890",
        "content": "Hello from the API test! This is a test SMS message.",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "metadata": {
            "thread_id": "test_thread_123",
            "priority": "normal"
        }
    }
    
    response = requests.post(
        f"{BASE_URL}/api/v1/messages",
        json=message_data,
        headers={"Content-Type": "application/json"}
    )
    print(f"Status: {response.status_code}")
    print(f"Response: {response.json()}")
    
    if response.status_code == 201:
        return response.json().get('id')
    print()
    return None

def test_list_messages():
    """Test listing messages"""
    print("📝 Testing message list...")
    response = requests.get(f"{BASE_URL}/api/v1/messages")
    print(f"Status: {response.status_code}")
    data = response.json()
    print(f"Found {data.get('total', 0)} messages")
    for msg in data.get('messages', [])[:2]:  # Show first 2 messages
        print(f"  - {msg['type']}: {msg['content'][:50]}...")
    print()

def test_get_message(message_id):
    """Test getting single message"""
    if not message_id:
        print("⚠️  Skipping single message test (no message ID)")
        return
        
    print(f"🔍 Testing single message retrieval...")
    response = requests.get(f"{BASE_URL}/api/v1/messages/{message_id}")
    print(f"Status: {response.status_code}")
    print(f"Response: {response.json()}")
    print()

def test_mark_read(message_id):
    """Test marking message as read"""
    if not message_id:
        print("⚠️  Skipping mark as read test (no message ID)")
        return
        
    print("✅ Testing mark as read...")
    response = requests.put(f"{BASE_URL}/api/v1/messages/{message_id}/read")
    print(f"Status: {response.status_code}")
    print(f"Response: {response.json()}")
    print()

def test_message_filtering():
    """Test message filtering"""
    print("🔎 Testing message filtering...")
    
    # Filter by type
    response = requests.get(f"{BASE_URL}/api/v1/messages?type=SMS")
    print(f"SMS messages: {response.json().get('total', 0)}")
    
    # Filter by device
    response = requests.get(f"{BASE_URL}/api/v1/messages?device=android-phone-1")
    print(f"Messages from android-phone-1: {response.json().get('total', 0)}")
    
    # Pagination
    response = requests.get(f"{BASE_URL}/api/v1/messages?per_page=2&page=1")
    data = response.json()
    print(f"Page 1 (limit 2): {len(data.get('messages', []))} messages, has_more: {data.get('has_more', False)}")
    print()

def check_database_status():
    """Check if database has been initialized"""
    print("🔍 Checking database status...")
    response = requests.get(f"{BASE_URL}/api/v1/messages")
    if response.status_code == 500:
        print("❌ Database not initialized! Run: python init_db.py")
        return False
    else:
        data = response.json()
        print(f"✅ Database working. Found {data.get('total', 0)} messages")
        return True

def main():
    print("🚀 Starting Message Hub API Tests")
    print("="*50)
    
    try:
        # Basic tests
        test_health_check()
        
        # Check database status first
        if not check_database_status():
            print("\n⚠️  Please run 'python init_db.py' first to initialize the database")
            return
        
        print()
        
        # Device tests
        api_key = test_register_device()
        test_list_devices()
        
        # Message tests
        message_id = test_create_message()
        test_list_messages()
        test_get_message(message_id)
        test_mark_read(message_id)
        test_message_filtering()
        
        print("✅ All tests completed successfully!")
        print("📝 Note: Test data is NOT deleted - it remains in the database")
        
    except requests.exceptions.ConnectionError:
        print("❌ Error: Could not connect to server.")
        print(f"   Make sure the server is running at {BASE_URL}")
    except Exception as e:
        print(f"❌ Error during testing: {str(e)}")

if __name__ == "__main__":
    main()