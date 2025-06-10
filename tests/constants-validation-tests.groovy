package bot.flair

// Constants and Validation Tests
// Tests for application constants and input validation

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class ConstantsValidationTest extends Specification {

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

  def "Constants Test - BASE_URL"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.BASE_URL == 'https://api.flair.co'
  }

  def "Constants Test - Temperature Limits"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.MAX_TEMP_CHANGE_RATE_C == 1.5
    script.MIN_TEMP_CHANGE_RATE_C == 0.001
    script.SETPOINT_OFFSET_C == 0.7
  }

  def "Constants Test - Percentage Limits"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.MIN_PERCENTAGE_OPEN == 0.0
    script.MAX_PERCENTAGE_OPEN == 100.0
    script.MIN_COMBINED_VENT_FLOW_PERCENTAGE == 30.0
  }

  def "Constants Test - Time Limits"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.MAX_MINUTES_TO_SETPOINT == 60
    script.MIN_MINUTES_TO_SETPOINT == 1
    script.HTTP_TIMEOUT_SECS == 5
  }

  def "Constants Test - Algorithm Constants"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.BASE_CONST == 0.0991
    script.EXP_CONST == 2.3
    script.MAX_ITERATIONS == 500
  }

  def "Constants Test - HVAC Mode Strings"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.COOLING == 'cooling'
    script.HEATING == 'heating'
    script.PENDING_COOL == 'pending cool'
    script.PENDING_HEAT == 'pending heat'
  }

  def "Constants Test - Operational Limits"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.MAX_NUMBER_OF_STANDARD_VENTS == 15
    script.MILLIS_DELAY_TEMP_READINGS == 30000
    script.INREMENT_PERCENTAGE_WHEN_REACHING_VENT_FLOW_TAGET == 1.5
  }

  def "Validation Test - Vent Pre-adjustment Threshold"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.VENT_PRE_ADJUSTMENT_THRESHOLD_C == 0.2
  }

  def "Validation Test - Content Type"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.CONTENT_TYPE == 'application/json'
  }
}
