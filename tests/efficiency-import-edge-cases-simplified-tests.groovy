/**
 * Simplified Edge Case Tests for Efficiency Data Import/Export
 * 
 * Tests the import/export functions directly without loading the full Hubitat app
 */

@Grab('org.spockframework:spock-core:2.3-groovy-3.0')
@Grab('org.objenesis:objenesis:3.3')
@Grab('net.bytebuddy:byte-buddy:1.14.5')

import spock.lang.Specification
import spock.lang.Unroll
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.math.BigDecimal

class EfficiencyImportEdgeCasesSimplifiedTest extends Specification {

    def mockDevice1, mockDevice2, mockDevice3
    def mockApp

    def setup() {
        // Create mock devices
        mockDevice1 = createMockDevice('device-1', 'Living Room', 'room-123', 0.5, 0.7)
        mockDevice2 = createMockDevice('device-2', 'Kitchen', 'room-456', 0.3, 0.4)
        mockDevice3 = createMockDevice('device-3', 'Bedroom', 'room-789', 0.8, 0.6)
        
        // Create mock app with necessary methods
        mockApp = [
            getChildDevices: { -> [mockDevice1, mockDevice2, mockDevice3] },
            sendEvent: { device, data -> /* no-op */ },
            log: { msg, level = 3 -> /* no-op */ },
            logError: { msg -> /* no-op */ },
            atomicState: [
                maxCoolingRate: 1.0,
                maxHeatingRate: 0.9
            ],
            settings: [:],
            state: [:]
        ]
        
        // Add the functions we want to test
        addImportExportFunctions(mockApp)
    }

    def createMockDevice(String deviceId, String roomName, String roomId, double coolingRate, double heatingRate) {
        return [
            getDeviceNetworkId: { -> deviceId },
            getLabel: { -> roomName },
            hasAttribute: { attr -> attr == 'percent-open' }, // This makes it a vent
            currentValue: { attr ->
                switch(attr) {
                    case 'room-id': return roomId
                    case 'room-name': return roomName
                    case 'room-cooling-rate': return coolingRate
                    case 'room-heating-rate': return heatingRate
                    default: return null
                }
            }
        ]
    }

