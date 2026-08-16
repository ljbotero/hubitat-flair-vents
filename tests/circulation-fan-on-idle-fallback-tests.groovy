/* groovylint-disable MethodName */

// R6.2 fan-on-while-idle circulation fallback — upstream wiring specs.
//
// The pure detector (`dabv2DetectCirculation`, R6.1/R6.2/R6.4) has always
// recognized `operatingState 'idle' + fanMode 'on'` as circulation, but the live
// evaluate entry points only ever read `thermostatOperatingState` and the
// circulation seam (`dabV2CirculationResult`) passes `fanMode = null` — so the
// R6.2 branch was dead code in production: forcing the fan on while idle never
// engaged circulation (surfaced by forum post #371's "force vents open while
// idle" conflict).
//
// These specs pin the repaired upstream wiring:
//
//   * `resolveThermostatEvaluateAction(operatingState, fanMode)` — translates the
//     R6.2 combination to the canonical 'fan only' action and passes every other
//     combination through unchanged. Detection is delegated to the pure library
//     so R6.1/R6.2/R6.4 semantics live in exactly one place.
//   * `thermostatEvaluateAction(thermostat)` — the device-level resolver both
//     evaluate entry points (`runDabV2BalanceEvaluate`, `evaluateDabV2ZoneById`)
//     now call; reads `thermostatOperatingState` + `thermostatFanMode`.
//   * Chain: a fake idle+fan-on thermostat resolved through
//     `thermostatEvaluateAction` and handed to `evaluateDabV2Zone` with
//     circulation ENABLED produces the circulation result (R6.2 -> R6.3), where
//     the same zone with fanMode 'auto' stays on the idle path (R6.4).
//   * `thermostat1FanModeHandler` — the new fan-mode subscription handler:
//     gated on dabEnabled + circulationEnabled, debounced through
//     `shouldApplyCirculationChange` (R6.7/R6.8), stamps the applied-change
//     timestamp only when an evaluate actually runs.
//   * Wiring pin: `initialize()` subscribes thermostat1's `thermostatFanMode`
//     to that handler (source-level pin, mirroring the invariants-guard style).
//
// Requirements: 6.1, 6.2, 6.3, 6.4, 6.7, 6.8, 6.9
// Design: §R6
//
// Run `./gradlew test --tests '*CirculationFanOnIdleFallback*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import spock.lang.Unroll

class CirculationFanOnIdleFallbackTest extends Specification {

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

  private Object buildScript(Map userSettings = [:]) {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': userSettings)
    script.atomicState = [:]
    return script
  }

  // A fake thermostat device: a Map whose currentValue closure serves the two
  // attributes the resolver reads (pattern from dabv2-adaptive-cadence-tests).
  private static Map fakeThermostat(String operatingState, String fanMode) {
    Map self = [:]
    self.opState = operatingState
    self.fanMode = fanMode
    self.currentValue = { Object... a ->
      if (a[0] == 'thermostatOperatingState') { return self.opState }
      if (a[0] == 'thermostatFanMode') { return self.fanMode }
      return null
    }
    return self
  }

  // ===========================================================================
  // resolveThermostatEvaluateAction — the R6.2 translation (pure on its inputs)
  // ===========================================================================

  @Unroll
  def 'action resolution: operatingState=#opState fanMode=#fanMode -> #expected'() {
    given:
    def script = buildScript()

    expect:
    script.resolveThermostatEvaluateAction(opState, fanMode) == expected

    where:
    opState    | fanMode     || expected
    'idle'     | 'on'        || 'fan only'  // R6.2 fan forced on while idle -> canonical fan-only action
    'idle'     | 'auto'      || 'idle'      // R6.4 idle + auto is not circulation
    'idle'     | 'circulate' || 'idle'      // 'circulate' schedules the fan; it does NOT mean the fan runs NOW
    'idle'     | null        || 'idle'      // no fan signal -> raw state passes through
    'cooling'  | 'on'        || 'cooling'   // R6.4 actively conditioning wins over the fan mode
    'heating'  | 'on'        || 'heating'   // R6.4 actively conditioning wins over the fan mode
    'fan only' | 'auto'      || 'fan only'  // R6.1 canonical state passes through unchanged
    'fan only' | null        || 'fan only'  // R6.1 regardless of fan signal
    null       | 'on'        || null        // no operating state -> nothing to translate
  }

  // ===========================================================================
  // thermostatEvaluateAction — the device-level resolver used by both evaluate
  // entry points
  // ===========================================================================

  def 'device-level resolver translates an idle+fan-on thermostat to the fan-only action (R6.2)'() {
    given:
    def script = buildScript()

    expect:
    script.thermostatEvaluateAction(fakeThermostat('idle', 'on')) == 'fan only'
  }

  def 'device-level resolver passes the raw operating state through when the fan mode is unreported'() {
    given: 'a thermostat that never reported thermostatFanMode (currentValue -> null)'
    def script = buildScript()

    expect:
    script.thermostatEvaluateAction(fakeThermostat('idle', null)) == 'idle'
    script.thermostatEvaluateAction(fakeThermostat('cooling', null)) == 'cooling'
  }

  def 'device-level resolver degrades to null for a missing thermostat'() {
    given:
    def script = buildScript()

    expect:
    script.thermostatEvaluateAction(null) == null
  }

  // ===========================================================================
  // Chain: the resolved action drives the circulation path end to end
  // (R6.2 -> R6.3), while idle+auto stays on the idle path (R6.4)
  // ===========================================================================

