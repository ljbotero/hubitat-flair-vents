
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Example-based specs for R7.6 vent voltage population from both paths (task 4.1).
 * These pin the wiring that task 4.2 implements:
 *
 *   - a vent reading that carries battery voltage as `voltage` on the vent
 *     resource (and NOT `system-voltage`) populates the canonical `voltage`
 *     attribute (R7.34: "from whichever the reading path provides ... SHALL NOT
 *     assume only system-voltage");
 *   - a vent reading that carries `system-voltage` (the
 *     `vent-sensor-readings` sub-resource path) populates the canonical
 *     `voltage` attribute (R7.34);
 *   - a `system-voltage` reading derives the `battery` percentage via the
 *     existing `(v-2.0)/1.6*100` map (R7.35).
 *
 * Validates: Requirements 7.34, 7.35.
 *
 * STRICT TDD: these FAIL against the current code. `processVentTraits` today
 * only maps `system-voltage` -> `voltage` (it ignores a resource-level
 * `voltage`) and never derives `battery`. Task 4.2 makes them green.
 */
class VoltageVentPathsSpec extends Specification {

  private static final String APP_FILE = Dabv2AppHarness.combinedAppText()
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice,
            Flags.AllowWritingToSettings,
            Flags.AllowReadingNonInputSettings
          ]

  CapturingLog log
  def script
  def vent

  def setup() {
    log = new CapturingLog()
    script = buildScript()
    vent = fakeVent('vent-1')
    script.metaClass.sendEvent = { device, Map ev ->
      device._attrs[ev.name] = ev.value
    }
  }

  def 'a resource-level voltage (no system-voltage) populates the voltage attribute'() {
    given: 'a vent reading exposing battery voltage as the resource `voltage` field only'
    Map reading = [data: [attributes: ['voltage': 3.1, 'percent-open': 40]]]

    when: 'the vent-trait handler processes it'
    script.processVentTraits(vent, reading)

    then: 'the canonical voltage attribute is populated from the resource path (R7.34)'
    vent._attrs['voltage'] == 3.1
  }

  def 'a system-voltage reading populates the voltage attribute'() {
    given: 'a vent reading exposing battery voltage as `system-voltage`'
    Map reading = [data: [attributes: ['system-voltage': 3.2, 'percent-open': 55]]]

    when: 'the vent-trait handler processes it'
    script.processVentTraits(vent, reading)

    then: 'the canonical voltage attribute is populated from the system-voltage path (R7.34)'
    vent._attrs['voltage'] == 3.2
  }

  def 'a system-voltage reading derives the battery percentage'() {
    given: 'a vent reading with a full-charge system-voltage'
    Map reading = [data: [attributes: ['system-voltage': 3.6, 'percent-open': 10]]]

    when: 'the vent-trait handler processes it'
    script.processVentTraits(vent, reading)

    then: 'battery is derived via the existing (v-2.0)/1.6*100 map and clamped 0..100 (R7.35)'
    vent._attrs['battery'] != null
    (vent._attrs['battery'] as int) == 100
  }

  // ---- helpers -------------------------------------------------------------

  private def buildScript() {
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 50]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def s = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    s.atomicState = [activeRequests: 50]
    return s
  }

  private static Object fakeVent(String id) {
    Map attrs = [:]
    return [
      getId             : { -> id },
      getDeviceNetworkId: { -> id },
      getLabel          : { -> "Vent ${id}".toString() },
      currentValue      : { String a -> attrs[a] },
      _attrs            : attrs,
    ]
  }
}
