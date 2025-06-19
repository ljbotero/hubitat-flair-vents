// Manual test script to verify room data caching
// This demonstrates the caching behavior

def testRoomCaching() {
    println "=== Room Data Caching Test ==="
    println "This test demonstrates how room data caching prevents redundant API calls"
    println ""
    
    println "SCENARIO 1: Multiple vents in the same room"
    println "When you have vents like:"
    println "  - Vent 1 (ID: abc123) in Room A"
    println "  - Vent 2 (ID: def456) in Room A"
    println "  - Vent 3 (ID: ghi789) in Room B"
    println ""
    
    println "BEFORE CACHING:"
    println "  - Each vent refresh would call /api/vents/{id}/room"
    println "  - 3 vents = 3 room API calls"
    println "  - Your logs showed repeated calls to the same room endpoint"
    println ""
    
    println "AFTER CACHING:"
    println "  - First vent in Room A: Makes API call, caches room data"
    println "  - Second vent in Room A: Uses cached data (no API call)"
    println "  - First vent in Room B: Makes API call (different room)"
    println "  - Cache duration: 60 seconds"
    println ""
    
    println "SCENARIO 2: Rapid refreshes"
    println "Your logs showed:"
    println "  - Multiple calls to /api/vents/e522531c-e274-54ca-ee78-070c67e54718/room"
    println "  - All within a few seconds (08:48:01 to 08:48:06)"
    println ""
    
    println "With caching:"
    println "  - First call at 08:48:01: Makes API call, caches result"
    println "  - Subsequent calls until 08:49:01: Use cached data"
    println "  - Significant reduction in API calls"
    println ""
    
    println "HOW TO VERIFY:"
    println "1. Enable debug logging (Level 3)"
    println "2. Refresh multiple vents in the same room"
    println "3. Look for log messages:"
    println "   - 'Using cached room data for room {id} (cached Xs ago)'"
    println "   - 'Cached room data for room {id}'"
    println "4. Count API throttling messages - should be significantly reduced"
    println ""
    
    println "IMPLEMENTATION DETAILS:"
    println "- Cache key: room ID (not vent ID)"
    println "- Cache duration: 60 seconds (configurable via ROOM_CACHE_DURATION_MS)"
    println "- Shared between vents and pucks"
    println "- Automatic cleanup of expired entries (optional)"
}

testRoomCaching()
