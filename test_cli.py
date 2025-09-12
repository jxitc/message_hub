#!/usr/bin/env python3
"""
Test script for Message Hub CLI functionality
"""

import subprocess
import sys
import os
import time

def run_cli_command(command, capture_output=True):
    """Run a CLI command and return the result"""
    try:
        cmd = f"python cli/main.py {command}"
        result = subprocess.run(
            cmd, 
            shell=True, 
            capture_output=capture_output,
            text=True,
            timeout=30
        )
        return result.returncode, result.stdout, result.stderr
    except subprocess.TimeoutExpired:
        return -1, "", "Command timed out"
    except Exception as e:
        return -1, "", str(e)

def test_cli_help():
    """Test CLI help functionality"""
    print("🔍 Testing CLI help...")
    
    code, stdout, stderr = run_cli_command("--help")
    if code == 0 and "Message Hub CLI" in stdout:
        print("✅ CLI help working")
        return True
    else:
        print(f"❌ CLI help failed: {stderr}")
        return False

def test_cli_status():
    """Test CLI status command"""
    print("📊 Testing status command...")
    
    code, stdout, stderr = run_cli_command("status")
    if code == 0:
        print("✅ Status command working")
        if "Message Hub Server Status" in stdout:
            print("   Server status displayed correctly")
        return True
    else:
        print(f"❌ Status command failed: {stderr}")
        return False

def test_cli_messages():
    """Test CLI messages command"""
    print("📬 Testing messages command...")
    
    code, stdout, stderr = run_cli_command("messages --limit 5")
    if code == 0:
        print("✅ Messages command working")
        if "Messages" in stdout or "No messages found" in stdout:
            print("   Messages displayed correctly")
        return True
    else:
        print(f"❌ Messages command failed: {stderr}")
        return False

def test_cli_messages_verbose():
    """Test CLI messages verbose mode"""
    print("📝 Testing messages verbose mode...")
    
    code, stdout, stderr = run_cli_command("messages --limit 3 --verbose")
    if code == 0:
        print("✅ Verbose messages command working")
        return True
    else:
        print(f"❌ Verbose messages command failed: {stderr}")
        return False

def test_cli_messages_filtered():
    """Test CLI messages with filters"""
    print("🔍 Testing messages with filters...")
    
    # Test type filter
    code, stdout, stderr = run_cli_command("messages --type SMS --limit 3")
    if code == 0:
        print("✅ Type filter working")
    else:
        print(f"⚠️  Type filter failed: {stderr}")
    
    # Test device filter
    code, stdout, stderr = run_cli_command("messages --device android-phone-1 --limit 3")
    if code == 0:
        print("✅ Device filter working")
        return True
    else:
        print(f"⚠️  Device filter failed: {stderr}")
        return False

def test_cli_sync():
    """Test CLI sync command"""
    print("🔄 Testing sync command...")
    
    code, stdout, stderr = run_cli_command("sync")
    if code == 0:
        print("✅ Sync command working")
        if "Delta Sync Results" in stdout:
            print("   Sync results displayed correctly")
        return True
    else:
        print(f"❌ Sync command failed: {stderr}")
        return False

def test_cli_config():
    """Test CLI configuration"""
    print("⚙️  Testing configuration...")
    
    # Show config
    code, stdout, stderr = run_cli_command("config-show")
    if code == 0:
        print("✅ Config show working")
        if "Current Configuration" in stdout:
            print("   Configuration displayed correctly")
        return True
    else:
        print(f"❌ Config show failed: {stderr}")
        return False

def test_executable():
    """Test the message-hub executable"""
    print("🚀 Testing message-hub executable...")
    
    try:
        result = subprocess.run(
            ["./message-hub", "--help"],
            capture_output=True,
            text=True,
            timeout=10
        )
        
        if result.returncode == 0 and "Message Hub CLI" in result.stdout:
            print("✅ Executable working correctly")
            return True
        else:
            print(f"❌ Executable failed: {result.stderr}")
            return False
    except Exception as e:
        print(f"❌ Executable test failed: {e}")
        return False

def run_interactive_demo():
    """Run interactive demo if user wants"""
    print("\n🎮 Interactive Demo Available!")
    print("Try these commands:")
    print("  ./message-hub status")
    print("  ./message-hub messages --limit 5")
    print("  ./message-hub messages --verbose --limit 3")
    print("  ./message-hub messages --type SMS")
    print("  ./message-hub sync")
    print("  ./message-hub config-show")
    print()

def main():
    print("🚀 Starting Message Hub CLI Tests")
    print("=" * 50)
    
    # Check if server is running
    print("🔍 Checking if server is accessible...")
    code, stdout, stderr = run_cli_command("status")
    if code != 0:
        print("⚠️  Server not accessible - some tests may fail")
        print(f"   Make sure Message Hub server is running at http://127.0.0.1:5001")
        print()
    
    tests = [
        test_cli_help,
        test_cli_status,
        test_cli_messages,
        test_cli_messages_verbose,
        test_cli_messages_filtered,
        test_cli_sync,
        test_cli_config,
        test_executable
    ]
    
    passed = 0
    total = len(tests)
    
    for test in tests:
        try:
            if test():
                passed += 1
            print()
        except Exception as e:
            print(f"❌ Test failed with exception: {e}")
            print()
    
    print("=" * 50)
    print(f"📊 Test Results: {passed}/{total} tests passed")
    
    if passed == total:
        print("🎉 All CLI tests passed!")
    else:
        print(f"⚠️  {total - passed} tests failed")
    
    run_interactive_demo()

if __name__ == "__main__":
    main()