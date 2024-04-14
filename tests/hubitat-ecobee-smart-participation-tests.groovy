package bot.ecobee.smart

// Run `gradle build` to test
// More info @ https://github.com/biocomp/hubitat_ci/blob/master/docs/how_to_test.md

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class Test extends Specification {

  private static final File APP_FILE = new File('src/hubitat-ecobee-smart-participation.groovy')
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice
          ]
  // private static final AbstractMap USER_SETTINGS = [
  //   'debugLevel': 1,
  //   'enabled': true]

  // private static final Closure BEFORE_RUN_SCRIPT = { script ->
  //   script.getMetaClass().atomicStateUpdate = {
  //       String arg1, String arg2, AbstractMap arg3 -> '' }
  // }

  def "recalculateSensorParticipationInitTest - Single sensor"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.recalculateSensorParticipationInit([:], 10, 5, true) == [:]
    script.recalculateSensorParticipationInit(
      ['001': ['occupancy': 'occupied', 'temperature': 85.0]], 70, 80, true) == ['001':true]
    script.recalculateSensorParticipationInit(
      ['001': ['occupancy': 'occupied', 'temperature': 65.0]], 70, 80, true) == ['001':true]
    script.recalculateSensorParticipationInit(
      ['001': ['occupancy': 'occupied', 'temperature': 75.0]], 70, 80, true) == ['001':true]
  }

  def "recalculateSensorParticipationInitTest - Multiple sensors cooling"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.recalculateSensorParticipationInit([
        '001': ['occupancy': 'occupied', 'temperature': 82.0],
        '002': ['occupancy': 'occupied', 'temperature': 85.0],
        '003': ['occupancy': 'occupied', 'temperature': 87.0]
      ], 70, 80, true) == ['002':true, '003':true]

    script.recalculateSensorParticipationInit([
        '001': ['occupancy': 'occupied', 'temperature': 74.0],
        '002': ['occupancy': 'occupied', 'temperature': 75.0],
        '003': ['occupancy': 'occupied', 'temperature': 82.0]
      ], 70, 80, true) == ['003':true]

    script.recalculateSensorParticipationInit([
        '001': ['occupancy': 'occupied', 'temperature': 74.0],
        '002': ['occupancy': 'occupied', 'temperature': 79.0],
        '003': ['occupancy': 'occupied', 'temperature': 78.0],
        '004': ['occupancy': 'occupied', 'temperature': 75.0]
      ], 70, 80, true) == ['002':true, '003':true]
  }

  def "recalculateSensorParticipationInitTest - Multiple sensors heating"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.recalculateSensorParticipationInit([
        '001': ['occupancy': 'occupied', 'temperature': 62.0],
        '002': ['occupancy': 'occupied', 'temperature': 65.0],
        '003': ['occupancy': 'occupied', 'temperature': 67.0]
      ], 70, 80, true) == ['001':true]

    script.recalculateSensorParticipationInit([
        '001': ['occupancy': 'occupied', 'temperature': 74.0],
        '002': ['occupancy': 'occupied', 'temperature': 75.0],
        '003': ['occupancy': 'occupied', 'temperature': 62.0]
      ], 70, 80, true) == ['003':true]

    script.recalculateSensorParticipationInit([
        '001': ['occupancy': 'occupied', 'temperature': 64.0],
        '002': ['occupancy': 'occupied', 'temperature': 69.0],
        '003': ['occupancy': 'occupied', 'temperature': 65.0],
        '004': ['occupancy': 'occupied', 'temperature': 72.0]
      ], 70, 80, true) == ['001':true, '003':true]
  }
}
