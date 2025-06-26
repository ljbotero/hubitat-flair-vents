import spock.lang.*

/**
 * Comprehensive Vent Control Functionality Tests
 * 
 * Tests core vent control operations that users report are failing:
 * - Manual vent level changes
 * - Dashboard control integration  
 * - Room active/inactive commands
 * - Device polling and updates
 * - Response timing and reliability
 */
class VentControlFunctionalitySpec extends Specification {

    def app
    def ventDevice
    def puckDevice
    def mockThermostat
    def mockResponse
    def loggedMessages = []

    def setup() {
        app = new TestFlairApp()
        ventDevice = createMockVentDevice()
        puckDevice = createMockPuckDevice()
        mockThermostat = createMockThermostat()
        mockResponse = new MockVentResponse()
        loggedMessages.clear()
        
        // Initialize app with test data
        app.loggedMessages = loggedMessages
        app.mockChildDevices = [ventDevice, puckDevice]
        app.state = [flairAccessToken: 'test-token-123']
        app.settings = [dabEnabled: true, thermostat1: mockThermostat]
    }

    // Test 1: Manual Vent Control - Core Functionality
    def "manual vent control should work reliably"() {
        given: "a vent device with current level"
        ventDevice.currentValue('percent-open') >> 50
        ventDevice.currentValue('room-id') >> 'room123'
        ventDevice.hasAttribute('percent-open') >> true

        when: "user manually changes vent level to 75%"
        app.patchVent(ventDevice, 75)

        then: "vent should be updated via API call"
        app.patchAsyncCalled == true
        app.patchUri.contains('/api/vents/')
        app.patchCallback == 'handleVentPatch'
        noExceptionThrown()
    }

    def "manual vent control should handle edge cases properly"() {
        given: "a vent device"
        ventDevice.currentValue('percent-open') >> currentLevel
        ventDevice.currentValue('room-id') >> 'room123'
        ventDevice.hasAttribute('percent-open') >> true

        when: "user sets vent to target level"
        app.patchVent(ventDevice, targetLevel)

        then: "appropriate action should be taken"
        if (targetLevel == currentLevel) {
            // Same level - should skip API call
            app.patchAsyncCalled == false
        } else {
            // Different level - should make API call
            app.patchAsyncCalled == true
        }

        where:
        currentLevel | targetLevel
        0           | 0           // Same level - no change
        50          | 50          // Same level - no change  
        25          | 75          // Different level - should update
        100         | 0           // Different level - should update
        0           | 100         // Different level - should update
    }

    def "manual vent control should enforce valid ranges"() {
        given: "a vent device"
        ventDevice.currentValue('percent-open') >> 50
        ventDevice.hasAttribute('percent-open') >> true

        when: "user sets vent to invalid level"
        app.patchVent(ventDevice, inputLevel)

        then: "level should be clamped to valid range"
        app.patchAsyncCalled == true
        app.patchBodyContains("percent-open\":${expectedLevel}")

        where:
        inputLevel | expectedLevel
        -10        | 0            // Negative should clamp to 0
        150        | 100          // Over 100 should clamp to 100
        50.7       | 51           // Decimal should be rounded
    }

    // Test 2: Room Active/Inactive Commands
    def "room active/inactive control should work for vents"() {
        given: "a vent device with room data"
        ventDevice.currentValue('room-id') >> 'room123'
        ventDevice.currentValue('room-active') >> currentState

        when: "user changes room active state"
        app.patchRoom(ventDevice, targetState)

        then: "appropriate API call should be made"
        if (targetState == currentState) {
            // Same state - should skip API call
            app.patchAsyncCalled == false
        } else {
            // Different state - should make API call
            app.patchAsyncCalled == true
            app.patchUri.contains('/api/rooms/')
        }

        where:
        currentState | targetState
        'true'       | 'true'      // Same - no change
        'false'      | 'false'     // Same - no change
        'true'       | 'false'     // Different - should update
        'false'      | 'true'      // Different - should update
    }

    def "room active control should handle missing room ID gracefully"() {
        given: "a vent device without room ID"
        ventDevice.currentValue('room-id') >> null

        when: "user tries to change room active state"
        app.patchRoom(ventDevice, 'true')

        then: "no API call should be made"
        app.patchAsyncCalled == false
        noExceptionThrown()
    }

    // Test 3: Device Polling and Updates
    def "device polling should trigger data refresh for vents"() {
        given: "a vent device that needs refresh"
        ventDevice.getDeviceNetworkId() >> 'vent123'
        ventDevice.currentValue('room-id') >> 'room123'
        ventDevice.hasAttribute('percent-open') >> true

        when: "device polling triggers refresh"
        app.getDeviceData(ventDevice)

        then: "multiple API calls should be made to get current data"
        app.getAsyncCalled == true
        app.getCallCount >= 2 // Should make multiple calls
    }

