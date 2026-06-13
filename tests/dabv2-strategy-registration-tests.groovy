
// DAB v2 strategy registration + selection tests (Task 10.1; R5.6, R20.3, R20.4).
//
// App-orchestration (non-pure) behavior exercised against lightweight fakes of
// the Hubitat surface (settings + state + atomicState) via the hubitat_ci
// sandbox. Covers:
//   - `balance` is a registered, selectable control strategy in the config / UI
//     strings, and is the default (R5.6 / R20.1);
//   - the legacy `dab` strategy is RETAINED and selectable as a fallback (R20.3);
//   - the periodic evaluate loop SELECTS the target-computation path from the
//     registered strategy — `balance` runs the DAB v2 pipeline, `dab` runs the
//     legacy rebalance path — and the selection is observable;
//   - the code-review fixes (Task 2) apply under BOTH strategies (R20.4):
//     overshoot-close is enforced by the legacy sizing AND by the balance
//     allocator.
//
// Run `./gradlew test --tests '*Dabv2StrategyRegistration*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class Dabv2StrategyRegistrationTest extends Specification {

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

  private Object buildScript(Map userSettings = [:], Map stateMap = [:], Map atomicStateMap = [:]) {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> atomicStateMap
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': userSettings)
    script.atomicState = atomicStateMap
    return script
  }

  // ---------------------------------------------------------------------------
  // Strategy registration + selectability (R5.6, R20.3)
  // ---------------------------------------------------------------------------

  def "balance is the default control strategy"() {
    setup:
    def script = buildScript()

    expect:
    script.getDabV2ControlStrategy() == 'balance'
  }

  def "balance is selectable"() {
    setup:
    def script = buildScript([controlStrategy: 'balance'])

    expect:
    script.getDabV2ControlStrategy() == 'balance'
    script.isDabV2BalanceStrategy() == true
  }

  def "legacy dab strategy is retained and selectable as a fallback"() {
    setup:
    def script = buildScript([controlStrategy: 'dab'])

    expect:
    script.getDabV2ControlStrategy() == 'dab'
    script.isDabV2BalanceStrategy() == false
  }

  def "an unknown configured strategy falls back to the default balance"() {
    setup:
    def script = buildScript([controlStrategy: 'bogus'])

    expect:
    script.getDabV2ControlStrategy() == 'balance'
  }

  def "both strategies are registered in the config/UI option strings"() {
    setup:
    def script = buildScript()

    when:
    Map options = script.dabV2ControlStrategyOptions()

    then:
    // `balance` (DAB v2) and the retained legacy `dab` are both selectable
    // values carrying user-facing UI strings (R5.6 / R20.3).
    options.containsKey('balance')
    options.containsKey('dab')
    options['balance']?.toString()?.toLowerCase()?.contains('balance')
    options['dab'] != null && !options['dab'].toString().isEmpty()
  }

  // ---------------------------------------------------------------------------
  // Evaluate-loop selection wiring (R5.6)
  // ---------------------------------------------------------------------------

  def "the evaluate loop selects the balance path when balance is configured"() {
    setup:
    // No topology configured -> the balance path is a safe no-op, but the
    // SELECTION must still be recorded so the wiring is observable.
    def script = buildScript([dabEnabled: true, controlStrategy: 'balance'], [:], [:])

    when:
    script.selectAndRunDabV2Evaluate()

    then:
    script.atomicState.dabV2EvaluateStrategy == 'balance'
  }

  def "the evaluate loop selects the legacy path when dab is configured"() {
    setup:
    def script = buildScript([dabEnabled: true, controlStrategy: 'dab'], [:], [:])

    when:
    script.selectAndRunDabV2Evaluate()

    then:
    script.atomicState.dabV2EvaluateStrategy == 'dab'
  }

  // ---------------------------------------------------------------------------
  // Review fixes apply under BOTH strategies (R20.4) — overshoot-close (Task 2.2)
  // ---------------------------------------------------------------------------

  def "overshoot-close holds under the legacy strategy"() {
    setup:
    def script = buildScript([controlStrategy: 'dab', thermostat1CloseInactiveRooms: true])
    // Cooling, setpoint 22 C: a satisfied (already-cool) room with a low rate
    // must close to 0 % pre-floor, not open to 100 %.
    def rateAndTempPerVentId = [
      'satisfied':    [rate: 0.0005, temp: 20.0, active: true],
      'needsCooling': [rate: 0.0005, temp: 26.0, active: true]
    ]

    when:
    def result = script.calculateOpenPercentageForAllVents(rateAndTempPerVentId, 'cooling', 22.0, 60)

    then:
    result['satisfied'] == 0.0
    result['needsCooling'] == 100.0
  }

  def "overshoot-close holds under the balance strategy"() {
    setup:
    // Two conventional vents hold the floor so a satisfied active room is driven
    // to 0 WITHOUT being reopened by the Safety_Floor (R8.7), demonstrating the
    // same overshoot-close review fix under `balance`.
    def script = buildScript([controlStrategy: 'balance', safetyFloorPct: 40,
                              thermostat1AdditionalStandardVents: 2])
    def rooms = [
      [roomId: 'satisfied',    tempC: 20.0, active: true, coolingRate: 0.1, ventIds: ['vs']],
      [roomId: 'needsCooling', tempC: 27.0, active: true, coolingRate: 0.1, ventIds: ['vn']]
    ]

    when:
    def res = script.evaluateDabV2Zone(rooms, 22.0, 'cooling', null, [hour: 12], null)

    then:
    // The satisfied room is closed; the lagging room receives air.
    res.targets['satisfied'] == 0.0d
    res.targets['needsCooling'] > 0.0d
    res.combinedOpenPct >= 40.0d - 1e-6d
  }
}
