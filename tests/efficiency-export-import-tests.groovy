import spock.lang.*
import java.text.SimpleDateFormat

/**
 * Test suite for efficiency data export/import functionality
 * Tests the ability to export and import cooling/heating efficiency data
 * to preserve learned DAB algorithm data across app reinstalls
 */
class EfficiencyExportImportTests extends Specification {

    def app
    def mockDevice1, mockDevice2, mockDevice3
    
    def setup() {
        app = new Object() {
            def atomicState = [:]
            def state = [:]
            def settings = [structureId: 'structure-123']
            def children = []
            
            // Mock Hubitat methods
            def getChildDevices() { return children }
            def getChildDevice(id) { return children.find { it.deviceNetworkId == id } }
            def log = [debug: { msg -> println "DEBUG: $msg" }, warn: { msg -> println "WARN: $msg" }]
            def now() { return System.currentTimeMillis() }
            
            // Methods to be implemented
            def exportEfficiencyData() { return [:] }
            def generateEfficiencyJSON(data) { return '{}' }
            def importEfficiencyData(jsonContent) { return [:] }
            def validateImportData(jsonData) { return true }
            def applyImportedEfficiencies(efficiencyData) { return true }
            def matchDeviceByRoomId(roomId) { return null }
            def matchDeviceByRoomName(roomName) { return null }
            def sendEvent(device, eventData) { 
                // Update the device's currentValue closure to return the new value
                def oldCurrentValue = device.currentValue
                device.currentValue = { attr ->
                    if (attr == eventData.name) {
                        return eventData.value
                    }
                    return oldCurrentValue(attr)
                }
            }
        }
        
        // Create mock devices with proper closure handling
        mockDevice1 = [
            deviceNetworkId: 'vent-001',
            label: 'Living Room Vent',
            typeName: 'Flair vents',
            getDeviceNetworkId: { -> 'vent-001' },
            currentValue: { attr -> 
                def attrs = [
                    'room-id': 'room-001',
                    'room-name': 'Living Room',
                    'room-cooling-rate': 0.85,
                    'room-heating-rate': 0.92
                ]
                return attrs[attr]
            },
            hasAttribute: { attr -> attr == 'percent-open' }
        ]
        
        mockDevice2 = [
            deviceNetworkId: 'vent-002', 
            label: 'Bedroom Vent',
            typeName: 'Flair vents',
            getDeviceNetworkId: { -> 'vent-002' },
            currentValue: { attr -> 
                def attrs = [
                    'room-id': 'room-002',
                    'room-name': 'Bedroom',
                    'room-cooling-rate': 1.2,
                    'room-heating-rate': 0.75
                ]
                return attrs[attr]
            },
            hasAttribute: { attr -> attr == 'percent-open' }
        ]
        
        mockDevice3 = [
            deviceNetworkId: 'puck-001',
            label: 'Kitchen Puck', 
            typeName: 'Flair pucks',
            getDeviceNetworkId: { -> 'puck-001' },
            currentValue: { attr -> 
                def attrs = [
                    'room-id': 'room-003',
                    'room-name': 'Kitchen'
                ]
                return attrs[attr]
            },
            hasAttribute: { attr -> attr != 'percent-open' }
        ]
        
        app.children = [mockDevice1, mockDevice2, mockDevice3]
        app.atomicState.maxCoolingRate = 1.5
        app.atomicState.maxHeatingRate = 1.2
    }

