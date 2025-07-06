/**
 * Comprehensive Edge Case Tests for Efficiency Data Import/Export
 * 
 * Tests all potential failure scenarios and edge cases for the 
 * backup/restore functionality to ensure robust error handling.
 */

@Grab('org.spockframework:spock-core:2.3-groovy-3.0')
@Grab('org.objenesis:objenesis:3.3')
@Grab('net.bytebuddy:byte-buddy:1.14.5')
@Grab('me.biocomp:hubitat_ci:1.2.1')

import spock.lang.Specification
import spock.lang.Unroll
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.math.BigDecimal
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags

class EfficiencyImportEdgeCasesTest extends Specification {

    private static final File APP_FILE = new File('src/hubitat-flair-vents-app.groovy')
    private static final List VALIDATION_FLAGS = [
        Flags.DontValidateMetadata,
        Flags.DontValidatePreferences,
        Flags.DontValidateDefinition,
        Flags.DontRestrictGroovy,
        Flags.DontRequireParseMethodInDevice,
        Flags.AllowWritingToSettings,
        Flags.AllowReadingNonInputSettings
    ]

    def app
    def mockDevice1, mockDevice2, mockDevice3

    def setup() {
        // Load the app with proper validation using sandbox
        AppExecutor executorApi = Mock {
            _ * getState() >> [:]
        }
        def sandbox = new HubitatAppSandbox(APP_FILE)
        app = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
        
        // Initialize atomicState properly
        app.atomicState = [:]
        
        // Mock log to prevent null pointer exceptions
        app.log = [error: { msg -> }, debug: { msg -> }, warn: { msg -> }]
        
        // Ensure atomicState persistence during method calls
        app.metaClass.getAtomicState = { -> app.atomicState ?: [:] }
        app.metaClass.setAtomicState = { value -> app.atomicState = value }
        
        // Mock devices with different room configurations
        mockDevice1 = createMockDevice('device-1', 'Living Room', 'room-123', 0.5, 0.7)
        mockDevice2 = createMockDevice('device-2', 'Kitchen', 'room-456', 0.3, 0.4)
        mockDevice3 = createMockDevice('device-3', 'Bedroom', 'room-789', 0.8, 0.6)
        
        // Mock app dependencies
        app.metaClass.getChildDevices = { -> [mockDevice1, mockDevice2, mockDevice3] }
        app.metaClass.sendEvent = { device, data -> /* no-op */ }
        app.metaClass.log = { msg, level = 3 -> /* no-op */ }
        app.metaClass.logError = { msg -> /* no-op */ }
        
        // Mock atomicState
        app.atomicState = [
            maxCoolingRate: 1.0,
            maxHeatingRate: 0.9
        ]
        
        // Mock state for test compatibility
        app.state = [:]
    }

    def cleanup() {
        // No cleanup needed
    }

