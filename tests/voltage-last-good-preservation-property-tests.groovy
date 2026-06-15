
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Property-based spec for R7.6 vent voltage last-good preservation (task 4.1).
 *
 * Implements design.md Correctness Property 30 EXACTLY (one property per feature
 * method, tagged with the exact heading):
 *
 *   Property 30: Last-good voltage preserved (R7.37)
 *   "For any missing/non-numeric voltage reading, the emitted `voltage`/`battery`
 *    equals the last good value (never null/invalid)."
 *
 * The property drives `processVentTraits` with a GOOD numeric reading first
 * (establishing a last-good `voltage` and derived `battery`) followed by a BAD
 * reading (missing or non-numeric voltage), then asserts the device-visible
 * `voltage`/`battery` still hold the last good numeric values and were never
 * overwritten with null or a non-numeric value.
 *
 * Validates: Requirements 7.34, 7.35, 7.37.
 *
 * STRICT TDD: this spec is written to FAIL against the current code. Today
 * `processVentTraits` (a) never derives `battery` at all, so a good reading
 * leaves `battery` null (no last-good to preserve), and (b) maps
 * `system-voltage` -> `voltage` with only a `!= null` guard, so a non-numeric
 * `system-voltage` overwrites the last good `voltage` with an invalid value.
 * Task 4.2 adds the derived battery and the `state.lastGoodVoltage[ventId]`
 * guard before `sendEvent`, making it green. The class name contains "Property"
 * so `--tests '*Property*'` selects it; the `where:` block drives
 * >= PropertyGen.ITERATIONS (100) reproducible randomized readings.
 */
class VoltageLastGoodPreservationPropertySpec extends Specification {

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

  def 'Feature: hubitat-flair-vents-dab-v2, Property 30: Last-good voltage preserved'() {
    given: 'a sandboxed app whose device events persist as device state'
    def g = PropertyGen.forIteration(i)
    Map sharedState = [:]
    def script = buildScript(sharedState)
    def vent = fakeVent("vent-${i}".toString())
    script.metaClass.sendEvent = { device, Map ev ->
      device._attrs[ev.name] = ev.value
    }

    and: 'a GOOD numeric reading then a BAD (missing/non-numeric) reading'
    BigDecimal goodV = (g.nextDouble(2.0d, 3.6d) as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP)
    Map goodReading = [data: [attributes: ['system-voltage': goodV, 'percent-open': g.nextInt(0, 100)]]]
    Map badReading = [data: [attributes: badVoltageAttributes(g)]]

    when: 'the good reading is processed (establishes last-good voltage + battery)'
    script.processVentTraits(vent, goodReading)
    def vGood = vent._attrs['voltage']
    def bGood = vent._attrs['battery']

    and: 'the bad reading is processed'
    script.processVentTraits(vent, badReading)
    def vAfter = vent._attrs['voltage']
    def bAfter = vent._attrs['battery']

    then: 'the good reading populated numeric voltage and battery last-good values'
    vGood instanceof Number
    bGood instanceof Number

    and: 'the bad reading never overwrote them with null or a non-numeric value'
    vAfter instanceof Number
    bAfter instanceof Number

    and: 'the preserved values equal the last good values'
    vAfter == vGood
    bAfter == bGood

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // A bad voltage reading: either omits the voltage entirely or reports a
  // non-numeric value on whichever path (`system-voltage` or resource `voltage`).
  private static Map badVoltageAttributes(PropertyGen g) {
    int kind = g.nextInt(0, 4)
    int pct = g.nextInt(0, 100)
    switch (kind) {
      case 0: return ['percent-open': pct]                                  // missing entirely
      case 1: return ['system-voltage': null, 'percent-open': pct]          // explicit null
      case 2: return ['system-voltage': g.pick(['n/a', '', 'bad', '--']), 'percent-open': pct] // non-numeric
      case 3: return ['voltage': g.pick(['n/a', 'NaN', '']), 'percent-open': pct]               // non-numeric resource path
      default: return ['percent-open': pct]
    }
  }

  // ---- helpers -------------------------------------------------------------

  private def buildScript(Map sharedState) {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> sharedState
      _ * getAtomicState() >> [activeRequests: 50]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def s = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    s.state = sharedState
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
