
// DAB v2 cycle lifecycle + re-entrancy / stale-cycle guard fake-backed tests
// (Task 9.4; R10.7, R21.7).
//
// App-orchestration (non-pure) concurrency/cycle behavior exercised against
// lightweight fakes of the Hubitat surface (atomicState, a controllable clock,
// child devices). Covers the four guards the design's Concurrency/re-entrancy
// section (R21.7) and the short-cycle rule (R10.7) require:
//
//   1. Single-flight evaluate guard via atomicState (evalInFlight + cycleId):
//      an overlapping invocation while one is in flight returns immediately and
//      runs no work; a stale guard (older than the TTL) is reclaimed so the path
//      can never wedge; the guard is released after the body runs.
//   2. Stale-cycle callback dropped: a callback carrying a superseded cycle id
//      is a no-op and issues no conflicting vent commands (builds on 2.4's
//      cycleSeq identity check).
//   3. Short-cycle gap reuses the prior cycle's anchored allocation rather than
//      recomputing from scratch (R10.7).
//   4. Idempotent dispatch keyed by (cycleId, ventId, target): a repeated
//      dispatch for the same key is a no-op; a new target still dispatches; a new
//      cycle re-dispatches.
//
// Run `./gradlew test --tests '*Dabv2CycleLifecycle*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class Dabv2CycleLifecycleTest extends Specification {

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
  private Map<String, Object> devices = [:]

  private Object fakeVent(String dni, int percentOpen) {
    Map self = [:]
    self.dni = dni
    self.percentOpen = percentOpen
    self.getDeviceNetworkId = { -> self.dni }
    self.getId = { -> self.dni }
    self.hasAttribute = { Object... a -> a[0] == 'percent-open' }
    self.currentValue = { Object... a -> a[0] == 'percent-open' ? self.percentOpen : null }
    self.toString = { -> "FakeVent(${self.dni})".toString() }
    return self as ChildDeviceWrapper
  }

  private Object buildScript(Map userSettings = [:], Map state = [:], Map atomic = [:]) {
    log = new CapturingLog()
    stateMap = state
    atomicStateMap = atomic
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> atomicStateMap
      _ * getLog() >> log
      _ * getChildDevice(_) >> { String id -> devices[id] }
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
  // 1. Single-flight evaluate guard (R21.7)
  // ---------------------------------------------------------------------------

  def "acquire grants the guard once, denies an overlapping invocation, then re-grants after release"() {
    setup:
    def script = buildScript()
    Long t = 5_000_000L

    expect: 'the first acquire succeeds and records the guard in atomicState'
    script.acquireDabV2EvalGuard(t, 7L) == true
    script.atomicState.evalInFlight != null

    and: 'a re-entrant acquire while in-flight (non-stale) is denied'
    script.acquireDabV2EvalGuard(t + 1000L, 7L) == false

    and: 'after release the guard is free again'
    script.releaseDabV2EvalGuard()
    script.atomicState.evalInFlight == null
    script.acquireDabV2EvalGuard(t + 2000L, 8L) == true
  }

  def "a stale in-flight guard (older than the TTL) is reclaimed"() {
    setup:
    def script = buildScript()
    Long t = 5_000_000L
    script.acquireDabV2EvalGuard(t, 1L)

    expect: 'a much-later acquire past the staleness TTL reclaims the wedged guard'
    script.acquireDabV2EvalGuard(t + (10L * 60 * 1000), 2L) == true
  }

  def "withDabV2EvalGuard runs the body once and skips an overlapping invocation"() {
    setup:
    def script = buildScript()
    Long t = 5_000_000L
    script.metaClass.now = { -> t }
    int runs = 0
    def innerResult = null

    when: 'the body re-enters evaluate while still in flight'
    def outer = script.withDabV2EvalGuard(3L) {
      runs++
      innerResult = script.withDabV2EvalGuard(3L) { runs++; return 'inner-ran' }
      return 'outer-ran'
    }

    then: 'the outer body executed and returned its own value'
    runs == 1
    outer == 'outer-ran'

    and: 'the re-entrant invocation was skipped (never ran its body)'
    innerResult?.skipped == true

    and: 'the guard is released after the outer body'
    script.atomicState.evalInFlight == null
  }

  // ---------------------------------------------------------------------------
  // 2. Stale-cycle callback dropped (R21.7, builds on 2.4 cycleSeq)
  // ---------------------------------------------------------------------------

  def "dabV2IsCurrentCycle is true for the current/unknown cycle and false for a superseded one"() {
    setup:
    def script = buildScript([:], [:], [cycleSeq: 5L])

    expect:
    script.dabV2IsCurrentCycle(5L) == true     // current
    script.dabV2IsCurrentCycle(4L) == false    // superseded
    script.dabV2IsCurrentCycle(null) == true   // no id supplied -> not gated
  }

  def "a dispatch for a superseded cycle issues no vent commands"() {
    setup:
    devices = ['g#v0': fakeVent('g#v0', 30)]
    def script = buildScript([:], [:], [cycleSeq: 9L])
    def dispatched = []
    script.metaClass.patchVent = { dev, pct, Map extra = [:] -> dispatched << dev.getDeviceNetworkId() }
    Long now = 10_000_000L
    script.metaClass.now = { -> now }
    def rooms = [[roomId: 'room1', ventIds: ['g#v0'], currentOpen: 30.0d]]
    // zoneResult was evaluated for cycle 8, but the current cycle is already 9.
    def zoneResult = [balancing: true, cycleId: 8L, targets: ['room1': 80.0d],
                      floorRequiredRooms: (['room1'] as Set)]

    when:
    script.dispatchDabV2Targets(zoneResult, rooms)

    then: 'the stale dispatch is dropped — no conflicting command is issued'
    dispatched.isEmpty()
  }

  // ---------------------------------------------------------------------------
  // 3. Short-cycle gap reuses the prior anchored allocation (R10.7)
  // ---------------------------------------------------------------------------

  def "dabV2IsShortCycleReuse only reuses on same-mode re-activation within the gap"() {
    setup:
    def script = buildScript()
    Long idle = 1_000_000L
    Map prev = [cycleId: 4L, lastActiveMode: 'cooling', idleSinceMs: idle,
                anchorTargets: ['r1': 70.0d]]

    expect: 'same mode, gap shorter than threshold -> reuse'
    script.dabV2IsShortCycleReuse(prev, 'cooling', idle + (2L * 60 * 1000), 10) == true

    and: 'gap longer than threshold -> no reuse'
    script.dabV2IsShortCycleReuse(prev, 'cooling', idle + (20L * 60 * 1000), 10) == false

    and: 'a mode flip -> no reuse'
    script.dabV2IsShortCycleReuse(prev, 'heating', idle + (2L * 60 * 1000), 10) == false

    and: 'threshold of 0 disables reuse'
    script.dabV2IsShortCycleReuse(prev, 'cooling', idle + 1000L, 0) == false

    and: 'no prior idle bookkeeping -> no reuse'
    script.dabV2IsShortCycleReuse([cycleId: 4L, lastActiveMode: 'cooling'], 'cooling',
        idle + 1000L, 10) == false
  }

  def "a short-cycle re-activation reuses the prior cycle's anchored targets instead of recomputing"() {
    setup:
    def script = buildScript([shortCycleGapMin: 10])
    def rooms = [
      [roomId: 'a', tempC: 26.0, active: true, coolingRate: 0.2, ventIds: ['va']],
      [roomId: 'b', tempC: 24.0, active: true, coolingRate: 0.1, ventIds: ['vb']]
    ]
    Long t0 = 1_000_000L

    // First active evaluation establishes the cycle anchor + its allocation.
    def active = script.evaluateDabV2Zone(rooms, 22.0, 'cooling', null, [hour: 12], null, t0)
    assert active.balancing == true
    Map anchorTargets = active.targets

    // The zone goes idle, then re-activates 2 minutes later (a short cycle).
    Long idleAt = t0 + (1L * 60 * 1000)
    Map prevCycle = [cycleId: active.cycleId, lastActiveMode: 'cooling',
                     idleSinceMs: idleAt, anchorTargets: anchorTargets]

    when: 're-activating in the same mode within the short-cycle gap'
    def reused = script.evaluateDabV2Zone(rooms, 22.0, 'cooling', null, [hour: 12],
        prevCycle, idleAt + (2L * 60 * 1000))

    then: 'the prior anchored allocation is reused (no fresh allocation), keeping the same cycle id'
    reused.balancing == true
    reused.reusedAnchor == true
    reused.cycleId == active.cycleId
    reused.newAnchor == false
    reused.targets.keySet() == anchorTargets.keySet()

    and: 'a long idle gap instead forces a fresh anchor + recompute'
    def recomputed = script.evaluateDabV2Zone(rooms, 22.0, 'cooling', null, [hour: 12],
        prevCycle, idleAt + (30L * 60 * 1000))
    recomputed.reusedAnchor != true
  }

  // ---------------------------------------------------------------------------
  // 4. Idempotent dispatch keyed by (cycleId, ventId, target) (R21.7)
  // ---------------------------------------------------------------------------

  def "dabV2DispatchKey composes a stable key from cycle, vent, and target"() {
    setup:
    def script = buildScript()

    expect:
    script.dabV2DispatchKey(7L, 'v0', 80) == script.dabV2DispatchKey(7L, 'v0', 80)
    script.dabV2DispatchKey(7L, 'v0', 80) != script.dabV2DispatchKey(7L, 'v0', 60)
    script.dabV2DispatchKey(7L, 'v0', 80) != script.dabV2DispatchKey(8L, 'v0', 80)
  }

  def "a repeated dispatch for the same (cycleId, ventId, target) is a no-op"() {
    setup:
    devices = ['g#v0': fakeVent('g#v0', 30)]
    def script = buildScript([:], [:], [cycleSeq: 5L])
    def dispatched = []
    script.metaClass.patchVent = { dev, pct, Map extra = [:] -> dispatched << [dev.getDeviceNetworkId(), pct] }
    Long now = 10_000_000L
    script.metaClass.now = { -> now }
    def rooms = [[roomId: 'room1', ventIds: ['g#v0'], currentOpen: 30.0d]]
    def zoneResult = [balancing: true, cycleId: 5L, targets: ['room1': 80.0d],
                      floorRequiredRooms: (['room1'] as Set)]

    when: 'the same allocation is dispatched twice within the cycle'
    script.dispatchDabV2Targets(zoneResult, rooms)
    int afterFirst = dispatched.size()
    script.dispatchDabV2Targets(zoneResult, rooms)

    then: 'the first dispatch issues the command; the duplicate is a no-op'
    afterFirst == 1
    dispatched.size() == 1
  }

  def "a new target for the same vent still dispatches, and a new cycle re-dispatches"() {
    setup:
    devices = ['g#v0': fakeVent('g#v0', 30)]
    def script = buildScript([:], [:], [cycleSeq: 5L])
    def dispatched = []
    script.metaClass.patchVent = { dev, pct, Map extra = [:] -> dispatched << [dev.getDeviceNetworkId(), pct] }
    Long now = 10_000_000L
    script.metaClass.now = { -> now }
    def rooms = [[roomId: 'room1', ventIds: ['g#v0'], currentOpen: 30.0d]]

    when: 'dispatch 80%, then a genuinely different target 50% in the same cycle'
    script.dispatchDabV2Targets([balancing: true, cycleId: 5L, targets: ['room1': 80.0d],
                                 floorRequiredRooms: (['room1'] as Set)], rooms)
    script.dispatchDabV2Targets([balancing: true, cycleId: 5L, targets: ['room1': 50.0d],
                                 floorRequiredRooms: (['room1'] as Set)], rooms)
    int afterTwoTargets = dispatched.size()

    and: 'the same 80% target but under a NEW cycle id re-dispatches'
    script.atomicState.cycleSeq = 6L
    script.dispatchDabV2Targets([balancing: true, cycleId: 6L, targets: ['room1': 80.0d],
                                 floorRequiredRooms: (['room1'] as Set)], rooms)

    then: 'each distinct (cycleId,target) produced one command'
    afterTwoTargets == 2
    dispatched.size() == 3
  }
}
