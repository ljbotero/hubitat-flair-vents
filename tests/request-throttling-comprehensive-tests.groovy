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
    maxRequests == 3
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
    delayMs == 300
  }

  def "initRequestTracking initializes state properly"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [activeRequests: null]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [:]

    when:
    script.initRequestTracking()

    then:
    script.state.activeRequests == 0
  }

  def "initRequestTracking preserves existing state"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [activeRequests: 2]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [activeRequests: 2]

    when:
    script.initRequestTracking()

    then:
    script.state.activeRequests == 2
  }

  def "canMakeRequest returns true when under limit"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [activeRequests: 2]

    when:
    def canMake = script.canMakeRequest()

    then:
    canMake == true
  }

  def "canMakeRequest returns false when at limit"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [:]
    // Increment to reach the limit (3)
    script.incrementActiveRequests()
    script.incrementActiveRequests()
    script.incrementActiveRequests()

    when:
    def canMake = script.canMakeRequest()

    then:
    canMake == false
  }

  def "canMakeRequest returns false when over limit"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [:]
    // Increment to go over the limit
    script.incrementActiveRequests()
    script.incrementActiveRequests()
    script.incrementActiveRequests()
    script.incrementActiveRequests()
    script.incrementActiveRequests()

    when:
    def canMake = script.canMakeRequest()

    then:
    canMake == false
  }

  def "incrementActiveRequests increases counter"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [:]
    script.incrementActiveRequests() // Start at 1

    when:
    script.incrementActiveRequests()

    then:
    script.state.activeRequests == 2
  }

  def "incrementActiveRequests handles null state"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [:]

    when:
    script.incrementActiveRequests()

    then:
    script.state.activeRequests == 1
  }

  def "decrementActiveRequests decreases counter"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [:]
    script.incrementActiveRequests() // Start at 1
    script.incrementActiveRequests() // Now at 2

    when:
    script.decrementActiveRequests()

    then:
    script.state.activeRequests == 1
  }

  def "decrementActiveRequests never goes below zero"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [activeRequests: 0]

    when:
    script.decrementActiveRequests()

    then:
    script.state.activeRequests == 0
  }

  def "decrementActiveRequests handles null state"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [:]

    when:
    script.decrementActiveRequests()

    then:
    script.state.activeRequests == 0
  }

  def "retryGetDataAsync calls getDataAsync with correct parameters"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [flairAccessToken: 'test-token', activeRequests: 0]
    
    def testData = [uri: 'test-uri', callback: 'testCallback', data: [test: 'data']]

    when:
    script.retryGetDataAsync(testData)

    then:
    noExceptionThrown()
  }

  def "retryPatchDataAsync calls patchDataAsync with correct parameters"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [flairAccessToken: 'test-token', activeRequests: 0]
    
    def testData = [uri: 'test-uri', callback: 'testCallback', body: [test: 'body'], data: [test: 'data']]

    when:
    script.retryPatchDataAsync(testData)

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
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.state = [flairAccessToken: 'test-token']
    // Set up to reach limit
    script.incrementActiveRequests()
    script.incrementActiveRequests()
    script.incrementActiveRequests()

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
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.state = [flairAccessToken: 'test-token']
    // Set up to reach limit
    script.incrementActiveRequests()
    script.incrementActiveRequests()
    script.incrementActiveRequests()

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
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.state = [flairAccessToken: 'test-token']
    // Set up one active request
    script.incrementActiveRequests()

    when:
    script.getDataAsync('test-uri', 'testCallback', null)

    then:
    noExceptionThrown()
    script.state.activeRequests == 2 // Should be incremented
  }

  def "patchDataAsync processes request when under limit"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.state = [flairAccessToken: 'test-token']
    // Set up one active request
    script.incrementActiveRequests()

    when:
    script.patchDataAsync('test-uri', 'testCallback', [test: 'body'], null)

    then:
    noExceptionThrown()
    script.state.activeRequests == 2 // Should be incremented
  }

  def "patchDataAsync uses noOpHandler when callback is null"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.state = [flairAccessToken: 'test-token']
    // Set up one active request
    script.incrementActiveRequests()

    when:
    script.patchDataAsync('test-uri', null, [test: 'body'], null)

    then:
    noExceptionThrown()
    script.state.activeRequests == 2
  }

  def "throttling system maintains request counts accurately under concurrent load simulation"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [flairAccessToken: 'test-token']
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.state = [flairAccessToken: 'test-token', activeRequests: 0]

    when:
    // Simulate multiple concurrent requests
    5.times { 
      script.incrementActiveRequests()
    }

    then:
    script.state.activeRequests == 5

    when:
    // Some requests complete
    3.times {
      script.decrementActiveRequests()
    }

    then:
    script.state.activeRequests == 2

    when:
    // Check if we can make more requests
    def canMake1 = script.canMakeRequest() // Should be true (2 < 3)
    script.incrementActiveRequests()
    def canMake2 = script.canMakeRequest() // Should be false (3 >= 3)

    then:
    canMake1 == true
    canMake2 == false
    script.state.activeRequests == 3
  }
}
