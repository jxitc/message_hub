#!/usr/bin/env python3
"""
Performance test script for Message Hub API
Tests sync performance with large datasets
"""

import requests
import json
import time
import random
from datetime import datetime, timezone, timedelta
from concurrent.futures import ThreadPoolExecutor, as_completed

# Configuration  
BASE_URL = "http://127.0.0.1:5001"
# BASE_URL = "http://188.166.172.192:5001"  # Use this for remote testing

def generate_test_message(device_id, msg_type, index):
    """Generate a test message"""
    types = ['SMS', 'PUSH_NOTIFICATION', 'EMAIL', 'CALL_LOG']
    senders = ['+1234567890', '+0987654321', 'WhatsApp', 'Gmail', 'Slack']
    
    return {
        "source_device_id": device_id,
        "type": random.choice(types) if msg_type == 'random' else msg_type,
        "sender": random.choice(senders),
        "content": f"Performance test message {index} - Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "metadata": {
            "test_batch": "performance",
            "message_index": index,
            "priority": random.choice(['high', 'normal', 'low'])
        }
    }

def create_message(message_data):
    """Create a single message (for threading)"""
    try:
        response = requests.post(
            f"{BASE_URL}/api/v1/messages",
            json=message_data,
            headers={"Content-Type": "application/json"},
            timeout=10
        )
        return response.status_code == 201
    except Exception as e:
        print(f"Error creating message: {e}")
        return False

def bulk_create_messages(count, batch_size=50, max_workers=5):
    """Create messages in bulk with threading"""
    print(f"📦 Creating {count} test messages in batches of {batch_size}...")
    
    devices = ['perf-device-1', 'perf-device-2', 'perf-device-3']
    created = 0
    failed = 0
    
    start_time = time.time()
    
    for batch_start in range(0, count, batch_size):
        batch_end = min(batch_start + batch_size, count)
        batch_messages = []
        
        # Generate batch
        for i in range(batch_start, batch_end):
            device_id = devices[i % len(devices)]
            message = generate_test_message(device_id, 'random', i)
            batch_messages.append(message)
        
        # Send batch with threading
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = [executor.submit(create_message, msg) for msg in batch_messages]
            
            for future in as_completed(futures):
                if future.result():
                    created += 1
                else:
                    failed += 1
        
        # Progress update
        if (batch_end % 100 == 0) or (batch_end == count):
            elapsed = time.time() - start_time
            rate = created / elapsed if elapsed > 0 else 0
            print(f"  Progress: {batch_end}/{count} - Created: {created}, Failed: {failed}, Rate: {rate:.1f} msg/s")
    
    total_time = time.time() - start_time
    print(f"✅ Bulk creation completed: {created} created, {failed} failed in {total_time:.2f}s")
    print(f"   Average rate: {created/total_time:.1f} messages/second")
    print()
    
    return created

def test_sync_performance(message_count):
    """Test sync performance with different page sizes"""
    print(f"⚡ Testing sync performance with {message_count} messages...")
    
    page_sizes = [10, 50, 100, 500, 1000]
    
    for page_size in page_sizes:
        print(f"  Testing page size: {page_size}")
        
        start_time = time.time()
        response = requests.get(f"{BASE_URL}/api/v1/sync/messages?limit={page_size}")
        request_time = time.time() - start_time
        
        if response.status_code == 200:
            data = response.json()
            returned = data['sync_info']['returned']
            print(f"    ✅ Returned {returned} messages in {request_time:.3f}s")
        else:
            print(f"    ❌ Failed with status {response.status_code}")
    print()

def test_filtered_sync_performance():
    """Test sync performance with filters"""
    print("🔍 Testing filtered sync performance...")
    
    filters = [
        ("device=perf-device-1", "Device filter"),
        ("type=SMS", "Type filter"),  
        ("device=perf-device-1&type=SMS", "Combined filters"),
        ("since=2024-01-01T00:00:00Z", "Since filter")
    ]
    
    for filter_param, description in filters:
        print(f"  Testing {description}: {filter_param}")
        
        start_time = time.time()
        response = requests.get(f"{BASE_URL}/api/v1/sync/messages?{filter_param}&limit=100")
        request_time = time.time() - start_time
        
        if response.status_code == 200:
            data = response.json()
            returned = data['sync_info']['returned']
            total = data.get('total_count', 0)
            print(f"    ✅ {returned}/{total} messages in {request_time:.3f}s")
        else:
            print(f"    ❌ Failed with status {response.status_code}")
    print()