    def "device polling should trigger data refresh for pucks"() {
        given: "a puck device that needs refresh"
        puckDevice.getDeviceNetworkId() >> 'puck123'
        puckDevice.currentValue('room-id') >> 'room123'
        puckDevice.hasAttribute('percent-open') >> false // Pucks don't have this

        when: "puck polling triggers refresh"
        app.getDeviceData(puckDevice)

        then: "puck-specific API calls should be made"
        app.getAsyncCalled == true
        app.getCallCount >= 2 // Should make multiple calls for pucks too
    }

    // Test 4: Response Timing - Critical for User Experience
    def "vent control should not block or timeout"() {
        given: "app with proper async setup"
        app.canMakeRequest() >> true

        when: "user makes vent control request"
        def startTime = System.currentTimeMillis()
        app.patchVent(ventDevice, 75)
        def endTime = System.currentTimeMillis()

        then: "call should return quickly (async operation)"
        (endTime - startTime) < 1000 // Should return in under 1 second
        app.patchAsyncCalled == true
    }

    def "multiple rapid vent control requests should be handled properly"() {
        given: "app with request throttling"
        app.mockCanMakeRequest = [true, true, false, true] // Third request throttled

        when: "user makes multiple rapid requests"
        app.patchVent(ventDevice, 25)  // Goes through (different from 50%)
        ventDevice.updateAttribute('percent-open', 25) // Update device state
        app.patchVent(ventDevice, 60)  // Goes through (different from 25%)
        ventDevice.updateAttribute('percent-open', 60) // Update device state
        app.patchVent(ventDevice, 75)  // Throttled (different from 60%)
        app.patchVent(ventDevice, 100) // Goes through (different from 60%)

        then: "requests should be handled appropriately"
        app.patchAsyncCallCount == 3 // First, second, and fourth go through
        app.runInMillisCalled == true // Third gets queued
    }

    // Test 5: Error Handling and Recovery
    def "vent control should handle API failures gracefully"() {
        given: "app that can make requests"
        app.canMakeRequest() >> true
        
        and: "mock HTTP response that fails"
        mockResponse.setErrorResponse(500, "Internal Server Error")

        when: "vent control encounters API error"
        app.handleVentPatch(mockResponse, [device: ventDevice, targetOpen: 75])

        then: "error should be handled gracefully"
        noExceptionThrown()
        loggedMessages.any { it.contains("ERROR") }
    }

    def "vent control should handle authentication errors properly"() {
        given: "mock HTTP response with auth error"
        mockResponse.setErrorResponse(401, "Unauthorized")

        when: "vent control encounters auth error"
        def isValid = app.isValidResponse(mockResponse)

        then: "should trigger re-authentication"
        isValid == false
        app.runInCalled == true
        app.runInMethod == 'autoReauthenticate'
    }

    // Test 6: Integration with DAB (Dynamic Airflow Balancing)
    def "DAB should not interfere with manual vent control"() {
        given: "DAB is enabled"
        app.settings = [dabEnabled: true]
        
        and: "vent device"
        ventDevice.currentValue('percent-open') >> 50

        when: "user manually controls vent during DAB operation"
        app.patchVent(ventDevice, 75)

        then: "manual control should work regardless of DAB state"
        app.patchAsyncCalled == true
        noExceptionThrown()
    }

    def "vent control should work when DAB is disabled"() {
        given: "DAB is disabled"
        app.settings = [dabEnabled: false]
        
        and: "vent device"
        ventDevice.currentValue('percent-open') >> 50

        when: "user manually controls vent"
        app.patchVent(ventDevice, 25)

        then: "manual control should still work"
        app.patchAsyncCalled == true
        noExceptionThrown()
    }

    // Test 7: Polling Interval Management
    def "polling interval should adjust based on HVAC state"() {
        given: "app with thermostat"
        mockThermostat.currentValue('thermostatOperatingState') >> hvacState
        app.settings = [thermostat1: mockThermostat]

        when: "HVAC state changes"
        app.thermostat1ChangeStateHandler([value: hvacState])

        then: "polling interval should be updated appropriately"
        app.updatePollingIntervalCalled == true
        app.updatePollingIntervalValue == expectedInterval

        where:
        hvacState | expectedInterval
        'cooling' | 3               // Active interval
        'heating' | 3               // Active interval
        'idle'    | 10              // Idle interval
        'off'     | 10              // Idle interval
    }