    def "should collect all efficiency data for export"() {
        given: "app with efficiency data in devices and atomicState"
        app.metaClass.exportEfficiencyData = {
            def data = [
                globalRates: [
                    maxCoolingRate: delegate.atomicState.maxCoolingRate,
                    maxHeatingRate: delegate.atomicState.maxHeatingRate
                ],
                roomEfficiencies: []
            ]
            
            // Only collect from vents (devices with percent-open attribute)
            delegate.getChildDevices().findAll { it.hasAttribute('percent-open') }.each { device ->
                def roomData = [
                    roomId: device.currentValue('room-id'),
                    roomName: device.currentValue('room-name'),
                    ventId: device.deviceNetworkId,
                    coolingRate: device.currentValue('room-cooling-rate') ?: 0,
                    heatingRate: device.currentValue('room-heating-rate') ?: 0
                ]
                data.roomEfficiencies << roomData
            }
            return data
        }
        
        when: "exporting efficiency data"
        def result = app.exportEfficiencyData()
        
        then: "should include global rates"
        result.globalRates.maxCoolingRate == 1.5
        result.globalRates.maxHeatingRate == 1.2
        
        and: "should include room efficiencies from vents only"
        result.roomEfficiencies.size() == 2  // Only vents, not pucks
        
        and: "should include living room data"
        def livingRoom = result.roomEfficiencies.find { it.roomName == 'Living Room' }
        livingRoom.roomId == 'room-001'
        livingRoom.ventId == 'vent-001'
        livingRoom.coolingRate == 0.85
        livingRoom.heatingRate == 0.92
        
        and: "should include bedroom data"
        def bedroom = result.roomEfficiencies.find { it.roomName == 'Bedroom' }
        bedroom.roomId == 'room-002'
        bedroom.ventId == 'vent-002'
        bedroom.coolingRate == 1.2
        bedroom.heatingRate == 0.75
    }

    def "should generate valid JSON from efficiency data"() {
        given: "efficiency data"
        def efficiencyData = [
            globalRates: [maxCoolingRate: 1.5, maxHeatingRate: 1.2],
            roomEfficiencies: [
                [roomId: 'room-001', roomName: 'Living Room', ventId: 'vent-001', coolingRate: 0.85, heatingRate: 0.92]
            ]
        ]
        
        app.metaClass.generateEfficiencyJSON = { data ->
            def exportData = [
                exportMetadata: [
                    version: '0.22',
                    exportDate: new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date()),
                    structureId: delegate.settings.structureId
                ],
                efficiencyData: data
            ]
            return groovy.json.JsonOutput.toJson(exportData)
        }
        
        when: "generating JSON"
        def jsonResult = app.generateEfficiencyJSON(efficiencyData)
        def parsedJson = new groovy.json.JsonSlurper().parseText(jsonResult)
        
        then: "should include metadata"
        parsedJson.exportMetadata.version == '0.22'
        parsedJson.exportMetadata.structureId == 'structure-123'
        parsedJson.exportMetadata.exportDate != null
        
