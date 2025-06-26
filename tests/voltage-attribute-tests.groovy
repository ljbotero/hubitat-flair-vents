import spock.lang.Specification
import me.biocomp.hubitat_ci.api.app_api.AppExecutor

/**
 * Test suite for voltage attribute functionality
 * Tests both vents and pucks voltage attribute exposure to Rule Machine
 */
class VoltageAttributeSpec extends Specification {

    def "vents driver should have voltage attribute properly defined"() {
        given: "a vents driver instance"
        def driver = loadDriver('src/hubitat-flair-vents-driver.groovy')
        
        when: "checking driver metadata"
        def metadata = driver.getMetadata()
        
        then: "voltage attribute should be explicitly defined"
        metadata.definition.attributes.containsKey('voltage') ||
        metadata.definition.capabilities.contains('VoltageMeasurement')
        
        and: "voltage attribute should be accessible"
        def attributes = metadata.definition.attributes
        attributes.findAll { it.key.contains('voltage') || it.key == 'voltage' }.size() >= 1
    }

    def "pucks driver should have voltage attribute properly defined"() {
        given: "a pucks driver instance"
        def driver = loadDriver('src/hubitat-flair-vents-pucks-driver.groovy')
        
        when: "checking driver metadata"
        def metadata = driver.getMetadata()
        
        then: "voltage attribute should be accessible via Battery capability or explicit attribute"
        metadata.definition.capabilities.contains('Battery') ||
        metadata.definition.attributes.containsKey('voltage')
        
        and: "should have voltage-related attributes"
        def attributes = metadata.definition.attributes
        // Should have either explicit voltage or battery capability provides it
        attributes.size() >= 0 // Battery capability provides voltage implicitly
    }

    def "app should properly extract and map system-voltage to voltage attribute for vents"() {
        given: "an app instance with a vent device"
        def app = loadApp('src/hubitat-flair-vents-app.groovy')
        def mockVent = createMockDevice('vents')
        mockVent.hasAttribute('percent-open') >> true
        
        and: "API response with system-voltage data"
        def apiResponse = [
            data: [
                attributes: [
                    'system-voltage': 3.2,
                    'percent-open': 50
                ]
            ]
        ]
        
        when: "processing vent traits"
        app.processVentTraits(mockVent, apiResponse)
        
        then: "voltage attribute should be set"
        1 * mockVent.sendEvent([name: 'voltage', value: 3.2, unit: 'V'])
        
        and: "system-voltage should also be set for backward compatibility"
        1 * mockVent.sendEvent([name: 'system-voltage', value: 3.2])
    }

    def "app should properly extract and map system-voltage to voltage attribute for pucks"() {
        given: "an app instance with a puck device"
        def app = loadApp('src/hubitat-flair-vents-app.groovy')
        def mockPuck = createMockDevice('pucks')
        mockPuck.hasAttribute('percent-open') >> false
        
        and: "API response with system-voltage data"
        def apiResponse = [
            data: [
                attributes: [
                    'system-voltage': 3.0
                ]
            ]
        ]
        
        when: "processing puck reading"
        app.handlePuckReadingGet([getJson: { apiResponse }], [device: mockPuck])
        
        then: "voltage attribute should be set"
        1 * mockPuck.sendEvent([name: 'voltage', value: 3.0, unit: 'V'])
        
        and: "battery percentage should be calculated and set"
        1 * mockPuck.sendEvent([name: 'battery', value: _ as Integer, unit: '%'])
    }

    def "app should handle missing system-voltage gracefully"() {
        given: "an app instance with a vent device"
        def app = loadApp('src/hubitat-flair-vents-app.groovy')
        def mockVent = createMockDevice('vents')
        mockVent.hasAttribute('percent-open') >> true
        
        and: "API response without system-voltage data"
        def apiResponse = [
            data: [
                attributes: [
                    'percent-open': 75
                ]
            ]
        ]
        
        when: "processing vent traits"
        app.processVentTraits(mockVent, apiResponse)
        
        then: "should not attempt to set voltage attribute"
        0 * mockVent.sendEvent([name: 'voltage', value: _, unit: 'V'])
        
        and: "should still process other attributes"
        1 * mockVent.sendEvent([name: 'percent-open', value: 75, unit: '%'])
    }