  def 'an idle+fan-on thermostat engages circulation through the evaluate seam (R6.2/R6.3)'() {
    given: 'circulation enabled at 60% and one rested active room'
    def script = buildScript([
        debugLevel                   : 1,
        safetyFloorPct               : 40.0d,
        circulationEnabled           : true,
        circulationOpenPct           : 60,
        thermostat1CloseInactiveRooms: true])
    List rooms = [[roomId: 'r1', tempC: 22.0d, active: true, currentOpen: 0.0d,
                   coolingRate: 0.1, heatingRate: 0.1, ventIds: ['r1#v0']]]

    when: 'the zone action is resolved from the DEVICE (idle + fan on) and evaluated'
    String action = script.thermostatEvaluateAction(fakeThermostat('idle', 'on'))
    def result = script.evaluateDabV2Zone(rooms, 22.0d, action, null, [hour: 12], null, 1_000L)

    then: 'the circulation result is produced instead of the untouched idle result'
    result.circulation == true
    (result.targets['r1'] as double) > 0.0d
  }

  def 'the same idle zone with fanMode auto stays on the idle path (R6.4)'() {
    given:
    def script = buildScript([
        debugLevel                   : 1,
        safetyFloorPct               : 40.0d,
        circulationEnabled           : true,
        circulationOpenPct           : 60,
        thermostat1CloseInactiveRooms: true])
    List rooms = [[roomId: 'r1', tempC: 22.0d, active: true, currentOpen: 0.0d,
                   coolingRate: 0.1, heatingRate: 0.1, ventIds: ['r1#v0']]]

    when:
    String action = script.thermostatEvaluateAction(fakeThermostat('idle', 'auto'))
    def result = script.evaluateDabV2Zone(rooms, 22.0d, action, null, [hour: 12], null, 1_000L)

    then: 'no circulation result: a rested idle zone is left untouched'
    result.circulation != true
  }

  // ===========================================================================
  // thermostat1FanModeHandler — gates + debounce (R6.7/R6.8/R6.9)
  // ===========================================================================

  // now() is a sandbox-provided API method, so it must be stubbed on the
  // EXECUTOR mock (metaClass cannot shadow it — same approach as
  // throttle-stuck-counter-coalesce-status-tests / cycle-finalize-race-tests).
  private Map handlerScript(Map userSettings) {
    final log = new CapturingLog()
    Map h = [evals: 0, nowMs: 1_000_000L]
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> [:]
      _ * getLog() >> log
      _ * now() >> { h.nowMs }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': userSettings)
    script.atomicState = [:]
    script.metaClass.selectAndRunDabV2Evaluate = { -> h.evals++ }
    h.script = script
    return h
  }

  def 'fan-mode events are ignored while circulation is disabled (R6.9)'() {
    given: 'DAB on but circulation left at its default (off)'
    Map h = handlerScript([dabEnabled: true])

    when:
    h.script.thermostat1FanModeHandler([value: 'on'])

    then: 'no evaluate runs and no timestamp is stamped'
    h.evals == 0
    h.script.atomicState.dabV2LastCirculationChangeMs == null
  }

  def 'fan-mode events are ignored while DAB itself is disabled'() {
    given:
    Map h = handlerScript([circulationEnabled: true])

    when:
    h.script.thermostat1FanModeHandler([value: 'on'])

    then:
    h.evals == 0
  }

  def 'the first fan-mode flip evaluates immediately and stamps the applied-change timestamp'() {
    given:
    Map h = handlerScript([dabEnabled: true, circulationEnabled: true, circulationDebounceSec: 600])

    when:
    h.script.thermostat1FanModeHandler([value: 'on'])

    then: 'no prior change on record -> nothing to debounce against (R6.7)'
    h.evals == 1
    h.script.atomicState.dabV2LastCirculationChangeMs == 1_000_000L
  }

  def 'a second flip inside the debounce window is suppressed; after the window it applies (R6.7/R6.8)'() {
    given:
    Map h = handlerScript([dabEnabled: true, circulationEnabled: true, circulationDebounceSec: 600])

    when: 'first flip applies and stamps'
    h.script.thermostat1FanModeHandler([value: 'on'])

    and: 'a burst 300s later, inside the 600s window'
    h.nowMs = 1_300_000L
    h.script.thermostat1FanModeHandler([value: 'auto'])

    then: 'the burst is suppressed and the stamp is NOT advanced'
    h.evals == 1
    h.script.atomicState.dabV2LastCirculationChangeMs == 1_000_000L

    when: 'a flip 601s after the applied change, outside the window'
    h.nowMs = 1_601_000L
    h.script.thermostat1FanModeHandler([value: 'on'])

    then: 'the change applies and the stamp advances'
    h.evals == 2
    h.script.atomicState.dabV2LastCirculationChangeMs == 1_601_000L
  }

  // ===========================================================================
  // Wiring pin — initialize() subscribes the fan mode to the handler
  // (source-level pin, mirroring cross-cutting-invariants-guard style)
  // ===========================================================================

  def 'initialize() subscribes thermostat1 fan-mode events to the fallback handler'() {
    given:
    String appSource = new File('src/hubitat-flair-vents-app.groovy').text

    expect:
    appSource.contains("subscribe(settings.thermostat1, 'thermostatFanMode', thermostat1FanModeHandler)")
  }
}
