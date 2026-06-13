
// Overshoot-close regression tests (Task 2.2)
// A room at or past the setpoint in the conditioning direction MUST be sized to
// 0 % (pre-floor) regardless of its learned change rate. The directional
// setpoint check must take precedence over the "rate too low -> open fully"
// shortcut, otherwise a satisfied room keeps receiving (maximum) air.
//
// Requirements: 2.3, 2.4, 20.4
// Defect class 1 (overshoot-close), docs/review-findings.md
//
// Run `gradle build` (or `./gradlew test --tests '*OvershootClose*'`) to test.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class OvershootCloseTest extends Specification {

  private static final String APP_FILE = Dabv2AppHarness.combinedAppText()
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice
          ]
  private static final AbstractMap USER_SETTINGS = ['debugLevel': 1, 'thermostat1CloseInactiveRooms': true]

  private Object buildScript() {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    return sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS)
  }

  def "satisfied active room with a low change rate must close (cooling)"() {
    setup:
    def script = buildScript()
    // Cooling: setpoint 22 C. The room has overshot to 20 C (already cooler than
    // setpoint) but its learned cooling rate is below MIN_TEMP_CHANGE_RATE (0.001).
    def rateAndTempPerVentId = [
      'satisfiedLowRate': [rate: 0.0005, temp: 20.0, active: true],
      'needsCooling':     [rate: 0.0005, temp: 26.0, active: true]
    ]

    when:
    def result = script.calculateOpenPercentageForAllVents(rateAndTempPerVentId, 'cooling', 22.0, 60)

    then:
    // Satisfied room must be driven to the minimum (0 %) pre-floor, NOT opened to 100 %.
    result['satisfiedLowRate'] == 0.0
    // A room that still needs conditioning but has a low rate still opens fully.
    result['needsCooling'] == 100.0
  }

  def "satisfied active room with a low change rate must close (heating)"() {
    setup:
    def script = buildScript()
    // Heating: setpoint 21 C. The room has overshot to 23 C (already warmer than
    // setpoint) but its learned heating rate is below MIN_TEMP_CHANGE_RATE.
    def rateAndTempPerVentId = [
      'satisfiedLowRate': [rate: 0.0005, temp: 23.0, active: true],
      'needsHeating':     [rate: 0.0005, temp: 18.0, active: true]
    ]

    when:
    def result = script.calculateOpenPercentageForAllVents(rateAndTempPerVentId, 'heating', 21.0, 60)

    then:
    result['satisfiedLowRate'] == 0.0
    result['needsHeating'] == 100.0
  }

  def "room exactly at setpoint with a low change rate must close"() {
    setup:
    def script = buildScript()
    def rateAndTempPerVentId = [
      'atSetpoint': [rate: 0.0005, temp: 22.0, active: true]
    ]

    when:
    def result = script.calculateOpenPercentageForAllVents(rateAndTempPerVentId, 'cooling', 22.0, 60)

    then:
    result['atSetpoint'] == 0.0
  }
}
