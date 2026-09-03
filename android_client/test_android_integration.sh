#!/bin/bash

# Android Client Integration Test Script
# Tests the InfoAgent server endpoints that Android client will use

set -e

echo "🧪 Android Client Integration Tests"
echo "=================================="

# Configuration
SERVER_URL="http://localhost:8000"
API_BASE="${SERVER_URL}/api/v1"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test counter
TESTS_PASSED=0
TESTS_FAILED=0

# Helper functions
test_passed() {
    echo -e "${GREEN}✅ PASS${NC}: $1"
    TESTS_PASSED=$((TESTS_PASSED + 1))
}

test_failed() {
    echo -e "${RED}❌ FAIL${NC}: $1"
    echo -e "${RED}   Error: $2${NC}"
    TESTS_FAILED=$((TESTS_FAILED + 1))
}

test_info() {
    echo -e "${BLUE}ℹ️  INFO${NC}: $1"
}

# Test 1: Server Health Check
echo ""
echo "Test 1: Server Health Check"
echo "----------------------------"
HEALTH_RESPONSE=$(curl -s ${SERVER_URL}/health 2>/dev/null || echo "ERROR")

if [[ "$HEALTH_RESPONSE" == *"healthy"* ]]; then
    test_passed "Server health check"
    test_info "Response: $HEALTH_RESPONSE"
else
    test_failed "Server health check" "Server not responding or unhealthy"
    echo "❌ Cannot continue tests without healthy server"
    exit 1
fi

# Test 2: Memory Creation (Android Simulation)
echo ""
echo "Test 2: Memory Creation API"
echo "----------------------------"
MEMORY_DATA='{"content": "Android test: Meeting with development team about new mobile features", "source_type": "MANUAL"}'

MEMORY_RESPONSE=$(curl -s -X POST "${API_BASE}/memories" \
    -H "Content-Type: application/json" \
    -d "$MEMORY_DATA" 2>/dev/null || echo "ERROR")

if [[ "$MEMORY_RESPONSE" == *"success\":true"* ]]; then
    test_passed "Memory creation API"
    MEMORY_ID=$(echo "$MEMORY_RESPONSE" | grep -o '"id":[0-9]*' | cut -d':' -f2 || echo "unknown")
    test_info "Created memory ID: $MEMORY_ID"
    
    # Verify AI processing worked
    if [[ "$MEMORY_RESPONSE" == *"ai_processed\":true"* ]]; then
        test_passed "AI processing enabled"
    else
        test_failed "AI processing" "AI processing not detected in response"
    fi
    
    # Verify title generation
    if [[ "$MEMORY_RESPONSE" == *"title\":"* ]]; then
        test_passed "AI title generation"
        TITLE=$(echo "$MEMORY_RESPONSE" | grep -o '"title":"[^"]*"' | cut -d'"' -f4 || echo "unknown")
        test_info "Generated title: '$TITLE'"
    else
        test_failed "AI title generation" "No title found in response"
    fi
else
    test_failed "Memory creation API" "API call failed or returned error"
    echo "Response: $MEMORY_RESPONSE"
fi

# Test 3: Memory List API (Android will use this)
echo ""
echo "Test 3: Memory List API"
echo "-----------------------"
LIST_RESPONSE=$(curl -s "${API_BASE}/memories" 2>/dev/null || echo "ERROR")

if [[ "$LIST_RESPONSE" == *"success\":true"* ]]; then
    test_passed "Memory list API"
    MEMORY_COUNT=$(echo "$LIST_RESPONSE" | grep -o '"id":[0-9]*' | wc -l || echo "0")
    test_info "Total memories in database: $MEMORY_COUNT"
else
    test_failed "Memory list API" "API call failed"
fi

# Test 4: Invalid Request Handling (Android error scenarios)
echo ""
echo "Test 4: Error Handling"
echo "----------------------"
ERROR_DATA='{"content": "", "source_type": "INVALID"}'

ERROR_RESPONSE=$(curl -s -X POST "${API_BASE}/memories" \
    -H "Content-Type: application/json" \
    -d "$ERROR_DATA" 2>/dev/null || echo "ERROR")

if [[ "$ERROR_RESPONSE" == *"success\":false"* ]] || [[ "$ERROR_RESPONSE" == *"error"* ]]; then
    test_passed "Error handling for invalid requests"
    test_info "Server properly rejected invalid request"
else
    test_failed "Error handling" "Server should reject invalid requests"
fi

# Test 5: CORS Headers (Required for web/mobile clients)
echo ""
echo "Test 5: CORS Headers"
echo "--------------------"
CORS_RESPONSE=$(curl -s -I -X OPTIONS "${API_BASE}/memories" \
    -H "Origin: http://localhost:3000" \
    -H "Access-Control-Request-Method: POST" \
    -H "Access-Control-Request-Headers: Content-Type" 2>/dev/null || echo "ERROR")

if [[ "$CORS_RESPONSE" == *"Access-Control-Allow-Origin"* ]]; then
    test_passed "CORS headers present"
else
    test_failed "CORS headers" "CORS not properly configured"
fi

# Summary
echo ""
echo "🏁 Test Summary"
echo "==============="
echo -e "Tests passed: ${GREEN}$TESTS_PASSED${NC}"
echo -e "Tests failed: ${RED}$TESTS_FAILED${NC}"

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "\n${GREEN}🎉 All tests passed! Android client should work correctly.${NC}"
    echo ""
    echo "Next steps:"
    echo "1. Run Android app in emulator"
    echo "2. Test Settings → Test Connection"  
    echo "3. Add memory and verify server receives it"
    echo "4. Check memory list shows AI-generated title"
    exit 0
else
    echo -e "\n${RED}⚠️  Some tests failed. Fix server issues before testing Android client.${NC}"
    exit 1
fi