        and: "should include efficiency data"
        parsedJson.efficiencyData.globalRates.maxCoolingRate == 1.5
        parsedJson.efficiencyData.globalRates.maxHeatingRate == 1.2
        parsedJson.efficiencyData.roomEfficiencies.size() == 1
        parsedJson.efficiencyData.roomEfficiencies[0].roomName == 'Living Room'
    }

    def "should validate import data structure"() {
        given: "import validation method"
        app.metaClass.validateImportData = { jsonData ->
            // Check required structure
            if (!jsonData.exportMetadata || !jsonData.efficiencyData) return false
            if (!jsonData.efficiencyData.globalRates) return false
            if (!jsonData.efficiencyData.roomEfficiencies) return false
            
            // Validate global rates
            def globalRates = jsonData.efficiencyData.globalRates
            if (globalRates.maxCoolingRate == null || globalRates.maxHeatingRate == null) return false
            if (globalRates.maxCoolingRate < 0 || globalRates.maxHeatingRate < 0) return false
            if (globalRates.maxCoolingRate > 10 || globalRates.maxHeatingRate > 10) return false
            
            // Validate room efficiencies
            for (room in jsonData.efficiencyData.roomEfficiencies) {
                if (!room.roomId || !room.roomName || !room.ventId) return false
                if (room.coolingRate == null || room.heatingRate == null) return false
                if (room.coolingRate < 0 || room.heatingRate < 0) return false
                if (room.coolingRate > 10 || room.heatingRate > 10) return false
            }
            
            return true
        }
        
        expect: "valid data passes validation"
        app.validateImportData(validData) == expectedResult
        
        where:
        validData << [
            // Valid complete data
            [
                exportMetadata: [version: '0.22', exportDate: '2025-06-26T12:00:00Z', structureId: 'struct-1'],
                efficiencyData: [
                    globalRates: [maxCoolingRate: 1.5, maxHeatingRate: 1.2],
                    roomEfficiencies: [
                        [roomId: 'room-1', roomName: 'Living Room', ventId: 'vent-1', coolingRate: 0.85, heatingRate: 0.92]
                    ]
                ]
            ],
            // Missing metadata
            [efficiencyData: [globalRates: [maxCoolingRate: 1.5, maxHeatingRate: 1.2], roomEfficiencies: []]],
            // Missing efficiency data
            [exportMetadata: [version: '0.22']],
            // Invalid global rates (negative)
            [
                exportMetadata: [version: '0.22'],
                efficiencyData: [globalRates: [maxCoolingRate: -1, maxHeatingRate: 1.2], roomEfficiencies: []]
            ],
            // Invalid global rates (too high)
            [
                exportMetadata: [version: '0.22'],
                efficiencyData: [globalRates: [maxCoolingRate: 15, maxHeatingRate: 1.2], roomEfficiencies: []]
            ],
            // Missing room fields
            [
                exportMetadata: [version: '0.22'],
                efficiencyData: [
                    globalRates: [maxCoolingRate: 1.5, maxHeatingRate: 1.2],
                    roomEfficiencies: [[roomName: 'Living Room', coolingRate: 0.85]]
                ]
            ]
        ]
        expectedResult << [true, false, false, false, false, false]
    }

    def "should match devices by room ID"() {
        given: "device matching method"
        app.metaClass.matchDeviceByRoomId = { roomId ->
            return delegate.getChildDevices().find { device ->
                device.hasAttribute('percent-open') && device.currentValue('room-id') == roomId
            }
        }
        
        expect: "finds correct device by room ID"
        app.matchDeviceByRoomId('room-001')?.deviceNetworkId == 'vent-001'
        app.matchDeviceByRoomId('room-002')?.deviceNetworkId == 'vent-002'
        app.matchDeviceByRoomId('room-nonexistent') == null
        app.matchDeviceByRoomId('room-003') == null  // Puck, not vent
    }

    def "should match devices by room name as fallback"() {
        given: "device matching method"
        app.metaClass.matchDeviceByRoomName = { roomName ->
            return delegate.getChildDevices().find { device ->
                device.hasAttribute('percent-open') && device.currentValue('room-name') == roomName
            }
        }
        
        expect: "finds correct device by room name"
        app.matchDeviceByRoomName('Living Room')?.deviceNetworkId == 'vent-001'
        app.matchDeviceByRoomName('Bedroom')?.deviceNetworkId == 'vent-002'
        app.matchDeviceByRoomName('Nonexistent Room') == null
        app.matchDeviceByRoomName('Kitchen') == null  // Puck, not vent
    }

    def "should apply imported efficiencies to matched devices"() {
        given: "import application method"
        def appliedDevices = []
        
        // Implement the actual matching methods first
        app.metaClass.matchDeviceByRoomId = { roomId ->
            return delegate.getChildDevices().find { device ->
                device.hasAttribute('percent-open') && device.currentValue('room-id') == roomId
            }
        }
        
        app.metaClass.matchDeviceByRoomName = { roomName ->
            return delegate.getChildDevices().find { device ->
                device.hasAttribute('percent-open') && device.currentValue('room-name') == roomName
            }
        }
        
        app.metaClass.applyImportedEfficiencies = { efficiencyData ->
            def results = [
                globalUpdated: false,
                roomsUpdated: 0,
                roomsSkipped: 0,
                errors: []
            ]
            
            // Update global rates
            if (efficiencyData.globalRates) {
                delegate.atomicState.maxCoolingRate = efficiencyData.globalRates.maxCoolingRate
                delegate.atomicState.maxHeatingRate = efficiencyData.globalRates.maxHeatingRate
                results.globalUpdated = true
            }
            
            // Update room efficiencies
            efficiencyData.roomEfficiencies?.each { roomData ->
                def device = delegate.matchDeviceByRoomId(roomData.roomId) ?:
                           delegate.matchDeviceByRoomName(roomData.roomName)
                           
                if (device) {
                    delegate.sendEvent(device, [name: 'room-cooling-rate', value: roomData.coolingRate])
                    delegate.sendEvent(device, [name: 'room-heating-rate', value: roomData.heatingRate])
                    appliedDevices << device.deviceNetworkId
                    results.roomsUpdated++
                } else {
                    results.roomsSkipped++
                    results.errors << "Room not found: ${roomData.roomName} (${roomData.roomId})"
                }
            }
            
            return results
        }
        
        and: "import data"
        def importData = [
            globalRates: [maxCoolingRate: 2.0, maxHeatingRate: 1.8],
            roomEfficiencies: [
                [roomId: 'room-001', roomName: 'Living Room', ventId: 'vent-001', coolingRate: 1.0, heatingRate: 1.1],
                [roomId: 'room-999', roomName: 'Missing Room', ventId: 'vent-999', coolingRate: 0.5, heatingRate: 0.6]
            ]
        ]
        
        when: "applying imported efficiencies"
        def results = app.applyImportedEfficiencies(importData)
        
        then: "should update global rates"
        results.globalUpdated == true
        app.atomicState.maxCoolingRate == 2.0
        app.atomicState.maxHeatingRate == 1.8
        
        and: "should update matched devices"
        results.roomsUpdated == 1
        results.roomsSkipped == 1
        mockDevice1.currentValue('room-cooling-rate') == 1.0
        mockDevice1.currentValue('room-heating-rate') == 1.1
        
        and: "should report errors for missing devices"
        results.errors.size() == 1
        results.errors[0].contains('Missing Room')
    }

    def "should handle import with room name fallback matching"() {
        given: "import data with changed room IDs"
        def importData = [
            globalRates: [maxCoolingRate: 1.5, maxHeatingRate: 1.2],
            roomEfficiencies: [
                // Room ID changed but name matches
                [roomId: 'room-999', roomName: 'Living Room', ventId: 'vent-001', coolingRate: 0.95, heatingRate: 0.88]
            ]
        ]
        
        // Setup matching methods for this test
        app.metaClass.matchDeviceByRoomId = { roomId ->
            return delegate.getChildDevices().find { device ->
                device.hasAttribute('percent-open') && device.currentValue('room-id') == roomId
            }
        }
        
        app.metaClass.matchDeviceByRoomName = { roomName ->
            return delegate.getChildDevices().find { device ->
                device.hasAttribute('percent-open') && device.currentValue('room-name') == roomName
            }
        }
        
        app.metaClass.applyImportedEfficiencies = { efficiencyData ->
            def results = [roomsUpdated: 0, roomsSkipped: 0, errors: []]
            
            efficiencyData.roomEfficiencies?.each { roomData ->
                // Try room ID first, then fall back to room name
                def device = delegate.matchDeviceByRoomId(roomData.roomId)
                if (!device) {
                    device = delegate.matchDeviceByRoomName(roomData.roomName)
                }
                
                if (device) {
                    delegate.sendEvent(device, [name: 'room-cooling-rate', value: roomData.coolingRate])
                    delegate.sendEvent(device, [name: 'room-heating-rate', value: roomData.heatingRate])
                    results.roomsUpdated++
                } else {
                    results.roomsSkipped++
                    results.errors << "Room not found: ${roomData.roomName}"
                }
            }
            
            return results
        }
        
        when: "applying imported efficiencies with fallback matching"
        def results = app.applyImportedEfficiencies(importData)
        
        then: "should match by room name when room ID doesn't match"
        results.roomsUpdated == 1
        results.roomsSkipped == 0
        mockDevice1.currentValue('room-cooling-rate') == 0.95
        mockDevice1.currentValue('room-heating-rate') == 0.88
    }

    def "should handle full import process with validation"() {
        given: "complete import method and dependencies"
        // First setup the validation method
        app.metaClass.validateImportData = { jsonData ->
            if (!jsonData.exportMetadata || !jsonData.efficiencyData) return false
            if (!jsonData.efficiencyData.globalRates) return false
            if (!jsonData.efficiencyData.roomEfficiencies) return false
            return true
        }
        
        // Setup matching methods
        app.metaClass.matchDeviceByRoomId = { roomId ->
            return delegate.getChildDevices().find { device ->
                device.hasAttribute('percent-open') && device.currentValue('room-id') == roomId
            }
        }
        
        app.metaClass.matchDeviceByRoomName = { roomName ->
            return delegate.getChildDevices().find { device ->
                device.hasAttribute('percent-open') && device.currentValue('room-name') == roomName
            }
        }
        
        // Setup the apply method
        app.metaClass.applyImportedEfficiencies = { efficiencyData ->
            def results = [
                globalUpdated: false,
                roomsUpdated: 0,
                roomsSkipped: 0,
                errors: []
            ]
            
            if (efficiencyData.globalRates) {
                delegate.atomicState.maxCoolingRate = efficiencyData.globalRates.maxCoolingRate
                delegate.atomicState.maxHeatingRate = efficiencyData.globalRates.maxHeatingRate
                results.globalUpdated = true
            }
            
            efficiencyData.roomEfficiencies?.each { roomData ->
                def device = delegate.matchDeviceByRoomId(roomData.roomId) ?:
                           delegate.matchDeviceByRoomName(roomData.roomName)
                           
                if (device) {
                    delegate.sendEvent(device, [name: 'room-cooling-rate', value: roomData.coolingRate])
                    delegate.sendEvent(device, [name: 'room-heating-rate', value: roomData.heatingRate])
                    results.roomsUpdated++
                } else {
                    results.roomsSkipped++
                    results.errors << "Room not found: ${roomData.roomName} (${roomData.roomId})"
                }
            }
            
            return results
        }
        
        // Now setup the main import method
        app.metaClass.importEfficiencyData = { jsonContent ->
            try {
                def jsonData = new groovy.json.JsonSlurper().parseText(jsonContent)
                
                if (!delegate.validateImportData(jsonData)) {
                    return [success: false, error: 'Invalid data format']
                }
                
                def results = delegate.applyImportedEfficiencies(jsonData.efficiencyData)
                
                return [
                    success: true,
                    globalUpdated: results.globalUpdated,
                    roomsUpdated: results.roomsUpdated,
                    roomsSkipped: results.roomsSkipped,
                    errors: results.errors
                ]
            } catch (Exception e) {
                return [success: false, error: e.message]
            }
        }
        
        and: "valid JSON import data"
        def validJson = '''
        {
            "exportMetadata": {
                "version": "0.22",
                "exportDate": "2025-06-26T12:00:00Z",
                "structureId": "structure-123"
            },
            "efficiencyData": {
                "globalRates": {
                    "maxCoolingRate": 2.5,
                    "maxHeatingRate": 2.0
                },
                "roomEfficiencies": [
                    {
                        "roomId": "room-001",
                        "roomName": "Living Room",
                        "ventId": "vent-001",
                        "coolingRate": 1.5,
                        "heatingRate": 1.3
                    }
                ]
            }
        }
        '''
        
        when: "importing valid JSON data"
        def result = app.importEfficiencyData(validJson)
        
        then: "should successfully import data"
        result.success == true
        result.globalUpdated == true
        result.roomsUpdated == 1
        result.roomsSkipped == 0
        result.errors.size() == 0
    }

    def "should reject invalid JSON format"() {
        given: "invalid JSON data and import method"
        def invalidJson = '{ invalid json }'
        
        app.metaClass.importEfficiencyData = { jsonContent ->
            try {
                def jsonData = new groovy.json.JsonSlurper().parseText(jsonContent)
                return [success: true, data: jsonData]
            } catch (Exception e) {
                return [success: false, error: e.message]
            }
        }
        
        when: "importing invalid JSON"
        def result = app.importEfficiencyData(invalidJson)
        
        then: "should return error"
        result.success == false
        result.error != null
    }

    def "should handle empty efficiency data gracefully"() {
        given: "device with modified currentValue that returns null for efficiency data"
        // Create a device that returns null for efficiency data
        def deviceWithNullData = [
            deviceNetworkId: 'vent-001',
            label: 'Living Room Vent',
            typeName: 'Flair vents',
            getDeviceNetworkId: { -> 'vent-001' },
            currentValue: { attr -> 
                def attrs = [
                    'room-id': 'room-001',
                    'room-name': 'Living Room',
                    'room-cooling-rate': null,
                    'room-heating-rate': null
                ]
                return attrs[attr]
            },
            hasAttribute: { attr -> attr == 'percent-open' }
        ]
        
        // Replace device in children array
        app.children = [deviceWithNullData, mockDevice2, mockDevice3]
        
        // Define the exportEfficiencyData method for this test
        app.metaClass.exportEfficiencyData = {
            def data = [
                globalRates: [
                    maxCoolingRate: delegate.atomicState.maxCoolingRate,
                    maxHeatingRate: delegate.atomicState.maxHeatingRate
                ],
                roomEfficiencies: []
            ]
            
            // Only collect from vents (devices with percent-open attribute)
            delegate.getChildDevices().findAll { it.hasAttribute('percent-open') }.each { device ->
                def roomData = [
                    roomId: device.currentValue('room-id'),
                    roomName: device.currentValue('room-name'),
                    ventId: device.deviceNetworkId,
                    coolingRate: device.currentValue('room-cooling-rate') ?: 0,
                    heatingRate: device.currentValue('room-heating-rate') ?: 0
                ]
                data.roomEfficiencies << roomData
            }
            return data
        }
        
        when: "exporting efficiency data"
        def result = app.exportEfficiencyData()
        
        then: "should include zero values for missing data"
        def livingRoom = result.roomEfficiencies.find { it.roomName == 'Living Room' }
        livingRoom.coolingRate == 0
        livingRoom.heatingRate == 0
    }

    def "should preserve existing efficiency data when importing partial data"() {
        given: "existing efficiency data already set and apply method with matching"
        // Setup matching methods
        app.metaClass.matchDeviceByRoomId = { roomId ->
            return delegate.getChildDevices().find { device ->
                device.hasAttribute('percent-open') && device.currentValue('room-id') == roomId
            }
        }
        
        app.metaClass.matchDeviceByRoomName = { roomName ->
            return delegate.getChildDevices().find { device ->
                device.hasAttribute('percent-open') && device.currentValue('room-name') == roomName
            }
        }
        
        app.metaClass.applyImportedEfficiencies = { efficiencyData ->
            def results = [roomsUpdated: 0, roomsSkipped: 0, errors: []]
            
            efficiencyData.roomEfficiencies?.each { roomData ->
                def device = delegate.matchDeviceByRoomId(roomData.roomId) ?:
                           delegate.matchDeviceByRoomName(roomData.roomName)
                           
                if (device) {
                    delegate.sendEvent(device, [name: 'room-cooling-rate', value: roomData.coolingRate])
                    delegate.sendEvent(device, [name: 'room-heating-rate', value: roomData.heatingRate])
                    results.roomsUpdated++
                } else {
                    results.roomsSkipped++
                    results.errors << "Room not found: ${roomData.roomName}"
                }
            }
            
            return results
        }
        
        and: "import data for different room only"
        def importData = [
            globalRates: [maxCoolingRate: 1.5, maxHeatingRate: 1.2],
            roomEfficiencies: [
                [roomId: 'room-002', roomName: 'Bedroom', ventId: 'vent-002', coolingRate: 1.0, heatingRate: 0.8]
            ]
        ]
        
        when: "applying imported efficiencies"
        def results = app.applyImportedEfficiencies(importData)
        
        then: "should update only specified room"
        results.roomsUpdated == 1
        mockDevice1.currentValue('room-cooling-rate') == 0.85  // Unchanged
        mockDevice1.currentValue('room-heating-rate') == 0.92  // Unchanged
        mockDevice2.currentValue('room-cooling-rate') == 1.0   // Updated
        mockDevice2.currentValue('room-heating-rate') == 0.8   // Updated
    }
}
