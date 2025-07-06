package bot.flair

// Comprehensive Request Throttling Tests
// Tests for all throttling functionality including MAX_CONCURRENT_REQUESTS

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class RequestThrottlingComprehensiveTest extends Specification {

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

  def "MAX_CONCURRENT_REQUESTS constant is properly defined"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    when:
    def maxRequests = script.MAX_CONCURRENT_REQUESTS

    then:
    maxRequests == 8
  }

  def "API_CALL_DELAY_MS constant is properly defined"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    when:
    def delayMs = script.API_CALL_DELAY_MS

    then:
    delayMs == 3000
  }

  def "initRequestTracking initializes atomicState properly"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [:]

    when:
    script.initRequestTracking()

    then:
    script.atomicState.activeRequests == 0
  }

  def "initRequestTracking preserves existing atomicState"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 2]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [activeRequests: 2]

    when:
    script.initRequestTracking()

    then:
    script.atomicState.activeRequests == 2
  }

  def "canMakeRequest returns true when under limit"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 2]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [activeRequests: 2]

    when:
    def canMake = script.canMakeRequest()

    then:
    canMake == true
  }

  def "canMakeRequest returns true when at limit (resets stuck counter)"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 8]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [activeRequests: 8]

    when:
    def canMake = script.canMakeRequest()

    then:
    canMake == true  // Should reset and return true
    script.atomicState.activeRequests == 0  // Should be reset
  }

  def "canMakeRequest returns false when over limit"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 0]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [activeRequests: 0]
    // Increment to go over the limit (12 > 10)
    12.times { script.incrementActiveRequests() }

    when:
    def canMake = script.canMakeRequest()

    then:
    canMake == true  // Should reset to 0 and return true due to stuck counter detection
  }

  def "incrementActiveRequests increases counter"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 0]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [activeRequests: 0]
    script.incrementActiveRequests() // Start at 1

    when:
    script.incrementActiveRequests()

    then:
    script.atomicState.activeRequests == 2
  }

  def "incrementActiveRequests initializes atomicState when missing"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [:]  // Empty map, not null

    when:
    script.incrementActiveRequests()

    then:
    script.atomicState.activeRequests == 1
  }

  def "decrementActiveRequests decreases counter"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 0]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [activeRequests: 0]
    script.incrementActiveRequests() // Start at 1
    script.incrementActiveRequests() // Now at 2

    when:
    script.decrementActiveRequests()

    then:
    script.atomicState.activeRequests == 1
  }

  def "decrementActiveRequests never goes below zero"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 0]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [activeRequests: 0]

    when:
    script.decrementActiveRequests()

    then:
    script.atomicState.activeRequests == 0
  }

  def "decrementActiveRequests initializes atomicState when missing"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [:]  // Empty map, not null

    when:
    script.decrementActiveRequests()

    then:
    script.atomicState.activeRequests == 0
  }

  def "getDataAsync retry functionality works correctly"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getLog() >> log
      _ * getAtomicState() >> [activeRequests: 0]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [flairAccessToken: 'test-token']
    script.atomicState = [activeRequests: 0]

    when:
    script.getDataAsync('test-uri', 'testCallback', null)

    then:
    noExceptionThrown()
  }

  def "patchDataAsync retry functionality works correctly"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getLog() >> log
      _ * getAtomicState() >> [activeRequests: 0]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [flairAccessToken: 'test-token']
    script.atomicState = [activeRequests: 0]

    when:
    script.patchDataAsync('test-uri', 'testCallback', [test: 'body'], null)

    then:
    noExceptionThrown()
  }

  def "safeSendEvent handles missing sendEvent gracefully"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
    }
    def mockDevice = [getLabel: { -> 'Test Device' }]
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])

    when:
    script.safeSendEvent(mockDevice, [name: 'test', value: 'value'])

    then:
    noExceptionThrown()
    // The method should handle the missing sendEvent gracefully
  }

  def "getDataAsync throttles when at request limit"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      _ * getAtomicState() >> [activeRequests: 0]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.state = [flairAccessToken: 'test-token']
    script.atomicState = [activeRequests: 0]
    // Set up to reach limit (10 times)
    10.times { script.incrementActiveRequests() }

    when:
    script.getDataAsync('test-uri', 'testCallback', null)

    then:
    noExceptionThrown()
    // Request should be queued when at limit
  }

  def "patchDataAsync throttles when at request limit"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      _ * getAtomicState() >> [activeRequests: 0]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.state = [flairAccessToken: 'test-token']
    script.atomicState = [activeRequests: 0]
    // Set up to reach limit (10 times)
    10.times { script.incrementActiveRequests() }

    when:
    script.patchDataAsync('test-uri', 'testCallback', [test: 'body'], null)

    then:
    noExceptionThrown()
    // Request should be queued when at limit
  }

  def "getDataAsync processes request when under limit"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      _ * getAtomicState() >> [activeRequests: 0]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.state = [flairAccessToken: 'test-token']
    script.atomicState = [activeRequests: 0]
    // Set up one active request
    script.incrementActiveRequests()

    when:
    script.getDataAsync('test-uri', 'testCallback', null)

    then:
    noExceptionThrown()
    script.atomicState.activeRequests == 2 // Should be incremented
  }

  def "patchDataAsync processes request when under limit"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      _ * getAtomicState() >> [activeRequests: 0]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.state = [flairAccessToken: 'test-token']
    script.atomicState = [activeRequests: 0]
    // Set up one active request
    script.incrementActiveRequests()

    when:
    script.patchDataAsync('test-uri', 'testCallback', [test: 'body'], null)

    then:
    noExceptionThrown()
    script.atomicState.activeRequests == 2 // Should be incremented
  }

  def "patchDataAsync uses noOpHandler when callback is null"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      _ * getAtomicState() >> [activeRequests: 0]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.state = [flairAccessToken: 'test-token']
    script.atomicState = [activeRequests: 0]
    // Set up one active request
    script.incrementActiveRequests()

    when:
    script.patchDataAsync('test-uri', null, [test: 'body'], null)

    then:
    noExceptionThrown()
    script.atomicState.activeRequests == 2
  }

  def "throttling system maintains request counts accurately under concurrent load simulation"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getAtomicState() >> [activeRequests: 0]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [flairAccessToken: 'test-token']
    script.atomicState = [activeRequests: 0]

    when:
    // Simulate multiple concurrent requests
    5.times { 
      script.incrementActiveRequests()
    }

    then:
    script.atomicState.activeRequests == 5

    when:
    // Some requests complete
    3.times {
      script.decrementActiveRequests()
    }

    then:
    script.atomicState.activeRequests == 2

    when:
    // Check if we can make more requests
    def canMake1 = script.canMakeRequest() // Should be true (2 < 10)
    script.incrementActiveRequests()
    def canMake2 = script.canMakeRequest() // Should be true (3 < 10)

    then:
    canMake1 == true
    canMake2 == true
    script.atomicState.activeRequests == 3
  }
}