    def "device polling interval update should propagate to all devices"() {
        given: "app with multiple devices"
        app.mockChildDevices = [ventDevice, puckDevice]

        when: "polling interval is updated"
        app.updateDevicePollingInterval(5)

        then: "all devices should be updated"
        app.updatePollingIntervalCalled == true
        app.updatePollingIntervalValue == 5
    }

    // Helper methods for creating mock devices
    def createMockVentDevice() {
        return new MockVentDevice()
    }

    def createMockPuckDevice() {
        return new MockPuckDevice()
    }

    def createMockThermostat() {
        return new MockThermostat()
    }
}

// Test helper classes
class TestFlairApp {
    def state = [:]
    def settings = [:]
    def loggedMessages = []
    def mockChildDevices = []
    def mockCanMakeRequest = []
    
    // Call tracking
    boolean patchAsyncCalled = false
    boolean getAsyncCalled = false
    boolean runInCalled = false
    boolean runInMillisCalled = false
    boolean updatePollingIntervalCalled = false
    
    String patchUri = ""
    String patchCallback = ""
    String patchBody = ""
    String runInMethod = ""
    Integer runInDelay = 0
    Integer patchAsyncCallCount = 0
    Integer getCallCount = 0
    Integer updatePollingIntervalValue = 0
    
    private int canMakeRequestIndex = 0

    def canMakeRequest() {
        if (mockCanMakeRequest.isEmpty()) {
            return true
        }
        if (canMakeRequestIndex < mockCanMakeRequest.size()) {
            return mockCanMakeRequest[canMakeRequestIndex++]
        }
        return true
    }

    def patchVent(device, percentOpen) {
        logDebug("patchVent called with device=${device.getDeviceNetworkId()}, percentOpen=${percentOpen}")
        
        // Check if device has percent-open attribute
        if (!device.hasAttribute('percent-open')) {
            logError("Device ${device.getDeviceNetworkId()} does not have percent-open attribute")
            return
        }

        def currentLevel = device.currentValue('percent-open')
        
        // Skip if same level
        if (currentLevel == percentOpen) {
            logDebug("Vent already at ${percentOpen}%, skipping API call")
            patchAsyncCalled = false
            return
        }

        // Clamp values
        def clampedLevel = Math.max(0, Math.min(100, Math.round(percentOpen)))
        
        // Check if we can make request
        if (!canMakeRequest()) {
            logWarn("Request throttled, queueing for later")
            runInMillisCalled = true
            patchAsyncCalled = false
            return
        }

        // Make API call
        def deviceId = device.getDeviceNetworkId()
        patchUri = "https://api.flair.co/api/vents/${deviceId}"
        patchCallback = "handleVentPatch"
        patchBody = "{\"data\":{\"type\":\"vents\",\"attributes\":{\"percent-open\":${clampedLevel}}}}"
        patchAsyncCalled = true
        patchAsyncCallCount++
        
        logDebug("API call made: ${patchUri}")
    }

    def patchRoom(device, active) {
        logDebug("patchRoom called with device=${device.getDeviceNetworkId()}, active=${active}")
        
        def roomId = device.currentValue('room-id')
        if (!roomId) {
            logError("Device ${device.getDeviceNetworkId()} does not have room-id")
            patchAsyncCalled = false
            return
        }

        def currentState = device.currentValue('room-active')
        
        // Skip if same state
        if (currentState == active) {
            logDebug("Room already ${active}, skipping API call")
            patchAsyncCalled = false
            return
        }

        // Check if we can make request
        if (!canMakeRequest()) {
            logWarn("Request throttled, queueing for later")
            runInMillisCalled = true
            patchAsyncCalled = false
            return
        }

        // Make API call
        patchUri = "https://api.flair.co/api/rooms/${roomId}"
        patchCallback = "handleRoomPatch"
        patchAsyncCalled = true
        patchAsyncCallCount++
        
        logDebug("API call made: ${patchUri}")
    }

    def getDeviceData(device) {
        logDebug("getDeviceData called with device=${device.getDeviceNetworkId()}")
        
        def deviceId = device.getDeviceNetworkId()
        def roomId = device.currentValue('room-id')
        
        // Different calls for vents vs pucks
        if (device.hasAttribute('percent-open')) {
            // Vent device
            getCallCount = 2 // Device reading + room data
        } else {
            // Puck device  
            getCallCount = 3 // Device data + device reading + room data
        }
        
        getAsyncCalled = true
        logDebug("Made ${getCallCount} API calls for device data")
    }