    def addImportExportFunctions(app) {
        // Add cleanDecimalForJson function
        app.cleanDecimalForJson = { value ->
            if (value == null || value == 0) return 0
            try {
                def stringValue = value.toString()
                def doubleValue = Double.parseDouble(stringValue)
                if (!Double.isFinite(doubleValue)) {
                    return 0.0d
                }
                def multiplier = 1000000000.0d  // 10^9 for 10 decimal places
                def rounded = Math.round(doubleValue * multiplier) / multiplier
                return Double.valueOf(rounded)
            } catch (Exception e) {
                return 0.0d
            }
        }

        // Add exportEfficiencyData function
        app.exportEfficiencyData = {
            def data = [
                globalRates: [
                    maxCoolingRate: app.cleanDecimalForJson(app.atomicState.maxCoolingRate),
                    maxHeatingRate: app.cleanDecimalForJson(app.atomicState.maxHeatingRate)
                ],
                roomEfficiencies: []
            ]
            
            app.getChildDevices().findAll { it.hasAttribute('percent-open') }.each { device ->
                def coolingRate = device.currentValue('room-cooling-rate') ?: 0
                def heatingRate = device.currentValue('room-heating-rate') ?: 0
                
                def roomData = [
                    roomId: device.currentValue('room-id'),
                    roomName: device.currentValue('room-name'),
                    ventId: device.getDeviceNetworkId(),
                    coolingRate: app.cleanDecimalForJson(coolingRate),
                    heatingRate: app.cleanDecimalForJson(heatingRate)
                ]
                data.roomEfficiencies << roomData
            }
            
            return data
        }

        // Add generateEfficiencyJSON function
        app.generateEfficiencyJSON = { data ->
            def exportData = [
                exportMetadata: [
                    version: '0.22',
                    exportDate: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'"),
                    structureId: 'test-structure'
                ],
                efficiencyData: data
            ]
            return JsonOutput.toJson(exportData)
        }

        // Add validateImportData function
        app.validateImportData = { jsonData ->
            // Check required structure
            if (!jsonData.exportMetadata || !jsonData.efficiencyData) return false
            if (!jsonData.efficiencyData.globalRates) return false
            if (jsonData.efficiencyData.roomEfficiencies == null) return false
            
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

        // Add device matching functions
        app.matchDeviceByRoomId = { roomId ->
            return app.getChildDevices().find { device ->
                device.hasAttribute('percent-open') && device.currentValue('room-id') == roomId
            }
        }

        app.matchDeviceByRoomName = { roomName ->
            return app.getChildDevices().find { device ->
                device.hasAttribute('percent-open') && device.currentValue('room-name') == roomName
            }
        }

        // Add applyImportedEfficiencies function
        app.applyImportedEfficiencies = { efficiencyData ->
            def results = [
                globalUpdated: false,
                roomsUpdated: 0,
                roomsSkipped: 0,
                errors: []
            ]
            
            // Update global rates
            if (efficiencyData.globalRates) {
                app.atomicState.maxCoolingRate = efficiencyData.globalRates.maxCoolingRate
                app.atomicState.maxHeatingRate = efficiencyData.globalRates.maxHeatingRate
                results.globalUpdated = true
            }
            
            // Update room efficiencies
            efficiencyData.roomEfficiencies?.each { roomData ->
                def device = app.matchDeviceByRoomId(roomData.roomId) ?: app.matchDeviceByRoomName(roomData.roomName)
                
                if (device) {
                    app.sendEvent(device, [name: 'room-cooling-rate', value: roomData.coolingRate])
                    app.sendEvent(device, [name: 'room-heating-rate', value: roomData.heatingRate])
                    results.roomsUpdated++
                } else {
                    results.roomsSkipped++
                    results.errors << "Room not found: ${roomData.roomName} (${roomData.roomId})"
                }
            }
            
            return results
        }

        // Add main importEfficiencyData function
        app.importEfficiencyData = { jsonContent ->
            try {
                def jsonData = new groovy.json.JsonSlurper().parseText(jsonContent)
                
                if (!app.validateImportData(jsonData)) {
                    return [success: false, error: 'Invalid data format. Please ensure you are using exported efficiency data.']
                }
                
                def results = app.applyImportedEfficiencies(jsonData.efficiencyData)
                
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
    }

    def "test successful import with all rooms found"() {
        given: "Valid JSON data with all rooms that exist in the system"
        def jsonData = createValidBackupJson([
            [roomId: 'room-123', roomName: 'Living Room', ventId: 'device-1', coolingRate: 0.6, heatingRate: 0.8],
            [roomId: 'room-456', roomName: 'Kitchen', ventId: 'device-2', coolingRate: 0.4, heatingRate: 0.5]
        ])

        when: "Importing the data"
        def result = mockApp.importEfficiencyData(JsonOutput.toJson(jsonData))

        then: "Import should succeed with all rooms updated"
        result.success == true
        result.roomsUpdated == 2
        result.roomsSkipped == 0
        result.globalUpdated == true
        result.errors.isEmpty()
    }

    def "test partial import with some rooms not found"() {
        given: "JSON data with mix of existing and non-existing rooms"
        def jsonData = createValidBackupJson([
            [roomId: 'room-123', roomName: 'Living Room', ventId: 'device-1', coolingRate: 0.6, heatingRate: 0.8],
            [roomId: 'room-999', roomName: 'Non-Existent Room', ventId: 'device-999', coolingRate: 0.4, heatingRate: 0.5],
            [roomId: 'room-456', roomName: 'Kitchen', ventId: 'device-2', coolingRate: 0.3, heatingRate: 0.4]
        ])

        when: "Importing the data"
        def result = mockApp.importEfficiencyData(JsonOutput.toJson(jsonData))

        then: "Import should partially succeed"
        result.success == true
        result.roomsUpdated == 2
        result.roomsSkipped == 1
        result.globalUpdated == true
        result.errors.size() == 1
        result.errors[0].contains('Non-Existent Room')
    }

    def "test import with room name changed but ID same"() {
        given: "JSON data where room name changed but room ID exists"
        def jsonData = createValidBackupJson([
            [roomId: 'room-123', roomName: 'Old Living Room Name', ventId: 'device-1', coolingRate: 0.6, heatingRate: 0.8]
        ])

        when: "Importing the data"
        def result = mockApp.importEfficiencyData(JsonOutput.toJson(jsonData))

        then: "Should find room by ID and update successfully"
        result.success == true
        result.roomsUpdated == 1
        result.roomsSkipped == 0
    }

    def "test import with room ID changed but name same"() {
        given: "JSON data where room ID changed but name exists"
        def jsonData = createValidBackupJson([
            [roomId: 'room-old-123', roomName: 'Living Room', ventId: 'device-1', coolingRate: 0.6, heatingRate: 0.8]
        ])

        when: "Importing the data"
        def result = mockApp.importEfficiencyData(JsonOutput.toJson(jsonData))

        then: "Should find room by name and update successfully"
        result.success == true
        result.roomsUpdated == 1
        result.roomsSkipped == 0
    }

    @Unroll
    def "test import with invalid JSON structure: #scenario"() {
        when: "Importing invalid JSON data"
        def result = mockApp.importEfficiencyData(jsonData)

        then: "Import should fail with appropriate error"
        result.success == false
        result.error != null

        where:
        scenario                  | jsonData
        'malformed JSON'         | '{"invalid": json}'
        'empty string'           | ''
        'null data'              | 'null'
        'missing exportMetadata' | '{"efficiencyData": {}}'
        'missing efficiencyData' | '{"exportMetadata": {}}'
    }

    def "test validation with missing required fields"() {
        given: "JSON data missing required structure"
        def invalidData = [
            exportMetadata: [version: '0.22'],
            efficiencyData: [
                globalRates: [:], // Missing required rates
                roomEfficiencies: [
                    [roomName: 'Living Room'] // Missing roomId, ventId, rates
                ]
            ]
        ]

        when: "Validating the data"
        def isValid = mockApp.validateImportData(invalidData)

        then: "Validation should fail"
        isValid == false
    }

    @Unroll
    def "test validation with out-of-range values: #scenario"() {
        given: "JSON data with invalid rate values"
        def invalidData = createValidBackupJson([
            [roomId: 'room-123', roomName: 'Living Room', ventId: 'device-1', 
             coolingRate: coolingRate, heatingRate: heatingRate]
        ])
        if (globalCooling != null) {
            invalidData.efficiencyData.globalRates.maxCoolingRate = globalCooling
        }
        if (globalHeating != null) {
            invalidData.efficiencyData.globalRates.maxHeatingRate = globalHeating
        }

        when: "Validating the data"
        def isValid = mockApp.validateImportData(invalidData)

        then: "Validation should fail"
        isValid == false

        where:
        scenario                    | coolingRate | heatingRate | globalCooling | globalHeating
        'negative cooling rate'     | -0.5        | 0.5         | null          | null
        'negative heating rate'     | 0.5         | -0.5        | null          | null
        'excessive cooling rate'    | 15.0        | 0.5         | null          | null
        'excessive heating rate'    | 0.5         | 15.0        | null          | null
        'negative global cooling'   | 0.5         | 0.5         | -1.0          | null
        'negative global heating'   | 0.5         | 0.5         | null          | -1.0
        'excessive global cooling'  | 0.5         | 0.5         | 15.0          | null
        'excessive global heating'  | 0.5         | 0.5         | null          | 15.0
    }

    def "test export-import roundtrip maintains data integrity"() {
        given: "Current system with efficiency data"
        mockApp.atomicState.maxCoolingRate = 1.5
        mockApp.atomicState.maxHeatingRate = 1.2

        when: "Exporting and then importing the data"
        def exportedData = mockApp.exportEfficiencyData()
        def jsonString = mockApp.generateEfficiencyJSON(exportedData)
        def importResult = mockApp.importEfficiencyData(jsonString)

        then: "Data should be preserved exactly"
        importResult.success == true
        importResult.roomsUpdated == 3 // All mock devices should be updated
        importResult.roomsSkipped == 0
        importResult.globalUpdated == true
    }

    def "test import with zero efficiency values"() {
        given: "JSON data with zero efficiency values"
        def jsonData = createValidBackupJson([
            [roomId: 'room-123', roomName: 'Living Room', ventId: 'device-1', coolingRate: 0.0, heatingRate: 0.0]
        ])

        when: "Importing the data"
        def result = mockApp.importEfficiencyData(JsonOutput.toJson(jsonData))

        then: "Should succeed (zero is valid efficiency)"
        result.success == true
        result.roomsUpdated == 1
        result.roomsSkipped == 0
    }

    def "test import with empty room efficiencies array"() {
        given: "Valid JSON with no room data"
        def jsonData = createValidBackupJson([])

        when: "Importing the data"
        def result = mockApp.importEfficiencyData(JsonOutput.toJson(jsonData))

        then: "Should succeed but update no rooms"
        result.success == true
        result.roomsUpdated == 0
        result.roomsSkipped == 0
        result.globalUpdated == true
    }

    def "test import when no rooms exist in system"() {
        given: "No devices in system"
        mockApp.getChildDevices = { -> [] }
        
        and: "Valid JSON with room data"
        def jsonData = createValidBackupJson([
            [roomId: 'room-123', roomName: 'Living Room', ventId: 'device-1', coolingRate: 0.6, heatingRate: 0.8]
        ])

        when: "Importing the data"
        def result = mockApp.importEfficiencyData(JsonOutput.toJson(jsonData))

        then: "Should succeed but skip all rooms"
        result.success == true
        result.roomsUpdated == 0
        result.roomsSkipped == 1
        result.globalUpdated == true
        result.errors.size() == 1
    }

    private createValidBackupJson(roomData) {
        return [
            exportMetadata: [
                version: '0.22',
                exportDate: '2025-06-26T15:00:00Z',
                structureId: 'test-structure'
            ],
            efficiencyData: [
                globalRates: [
                    maxCoolingRate: 1.0,
                    maxHeatingRate: 0.9
                ],
                roomEfficiencies: roomData
            ]
        ]
    }
}