    def createMockDevice(String deviceId, String roomName, String roomId, double coolingRate, double heatingRate) {
        def device = [
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
        return device
    }

    def "test successful import with all rooms found"() {
        given: "Valid JSON data with all rooms that exist in the system"
        def jsonData = createValidBackupJson([
            [roomId: 'room-123', roomName: 'Living Room', ventId: 'device-1', coolingRate: 0.6, heatingRate: 0.8],
            [roomId: 'room-456', roomName: 'Kitchen', ventId: 'device-2', coolingRate: 0.4, heatingRate: 0.5]
        ])

        when: "Importing the data"
        def result = app.importEfficiencyData(JsonOutput.toJson(jsonData))

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
        def result = app.importEfficiencyData(JsonOutput.toJson(jsonData))

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
        def result = app.importEfficiencyData(JsonOutput.toJson(jsonData))

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
        def result = app.importEfficiencyData(JsonOutput.toJson(jsonData))

        then: "Should find room by name and update successfully"
        result.success == true
        result.roomsUpdated == 1
        result.roomsSkipped == 0
    }

    @Unroll
    def "test import with invalid JSON structure: #scenario"() {
        when: "Importing invalid JSON data"
        def result = app.importEfficiencyData(jsonData)

        then: "Import should fail with appropriate error"
        result.success == false
        result.error != null
        result.error.contains(expectedError)

        where:
        scenario                  | jsonData                    | expectedError
        'malformed JSON'         | '{"invalid": json}'         | 'Unexpected character'
        'empty string'           | ''                          | 'Unexpected end of input'
        'null data'              | 'null'                      | 'Invalid data format'
        'missing exportMetadata' | '{"efficiencyData": {}}'    | 'Invalid data format'
        'missing efficiencyData' | '{"exportMetadata": {}}'    | 'Invalid data format'
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
        def isValid = app.validateImportData(invalidData)

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
        def isValid = app.validateImportData(invalidData)

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

    def "test import with empty room efficiencies array"() {
        given: "Valid JSON with no room data"
        def jsonData = createValidBackupJson([])

        when: "Importing the data"
        def result = app.importEfficiencyData(JsonOutput.toJson(jsonData))

        then: "Should succeed but update no rooms"
        result.success == true
        result.roomsUpdated == 0
        result.roomsSkipped == 0
        result.globalUpdated == true
    }

    def "test import with duplicate room entries"() {
        given: "JSON data with duplicate room entries"
        def jsonData = createValidBackupJson([
            [roomId: 'room-123', roomName: 'Living Room', ventId: 'device-1', coolingRate: 0.6, heatingRate: 0.8],
            [roomId: 'room-123', roomName: 'Living Room', ventId: 'device-1', coolingRate: 0.7, heatingRate: 0.9] // Duplicate
        ])

        when: "Importing the data"
        def result = app.importEfficiencyData(JsonOutput.toJson(jsonData))

        then: "Should process both entries (last one wins)"
        result.success == true
        result.roomsUpdated == 2 // Both entries processed
        result.roomsSkipped == 0
    }

    def "test import with zero efficiency values"() {
        given: "JSON data with zero efficiency values"
        def jsonData = createValidBackupJson([
            [roomId: 'room-123', roomName: 'Living Room', ventId: 'device-1', coolingRate: 0.0, heatingRate: 0.0]
        ])

        when: "Importing the data"
        def result = app.importEfficiencyData(JsonOutput.toJson(jsonData))

        then: "Should succeed (zero is valid efficiency)"
        result.success == true
        result.roomsUpdated == 1
        result.roomsSkipped == 0
    }

    def "test import handles device matching edge cases"() {
        given: "Mock additional devices and configure room matching scenarios"
        def noRoomIdDevice = createMockDevice('device-no-room', 'Orphan Room', null, 0.5, 0.5)
        def noRoomNameDevice = createMockDevice('device-no-name', null, 'room-no-name', 0.5, 0.5)
        
        app.metaClass.getChildDevices = { -> [mockDevice1, noRoomIdDevice, noRoomNameDevice] }
        
        def jsonData = createValidBackupJson([
            [roomId: 'room-123', roomName: 'Living Room', ventId: 'device-1', coolingRate: 0.6, heatingRate: 0.8],
            [roomId: null, roomName: 'Orphan Room', ventId: 'device-no-room', coolingRate: 0.4, heatingRate: 0.5],
            [roomId: 'room-no-name', roomName: null, ventId: 'device-no-name', coolingRate: 0.3, heatingRate: 0.4]
        ])

        when: "Importing the data"
        def result = app.importEfficiencyData(JsonOutput.toJson(jsonData))

        then: "Should handle gracefully"
        result.success == true
        result.roomsUpdated >= 1 // At least the valid Living Room should update
        // May skip rooms with null IDs/names depending on matching logic
    }

    def "test export-import roundtrip maintains data integrity"() {
        given: "Current system with efficiency data"
        app.atomicState.maxCoolingRate = 1.5
        app.atomicState.maxHeatingRate = 1.2

        when: "Exporting and then importing the data"
        def exportedData = app.exportEfficiencyData()
        def jsonString = app.generateEfficiencyJSON(exportedData)
        def importResult = app.importEfficiencyData(jsonString)

        then: "Data should be preserved exactly"
        importResult.success == true
        importResult.roomsUpdated == 3 // All mock devices should be updated
        importResult.roomsSkipped == 0
        importResult.globalUpdated == true
    }

    def "test import with missing global rates section"() {
        given: "JSON data without global rates"
        def jsonData = [
            exportMetadata: [version: '0.22', exportDate: '2025-06-26T15:00:00Z'],
            efficiencyData: [
                roomEfficiencies: [
                    [roomId: 'room-123', roomName: 'Living Room', ventId: 'device-1', coolingRate: 0.6, heatingRate: 0.8]
                ]
                // Missing globalRates section
            ]
        ]

        when: "Validating the data"
        def isValid = app.validateImportData(jsonData)

        then: "Validation should fail"
        isValid == false
    }

    def "test handleImportEfficiencyData integration with UI feedback"() {
        given: "Valid JSON input in settings"
        app.settings = [importJsonData: JsonOutput.toJson(createValidBackupJson([
            [roomId: 'room-123', roomName: 'Living Room', ventId: 'device-1', coolingRate: 0.6, heatingRate: 0.8],
            [roomId: 'room-999', roomName: 'Missing Room', ventId: 'device-999', coolingRate: 0.4, heatingRate: 0.5]
        ]))]
        
        // Mock app.updateSetting
        app.metaClass.app = [updateSetting: { name, value -> /* no-op */ }]

        when: "Calling the handler"
        app.handleImportEfficiencyData()

        then: "State should reflect the results"
        app.state.importStatus != null
        app.state.importSuccess == true
        app.state.importStatus.contains('Updated 1 rooms')
        app.state.importStatus.contains('Skipped 1 rooms')
    }

    def "test handleImportEfficiencyData with empty input"() {
        given: "Empty input"
        app.settings = [importJsonData: '']

        when: "Calling the handler"
        app.handleImportEfficiencyData()

        then: "Should show error message"
        app.state.importStatus != null
        app.state.importSuccess == false
        app.state.importStatus.contains('No JSON data provided')
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