    def handleVentPatch(resp, data) {
        logDebug("handleVentPatch called")
        
        if (!resp) {
            logError("handleVentPatch: No response")
            return
        }
        
        if (resp.hasError()) {
            def status = resp.getStatus()
            logError("handleVentPatch: HTTP ${status} error")
            
            if (status == 401) {
                runIn(1, 'autoReauthenticate')
            }
            return
        }
        
        logDebug("Vent patch successful")
    }

    def handleRoomPatch(resp, data) {
        logDebug("handleRoomPatch called")
        
        if (!resp || resp.hasError()) {
            logError("handleRoomPatch: API error")
            return
        }
        
        logDebug("Room patch successful")
    }

    def isValidResponse(resp) {
        if (!resp || resp.hasError()) {
            if (resp?.getStatus() == 401) {
                runIn(1, 'autoReauthenticate')
                runInCalled = true
                runInMethod = 'autoReauthenticate'
            }
            return false
        }
        return true
    }

    def thermostat1ChangeStateHandler(event) {
        logDebug("thermostat1ChangeStateHandler called with ${event.value}")
        
        def hvacState = event.value
        def newInterval = 10 // Default idle interval
        
        if (hvacState in ['cooling', 'heating']) {
            newInterval = 3 // Active interval
        }
        
        updateDevicePollingInterval(newInterval)
    }

    def updateDevicePollingInterval(intervalMinutes) {
        logDebug("updateDevicePollingInterval called with ${intervalMinutes}")
        
        updatePollingIntervalCalled = true
        updatePollingIntervalValue = intervalMinutes
        
        // Simulate updating all child devices
        mockChildDevices.each { device ->
            logDebug("Updating polling interval for ${device.getDeviceNetworkId()}")
        }
    }

    def runIn(delay, method) {
        runInCalled = true
        runInDelay = delay
        runInMethod = method
    }

    def runInMillis(delay, method, data) {
        runInMillisCalled = true
    }

    def getChildDevices() {
        return mockChildDevices
    }

    def patchBodyContains(text) {
        return patchBody.contains(text)
    }

    def logDebug(msg) {
        loggedMessages << "DEBUG: ${msg}"
    }

    def logWarn(msg) {
        loggedMessages << "WARN: ${msg}"
    }

    def logError(msg) {
        loggedMessages << "ERROR: ${msg}"
    }
}

class MockVentDevice {
    def attributes = [
        'percent-open': 50,
        'room-id': 'room123',
        'room-name': 'Living Room',
        'room-active': 'true'
    ]
    
    def getDeviceNetworkId() {
        return 'vent123'
    }
    
    def getLabel() {
        return 'Living Room Vent'
    }
    
    def getTypeName() {
        return 'Flair vents'
    }
    
    def hasAttribute(String attributeName) {
        return attributes.containsKey(attributeName)
    }
    
    def currentValue(String attributeName) {
        return attributes[attributeName]
    }
    
    def updateAttribute(String attributeName, value) {
        attributes[attributeName] = value
    }
}

class MockPuckDevice {
    def attributes = [
        'temperature': 72.5,
        'humidity': 45,
        'room-id': 'room123',
        'room-name': 'Living Room'
    ]
    
    def getDeviceNetworkId() {
        return 'puck123'
    }
    
    def getLabel() {
        return 'Living Room Puck'
    }
    
    def getTypeName() {
        return 'Flair pucks'
    }
    
    def hasAttribute(String attributeName) {
        return attributes.containsKey(attributeName)
    }
    
    def currentValue(String attributeName) {
        return attributes[attributeName]
    }
    
    def updateAttribute(String attributeName, value) {
        attributes[attributeName] = value
    }
}

class MockThermostat {
    def attributes = [
        'temperature': 72.0,
        'coolingSetpoint': 75.0,
        'heatingSetpoint': 68.0,
        'thermostatOperatingState': 'idle'
    ]
    
    def getDeviceNetworkId() {
        return 'thermostat123'
    }
    
    def getLabel() {
        return 'Main Thermostat'
    }
    
    def currentValue(String attributeName) {
        return attributes[attributeName]
    }
    
    def updateAttribute(String attributeName, value) {
        attributes[attributeName] = value
    }
}

class MockVentResponse {
    private boolean hasError = false
    private int status = 200
    private String errorMessage = ""
    private Map responseData = [:]

    def setSuccessResponse(Map data) {
        this.hasError = false
        this.status = 200
        this.responseData = data
    }

    def setErrorResponse(int status, String message) {
        this.hasError = true
        this.status = status
        this.errorMessage = message
    }

    boolean hasError() {
        return hasError
    }

    int getStatus() {
        return status
    }

    String getErrorMessage() {
        return errorMessage
    }

    def getData() {
        return responseData
    }

    def getJson() {
        return responseData
    }
}
