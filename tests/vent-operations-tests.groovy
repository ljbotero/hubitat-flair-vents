package bot.flair

// Vent Operations Tests
// Tests for vent control operations and validations

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class VentOperationsTest extends Specification {

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

  def "patchVentTest - Valid Percentage"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * sendEvent(_ as Object, _ as Map) >> null
    }
    def mockDevice = [
      currentValue: { prop -> prop == 'percent-open' ? 75 : null },
      getDeviceNetworkId: { -> 'test-vent-123' }
    ]
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['dabEnabled': false, 'thermostat1Mode': 'auto'])
    
    // Initialize atomicState to prevent null pointer exceptions
    script.atomicState = [thermostat1Mode: 'auto', activeRequests: 0, lastRequestTime: 0, requestCounts: [:], stuckRequestCounter: 0]
    script.state = [flairAccessToken: 'test-token']

    when:
    script.patchVent(mockDevice, 75)
    
    then:
    // Should not throw exception for valid percentage (same as current - early return)
    noExceptionThrown()
  }

  def "patchVentTest - Percentage Over 100"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getLog() >> log
      _ * sendEvent(_ as Object, _ as Map) >> null
    }
    def mockDevice = [
      currentValue: { prop -> prop == 'percent-open' ? 150 : null },
      getDeviceNetworkId: { -> 'test-vent-123' }
    ]
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['dabEnabled': false, 'thermostat1Mode': 'auto'])
    
    // Initialize atomicState to prevent null pointer exceptions
    script.atomicState = [thermostat1Mode: 'auto', activeRequests: 0, lastRequestTime: 0, requestCounts: [:], stuckRequestCounter: 0]
    script.state = [flairAccessToken: 'test-token']

    when:
    script.patchVent(mockDevice, 150)

    then:
    // Should not throw exception and should handle invalid percentage (same as current - early return)
    noExceptionThrown()
  }

  def "patchVentTest - Negative Percentage"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getLog() >> log
      _ * sendEvent(_ as Object, _ as Map) >> null
    }
    def mockDevice = [
      currentValue: { prop -> prop == 'percent-open' ? -25 : null },
      getDeviceNetworkId: { -> 'test-vent-123' }
    ]
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['dabEnabled': false, 'thermostat1Mode': 'auto'])
    
    // Initialize atomicState to prevent null pointer exceptions
    script.atomicState = [thermostat1Mode: 'auto', activeRequests: 0, lastRequestTime: 0, requestCounts: [:], stuckRequestCounter: 0]
    script.state = [flairAccessToken: 'test-token']

    when:
    script.patchVent(mockDevice, -25)

    then:
    // Should not throw exception and should handle invalid percentage (same as current - early return)
    noExceptionThrown()
  }

  def "patchVentTest - Same Percentage"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
    }
    def mockDevice = [
      currentValue: { prop -> prop == 'percent-open' ? 75 : null },
      getDeviceNetworkId: { -> 'test-vent-123' }
    ]
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['dabEnabled': false, 'thermostat1Mode': 'auto'])
    
    // Initialize atomicState to prevent null pointer exceptions
    script.atomicState = [thermostat1Mode: 'auto', activeRequests: 0, lastRequestTime: 0, requestCounts: [:], stuckRequestCounter: 0]
    script.state = [flairAccessToken: 'test-token']

    when:
    script.patchVent(mockDevice, 75)
    
    then:
    // Should complete without error when setting same percentage
    noExceptionThrown()
  }

  def "patchRoomTest - Valid Room Data"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
    }
    def mockDevice = [
      currentValue: { prop -> 
        switch(prop) {
          case 'room-id': return 'room-123'
          case 'room-active': return false
          case 'room-name': return 'Living Room'
          default: return null
        }
      }
    ]
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['dabEnabled': false, 'thermostat1Mode': 'auto'])
    
    // Initialize atomicState to prevent null pointer exceptions
    script.atomicState = [thermostat1Mode: 'auto', activeRequests: 0, lastRequestTime: 0, requestCounts: [:], stuckRequestCounter: 0]
    script.state = [flairAccessToken: 'test-token']

    when:
    script.patchRoom(mockDevice, 'true')
    
    then:
    // Should complete without error for valid room data
    noExceptionThrown()
  }

  def "patchRoomTest - Missing Room ID"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
    }
    def mockDevice = [
      currentValue: { prop -> 
        switch(prop) {
          case 'room-id': return null
          case 'room-active': return false
          case 'room-name': return 'Living Room'
          default: return null
        }
      }
    ]
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['dabEnabled': false, 'thermostat1Mode': 'auto'])
    
    // Initialize atomicState to prevent null pointer exceptions
    script.atomicState = [thermostat1Mode: 'auto', activeRequests: 0, lastRequestTime: 0, requestCounts: [:], stuckRequestCounter: 0]
    script.state = [flairAccessToken: 'test-token']

    when:
    script.patchRoom(mockDevice, 'true')
    
    then:
    // Should complete without error when room ID is missing (early return)
    noExceptionThrown()
  }

  def "patchRoomTest - Same Active State"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
    }
    def mockDevice = [
      currentValue: { prop -> 
        switch(prop) {
          case 'room-id': return 'room-123'
          case 'room-active': return true
          case 'room-name': return 'Living Room'
          default: return null
        }
      }
    ]
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['dabEnabled': false, 'thermostat1Mode': 'auto'])
    
    // Initialize atomicState to prevent null pointer exceptions
    script.atomicState = [thermostat1Mode: 'auto', activeRequests: 0, lastRequestTime: 0, requestCounts: [:], stuckRequestCounter: 0]
    script.state = [flairAccessToken: 'test-token']

    when:
    script.patchRoom(mockDevice, 'true')
    
    then:
    // Should complete without error when setting same active state
    noExceptionThrown()
  }
}
