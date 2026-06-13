
// DAB v2 adaptive evaluation cadence fake-backed tests (Task 9.6; R22).
//
// The optimizer evaluates OFTEN while the HVAC runs and RARELY while idle, via
// exactly ONE self-managed evaluate job per app (design "one self-managed job").
// The job (`dabV2EvaluateTick`) runs the evaluate work and then RE-ARMS ITSELF
// through a same-named `runIn`, whose reschedule overwrites the prior pending
// job — so there is never more than one pending cadence job (R22.3). The chosen
// interval is the configured active interval (default 3 min) while ANY
// thermostat is heating/cooling, the idle interval (default 10 min) otherwise
// (R22.1/R22.2), each already clamped to scheduler/rate-limit-safe ranges by the
// 9.1 getters (R22.4). A `thermostatOperatingState` transition switches cadence
// by re-arming the single job at the new interval (R22.3).
//
// These are app-orchestration (non-pure) behaviors exercised against lightweight
// fakes of the Hubitat scheduling surface (a captured `runIn`, a thermostat fake
// with a controllable operating state, atomicState).
//
// Run `./gradlew test --tests '*Dabv2AdaptiveCadence*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class Dabv2AdaptiveCadenceTest extends Specification {

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

  private CapturingLog log
  private Map stateMap
  private Map atomicStateMap
  private List runInCalls

  // A thermostat fake whose reported operating state is controllable.
  private Object fakeThermostat(String operatingState) {
    Map self = [:]
    self.opState = operatingState
    self.currentValue = { Object... a -> a[0] == 'thermostatOperatingState' ? self.opState : null }
    self.toString = { -> "FakeThermostat(${self.opState})".toString() }
    return self
  }

  private Object buildScript(Map userSettings = [:], Map state = [:], Map atomic = [:]) {
    log = new CapturingLog()
    stateMap = state
    atomicStateMap = atomic
    runInCalls = []
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> atomicStateMap
      _ * getLog() >> log
      _ * runIn(_, _, _) >> { d, h, o -> runInCalls << [delay: d, handler: h, opts: o] }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ([debugLevel: 1, dabEnabled: true, safetyFloorPct: 40] + userSettings))
    script.state = stateMap
    script.atomicState = atomicStateMap
    return script
  }

  // ---------------------------------------------------------------------------
  // 1. Active-state detection across the configured thermostat(s) (R22.1/22.2)
  // ---------------------------------------------------------------------------

  def "dabV2AnyThermostatActive is true iff some thermostat is heating/cooling"() {
    setup:
    def script = buildScript()

    expect: 'heating/cooling (incl. pending) count as active'
    script.dabV2AnyThermostatActive(['cooling']) == true
    script.dabV2AnyThermostatActive(['heating']) == true
    script.dabV2AnyThermostatActive(['pending cool']) == true
    script.dabV2AnyThermostatActive(['pending heat']) == true

    and: 'idle / fan-only / economizer / unknown / null / empty are NOT active'
    script.dabV2AnyThermostatActive(['idle']) == false
    script.dabV2AnyThermostatActive(['fan only']) == false
    script.dabV2AnyThermostatActive(['vent economizer']) == false
    script.dabV2AnyThermostatActive([null]) == false
    script.dabV2AnyThermostatActive([]) == false
    script.dabV2AnyThermostatActive(null) == false

    and: 'ANY active thermostat makes the whole zone active'
    script.dabV2AnyThermostatActive(['idle', 'cooling']) == true
    script.dabV2AnyThermostatActive(['idle', 'fan only']) == false
  }

  // ---------------------------------------------------------------------------
  // 2. Cadence interval selection: active (3) vs idle (10), defaults (R22.1/2)
  // ---------------------------------------------------------------------------

  def "the cadence interval is the active interval while conditioning, the idle interval otherwise"() {
    setup:
    def script = buildScript()

    expect: 'defaults: 3 min active, 10 min idle'
    script.dabV2CadenceIntervalMin(true) == 3
    script.dabV2CadenceIntervalMin(false) == 10
  }

  def "the cadence interval honors configured values"() {
    setup:
    def script = buildScript([activeIntervalMin: 5, idleIntervalMin: 15])

    expect:
    script.dabV2CadenceIntervalMin(true) == 5
    script.dabV2CadenceIntervalMin(false) == 15
  }

  // ---------------------------------------------------------------------------
  // 3. Intervals clamped to scheduler/rate-limit-safe ranges (R22.4)
  // ---------------------------------------------------------------------------

  def "out-of-range configured intervals are clamped before scheduling"() {
    setup: 'active below the floor (0) and idle above the ceiling (999)'
    def script = buildScript([activeIntervalMin: 0, idleIntervalMin: 999])

    expect: 'active clamps up to the 1-min floor, idle clamps down to the 60-min ceiling'
    script.dabV2CadenceIntervalMin(true) == 1
    script.dabV2CadenceIntervalMin(false) == 60

    when: 'arming the cadence uses the CLAMPED minutes (converted to seconds)'
    script.scheduleDabV2EvaluateCadence(true)
    script.scheduleDabV2EvaluateCadence(false)

    then:
    runInCalls[0].delay == 60       // 1 min
    runInCalls[1].delay == 3600     // 60 min
  }

  // ---------------------------------------------------------------------------
  // 4. The single self-managed job: arm + re-arm overwrite the same job (R22.3)
  // ---------------------------------------------------------------------------

  def "arming the cadence schedules exactly one self-managed job that overwrites the prior one"() {
    setup:
    def script = buildScript()

    when: 'arm at the active cadence'
    script.scheduleDabV2EvaluateCadence(true)

    then: 'one runIn for the single self-managed job at the active interval (180s)'
    runInCalls.size() == 1
    runInCalls[0].handler == 'dabV2EvaluateTick'
    runInCalls[0].delay == 180
    // Same-named reschedule overwrites the prior pending job -> a single job.
    runInCalls[0].opts?.overwrite == true

    and: 'the current cadence is recorded'
    script.atomicState.dabV2Cadence?.active == true
    script.atomicState.dabV2Cadence?.intervalMin == 3
  }

  def "the self-managed tick re-arms itself and runs the evaluate work"() {
    setup:
    def script = buildScript([:], [:], [:])
    // A live thermostat that is actively cooling so the re-arm picks the active cadence.
    script.metaClass.currentThermostatOperatingStates = { -> ['cooling'] }
    int evals = 0
    // The tick's "evaluate work" is now the strategy selector (Task 10.1), which
    // routes to the balance or legacy path per the registered strategy.
    script.metaClass.selectAndRunDabV2Evaluate = { -> evals++ }

    when:
    script.dabV2EvaluateTick()

    then: 'the evaluate work ran exactly once'
    evals == 1

    and: 'the SAME job re-armed itself (self-rescheduling) at the active interval'
    runInCalls.size() == 1
    runInCalls[0].handler == 'dabV2EvaluateTick'
    runInCalls[0].delay == 180
  }

  def "the tick re-arms itself BEFORE the evaluate work, so a failure never strands the cadence"() {
    setup:
    def script = buildScript()
    script.metaClass.currentThermostatOperatingStates = { -> ['idle'] }
    script.metaClass.selectAndRunDabV2Evaluate = { -> throw new RuntimeException('boom') }

    when:
    script.dabV2EvaluateTick()

    then: 'the evaluate failure surfaces, but the single job was already re-armed first'
    thrown(RuntimeException)
    runInCalls.size() == 1
    runInCalls[0].handler == 'dabV2EvaluateTick'
    runInCalls[0].delay == 600
  }

  // ---------------------------------------------------------------------------
  // 5. Cadence switches on the thermostatOperatingState transition (R22.3)
  // ---------------------------------------------------------------------------

  def "a heating/cooling -> idle transition switches the single job from the active to the idle cadence"() {
    setup:
    def script = buildScript()
    // Keep the transition handler thin: stub the heavy collaborators so the test
    // observes only the cadence (re)scheduling.
    script.metaClass.recordStartingTemperatures = { -> }
    script.metaClass.updateDevicePollingInterval = { Object... a -> }
    script.metaClass.initializeRoomStates = { Object... a -> }
    script.metaClass.finalizeRoomStates = { Object... a -> }
    script.metaClass.unschedule = { Object... a -> }
    script.metaClass.runEvery30Minutes = { Object... a -> }
    script.metaClass.runInMillis = { Object... a -> }
    // Back the nested-map atomicState helper with the plain-map fake.
    script.metaClass.atomicStateUpdate = { String stateKey, String key, value ->
      def m = (atomicStateMap[stateKey] instanceof Map) ? atomicStateMap[stateKey] : [:]
      m[(key)] = value
      atomicStateMap[stateKey] = m
    }

    when: 'the thermostat starts cooling'
    script.thermostat1ChangeStateHandler([value: 'cooling'])

    then: 'the single job is armed at the active interval (180s)'
    def afterActive = runInCalls.findAll { it.handler == 'dabV2EvaluateTick' }
    afterActive.size() == 1
    afterActive[0].delay == 180

    when: 'the thermostat then goes idle'
    script.thermostat1ChangeStateHandler([value: 'idle'])

    then: 'the SAME single job is re-armed at the idle interval (600s)'
    def all = runInCalls.findAll { it.handler == 'dabV2EvaluateTick' }
    all.size() == 2
    all[1].delay == 600
  }
}
