package bot.flair

// API Communication Tests
// Tests for HTTP operations, async handlers, and API interactions

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class ApiCommunicationTest extends Specification {

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

  def "isValidResponseTest - Valid Response"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    
    def mockResponse = [
      hasError: { -> false }
    ]

    expect:
    script.isValidResponse(mockResponse) == true
  }

  def "isValidResponseTest - Null Response"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['dabEnabled': false, 'thermostat1Mode': 'auto'])
    
    // Mock the log object to prevent null pointer exceptions
    script.log = [error: { msg -> }, debug: { msg -> }, warn: { msg -> }]

    expect:
    script.isValidResponse(null) == false
  }

  def "isValidResponseTest - Response with Error"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['dabEnabled': false, 'thermostat1Mode': 'auto'])
    
    // Mock the log object to prevent null pointer exceptions
    script.log = [error: { msg -> }, debug: { msg -> }, warn: { msg -> }]
    
    def mockResponse = [
      hasError: { -> true }
    ]

    expect:
    script.isValidResponse(mockResponse) == false
  }

  def "isValidResponseTest - Response with Exception"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['dabEnabled': false, 'thermostat1Mode': 'auto'])
    
    // Mock the log object to prevent null pointer exceptions
    script.log = [error: { msg -> }, debug: { msg -> }, warn: { msg -> }]
    
    def mockResponse = [
      hasError: { -> throw new RuntimeException("Test error") }
    ]

    expect:
    script.isValidResponse(mockResponse) == false
  }

  def "getStructureIdTest - With Existing Setting"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['structureId': 'test-structure-123'])

    expect:
    script.getStructureId() == 'test-structure-123'
  }

  def "getStructureIdTest - Without Existing Setting"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.getStructureId() == null
  }
}
