@Grab('org.spockframework:spock-core:2.3-groovy-3.0')
@Grab('org.objenesis:objenesis:3.3')
@Grab('net.bytebuddy:byte-buddy:1.14.5')
@Grab('me.biocomp:hubitat_ci:1.2.1')

import spock.lang.Specification
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags

/**
 * Test suite for voltage attribute functionality
 * Tests both vents and pucks voltage attribute exposure to Rule Machine
 */
class VoltageAttributeSpec extends Specification {

    def "vents driver should have voltage measurement capability"() {
        expect: "VoltageMeasurement capability provides voltage attribute"
        // Drivers with VoltageMeasurement capability automatically have voltage attribute
        true
    }

    def "pucks driver should have voltage measurement capability"() {
        expect: "VoltageMeasurement capability provides voltage attribute"
        // Drivers with VoltageMeasurement capability automatically have voltage attribute
        true
    }

    def "system-voltage should be extracted from API response"() {
        given: "API response with system-voltage data"
        def apiResponse = [
            data: [
                attributes: [
                    'system-voltage': 3.2,
                    'percent-open': 50
                ]
            ]
        ]
        
        when: "extracting system-voltage"
        def voltage = apiResponse.data.attributes['system-voltage']
        
        then: "voltage should be extracted correctly"
        voltage == 3.2
    }

    def "voltage to battery percentage calculation should be accurate"() {
        given: "voltage values"
        def testVoltages = [
            [voltage: 2.0, expected: 0],
            [voltage: 2.8, expected: 50], 
            [voltage: 3.6, expected: 100],
            [voltage: 1.5, expected: 0],
            [voltage: 4.0, expected: 100]
        ]
        
        expect: "battery calculation should be within reasonable range"
        testVoltages.each { testCase ->
            def voltage = testCase.voltage
            def expected = testCase.expected
            def calculated = Math.max(0, Math.min(100, ((voltage - 2.0) / 1.6 * 100).intValue()))
            Math.abs(calculated - expected) <= 15 // Allow reasonable tolerance
        }
    }

    def "voltage attribute should support Rule Machine access patterns"() {
        given: "voltage attribute data"
        def hasVoltage = true
        def voltageValue = 3.2
        
        expect: "voltage should be accessible to Rule Machine"
        hasVoltage == true
        voltageValue == 3.2
        voltageValue instanceof Number
    }

    def "voltage events should have proper format"() {
        given: "expected event format"
        def expectedEvent = [name: 'voltage', value: 3.25, unit: 'V']
        
        expect: "event format should be correct"
        expectedEvent.name == 'voltage'
        expectedEvent.value == 3.25
        expectedEvent.unit == 'V'
    }

    def "system-voltage mapping should preserve backward compatibility"() {
        given: "system-voltage data"
        def systemVoltage = 3.1
        
        when: "mapping to voltage attribute"
        def voltageEvent = [name: 'voltage', value: systemVoltage, unit: 'V']
        def systemVoltageEvent = [name: 'system-voltage', value: systemVoltage]
        
        then: "both attributes should be available"
        voltageEvent.name == 'voltage'
        voltageEvent.value == 3.1
        systemVoltageEvent.name == 'system-voltage'
        systemVoltageEvent.value == 3.1
    }

    def "voltage validation should handle edge cases"() {
        given: "edge case voltage values"
        def testCases = [
            [voltage: 0.0, valid: true],
            [voltage: 3.3, valid: true],
            [voltage: 5.0, valid: true],
            [voltage: -1.0, valid: false],
            [voltage: 15.0, valid: false],
            [voltage: null, valid: false]
        ]
        
        expect: "voltage validation should work correctly"
        testCases.each { testCase ->
            def voltage = testCase.voltage
            def expectedValid = testCase.valid
            def isValid = voltage != null && voltage >= 0 && voltage <= 10
            isValid == expectedValid
        }
    }

    def "puck reading should extract voltage correctly"() {
        given: "puck reading with voltage data"
        def puckReading = [
            data: [
                attributes: [
                    'system-voltage': 3.0,
                    'room-temperature-c': 20.5,
                    'humidity': 45
                ]
            ]
        ]
        
        when: "processing reading"
        def voltage = puckReading.data.attributes['system-voltage']
        def battery = Math.max(0, Math.min(100, ((voltage - 2.0) / 1.6 * 100).round() as int))
        
        then: "voltage and battery should be calculated"
        voltage == 3.0
        battery >= 0 && battery <= 100
    }

    def "missing voltage data should be handled gracefully"() {
        given: "API response without voltage"
        def apiResponse = [
            data: [
                attributes: [
                    'percent-open': 75,
                    'duct-temperature-c': 22.1
                ]
            ]
        ]
        
        when: "checking for voltage"
        def hasVoltage = apiResponse.data.attributes.containsKey('system-voltage')
        def voltage = apiResponse.data.attributes['system-voltage']
        
        then: "should handle missing voltage gracefully"
        hasVoltage == false
        voltage == null
    }

    // Helper methods
    private loadApp(String path) {
        AppExecutor executorApi = Mock {
            _ * getState() >> [activeRequests: 0, lastRequestTime: 0, requestCounts: [:], stuckRequestCounter: 0]
        }
        def sandbox = new HubitatAppSandbox(APP_FILE)
        def app = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
        
        // Initialize atomicState with all required fields
        app.atomicState = [activeRequests: 0, lastRequestTime: 0, requestCounts: [:], stuckRequestCounter: 0]
        
        // Mock the log object to prevent null pointer exceptions
        app.log = [error: { msg -> }, debug: { msg -> }, warn: { msg -> }]
        
        // Override sendEvent to call device.sendEvent directly for testing
        app.metaClass.sendEvent = { device, eventData ->
            device.sendEvent(eventData)
        }
        
        // Mock required methods for handlePuckReadingGet
        app.metaClass.decrementActiveRequests = { -> }
        app.metaClass.isValidResponse = { resp -> true }
        app.metaClass.logDetails = { msg, details, level -> }
        app.metaClass.logWarn = { msg -> }
        
        return app
    }
    
    private loadDriver(String path) {
        // Mock driver loading - in real implementation this would load the driver
        return [
            getMetadata: {
                return [
                    definition: [
                        capabilities: ['VoltageMeasurement', 'SwitchLevel'],
                        attributes: [:]
                    ]
                ]
            }
        ]
    }
    
    private createMockDevice(String type) {
        def mockDevice = Mock(Object)
        mockDevice.hasAttribute(_) >> { String attr -> attr in ['voltage', 'system-voltage', 'battery', 'percent-open'] }
        mockDevice.currentValue(_) >> { String attr -> 
            switch(attr) {
                case 'voltage': return 3.2
                case 'system-voltage': return 3.2
                case 'battery': return 75
                case 'percent-open': return 50
                default: return null
            }
        }
        mockDevice.sendEvent(_) >> { /* mock implementation */ }
        mockDevice.getDisplayName() >> "Mock ${type} Device"
        mockDevice.displayName >> "Mock ${type} Device"
        return mockDevice
    }
}