def test_pagination_performance():
    """Test pagination performance - simulate client sync"""
    print("📄 Testing pagination performance (simulating client sync)...")
    
    start_time = time.time()
    total_synced = 0
    page_count = 0
    last_timestamp = None
    
    while True:
        page_count += 1
        url = f"{BASE_URL}/api/v1/sync/messages?limit=50"
        if last_timestamp:
            url += f"&since={last_timestamp}"
        
        page_start = time.time()
        response = requests.get(url)
        page_time = time.time() - page_start
        
        if response.status_code != 200:
            print(f"    ❌ Page {page_count} failed with status {response.status_code}")
            break
            
        data = response.json()
        returned = data['sync_info']['returned']
        total_synced += returned
        last_timestamp = data.get('last_timestamp')
        has_more = data.get('has_more', False)
        
        print(f"    Page {page_count}: {returned} messages in {page_time:.3f}s")
        
        if not has_more or returned == 0:
            break
        
        if page_count >= 20:  # Safety limit
            print("    ⚠️  Reached page limit (20 pages)")
            break
    
    total_time = time.time() - start_time
    avg_time_per_page = total_time / page_count if page_count > 0 else 0
    
    print(f"  ✅ Pagination complete: {total_synced} messages, {page_count} pages")
    print(f"     Total time: {total_time:.2f}s, Avg per page: {avg_time_per_page:.3f}s")
    print()

def test_concurrent_sync():
    """Test concurrent sync requests"""
    print("🔀 Testing concurrent sync performance...")
    
    def sync_request(thread_id):
        start_time = time.time()
        response = requests.get(f"{BASE_URL}/api/v1/sync/messages?limit=100&device=perf-device-{thread_id % 3 + 1}")
        request_time = time.time() - start_time
        
        if response.status_code == 200:
            data = response.json()
            returned = data['sync_info']['returned']
            return (thread_id, returned, request_time, True)
        else:
            return (thread_id, 0, request_time, False)
    
    # Test with different concurrency levels
    for workers in [1, 3, 5]:
        print(f"  Testing with {workers} concurrent requests:")
        
        start_time = time.time()
        with ThreadPoolExecutor(max_workers=workers) as executor:
            futures = [executor.submit(sync_request, i) for i in range(workers)]
            results = [future.result() for future in as_completed(futures)]
        
        total_time = time.time() - start_time
        successful = sum(1 for _, _, _, success in results if success)
        total_messages = sum(count for _, count, _, success in results if success)
        avg_request_time = sum(req_time for _, _, req_time, success in results if success) / successful if successful > 0 else 0
        
        print(f"    ✅ {successful}/{workers} successful, {total_messages} total messages")
        print(f"       Total time: {total_time:.2f}s, Avg request: {avg_request_time:.3f}s")
    print()

def cleanup_test_data():
    """Note: We don't have a delete API, so test data will remain"""
    print("🧹 Note: Test data remains in database (no bulk delete API)")
    print("   You can restart with a fresh database if needed")
    print()

def main():
    print("🚀 Starting Performance Tests")
    print("="*50)
    
    try:
        # Check if server is responsive
        print("🔍 Checking server status...")
        response = requests.get(f"{BASE_URL}/health", timeout=5)
        if response.status_code != 200:
            print("❌ Server not responding properly")
            return
        print("✅ Server is responsive")
        print()
        
        # Get baseline stats
        status_response = requests.get(f"{BASE_URL}/api/v1/sync/status")
        if status_response.status_code == 200:
            baseline = status_response.json()
            print(f"📊 Baseline: {baseline.get('total_messages', 0)} messages in database")
            print()
        
        # Create test data
        message_count = 500  # Adjust based on your needs
        created = bulk_create_messages(message_count, batch_size=25, max_workers=3)
        
        if created > 0:
            # Run performance tests
            test_sync_performance(created)
            test_filtered_sync_performance()
            test_pagination_performance() 
            test_concurrent_sync()
        
        cleanup_test_data()
        
        print("✅ Performance testing completed!")
        
    except requests.exceptions.ConnectionError:
        print("❌ Error: Could not connect to server.")
        print(f"   Make sure the server is running at {BASE_URL}")
    except KeyboardInterrupt:
        print("\n⚠️  Testing interrupted by user")
    except Exception as e:
        print(f"❌ Error during performance testing: {str(e)}")

if __name__ == "__main__":
    main()