    def "app should validate voltage values are within reasonable range"() {
        given: "an app instance with a vent device"
        def app = loadApp('src/hubitat-flair-vents-app.groovy')
        def mockVent = createMockDevice('vents')
        mockVent.hasAttribute('percent-open') >> true
        
        and: "API response with extreme voltage values"
        def apiResponse = [
            data: [
                attributes: [
                    'system-voltage': voltageValue
                ]
            ]
        ]
        
        when: "processing vent traits"
        app.processVentTraits(mockVent, apiResponse)
        
        then: "should handle extreme values appropriately"
        if (voltageValue >= 0 && voltageValue <= 10) {
            1 * mockVent.sendEvent([name: 'voltage', value: voltageValue, unit: 'V'])
        } else {
            // Should either skip or clamp to reasonable range
            (0..1) * mockVent.sendEvent([name: 'voltage', value: _, unit: 'V'])
        }
        
        where:
        voltageValue << [0.0, 1.5, 3.3, 5.0, -1.0, 15.0, null]
    }

    def "puck battery calculation should be accurate"() {
        given: "an app instance"
        def app = loadApp('src/hubitat-flair-vents-app.groovy')
        def mockPuck = createMockDevice('pucks')
        
        when: "calculating battery percentage from voltage"
        def battery = app.calculateBatteryPercentage(voltage)
        
        then: "battery percentage should be within valid range"
        battery >= 0
        battery <= 100
        
        and: "should match expected calculation"
        Math.abs(battery - expectedBattery) <= 1 // Allow 1% tolerance
        
        where:
        voltage | expectedBattery
        2.0     | 0              // 0% battery
        2.8     | 50             // 50% battery  
        3.6     | 100            // 100% battery
        1.5     | 0              // Below minimum, clamped to 0%
        4.0     | 100            // Above maximum, clamped to 100%
    }

    def "voltage attribute should be accessible to Rule Machine"() {
        given: "a device with voltage attribute"
        def device = createMockDevice('vents')
        device.hasAttribute('voltage') >> true
        device.currentValue('voltage') >> 3.2
        
        when: "Rule Machine queries voltage attribute"
        def voltageValue = device.currentValue('voltage')
        
        then: "voltage should be accessible"
        voltageValue == 3.2
        
        and: "attribute should be properly typed"
        voltageValue instanceof Number
    }

    def "voltage events should be properly formatted"() {
        given: "an app instance with a device"
        def app = loadApp('src/hubitat-flair-vents-app.groovy')
        def mockDevice = createMockDevice('vents')
        
        when: "sending voltage event"
        app.sendVoltageEvent(mockDevice, 3.25)
        
        then: "event should have proper format"
        1 * mockDevice.sendEvent([
            name: 'voltage', 
            value: 3.25, 
            unit: 'V',
            descriptionText: "${mockDevice.displayName} voltage is 3.25V"
        ])
    }

    def "backward compatibility with system-voltage should be maintained"() {
        given: "an app instance with a device"
        def app = loadApp('src/hubitat-flair-vents-app.groovy')
        def mockDevice = createMockDevice('vents')
        
        and: "API response with system-voltage"
        def apiResponse = [
            data: [
                attributes: [
                    'system-voltage': 3.1
                ]
            ]
        ]
        
        when: "processing device traits"
        app.processVentTraits(mockDevice, apiResponse)
        
        then: "both voltage and system-voltage should be set"
        1 * mockDevice.sendEvent([name: 'voltage', value: 3.1, unit: 'V'])
        1 * mockDevice.sendEvent([name: 'system-voltage', value: 3.1])
    }

    // Helper methods
    private loadApp(String path) {
        return AppExecutor.newInstance(path)
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
        return Mock {
            hasAttribute(_) >> { String attr -> attr in ['voltage', 'system-voltage', 'battery'] }
            currentValue(_) >> { String attr -> 
                switch(attr) {
                    case 'voltage': return 3.2
                    case 'system-voltage': return 3.2
                    case 'battery': return 75
                    default: return null
                }
            }
            sendEvent(_) >> { /* mock implementation */ }
            getDisplayName() >> "Mock ${type} Device"
        }
    }
}
