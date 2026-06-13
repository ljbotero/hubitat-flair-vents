
// DAB v2 pre-adjust path (idle pre-positioning, bounded) fake-backed tests
// (Task 9.5; R10.6, R10.8).
//
// While the thermostat reports idle/fan, the app may pre-position vents toward
// the next cycle's predicted allocation — but ONLY within two bounds (R10.8):
//
//   1. Dwell gate: the zone has been idle for at least `preAdjustDwellMin`, so a
//      brief fan->idle->active bounce never causes a move.
//   2. Trigger gate: at least one active room's temperature is within
//      `preAdjustTriggerC` of the predicted activation trigger (the setpoint, in
//      the last active conditioning direction).
//
// The pre-adjust path REUSES the cycle's anchored allocation (it does NOT run a
// separate control law), never closes combined airflow below the Safety_Floor,
// and — when the gates are NOT met — issues no commands while idle EXCEPT a move
// strictly required to reach the floor (R10.6).
//
// Run `./gradlew test --tests '*Dabv2PreAdjust*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class Dabv2PreAdjustTest extends Specification {

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

  private static final Long MIN = 60_000L

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
      'userSettingValues': ([debugLevel: 1, dabEnabled: true, safetyFloorPct: 40,
                             preAdjustDwellMin: 5, preAdjustTriggerC: 1.0] + userSettings))
    script.state = stateMap
    script.atomicState = atomicStateMap
    return script
  }

  // A prior cooling cycle that has since gone idle: carries the last active
  // conditioning mode, the anchored per-room allocation, and the moment the zone
  // first went idle so the dwell gate can measure the idle gap.
  private Map coolingIdlePrev(Map anchorTargets, Long idleSinceMs, long cycleId = 7L) {
    return [cycleId: cycleId, lastActiveMode: 'cooling', idleSinceMs: idleSinceMs,
            anchorTargets: anchorTargets]
  }

  // ---------------------------------------------------------------------------
  // Gate helpers (R10.8)
  // ---------------------------------------------------------------------------

  def "dwell gate is met only after the configured idle dwell has elapsed"() {
    setup:
    def script = buildScript()
    Long idleAt = 1_000_000L
    Map prev = coolingIdlePrev(['a': 80.0d], idleAt)

    expect: 'idle shorter than the 5-min dwell -> not met'
    script.dabV2PreAdjustDwellMet(prev, idleAt + (1L * MIN), 5) == false

    and: 'idle at/after the dwell -> met'
    script.dabV2PreAdjustDwellMet(prev, idleAt + (5L * MIN), 5) == true
    script.dabV2PreAdjustDwellMet(prev, idleAt + (9L * MIN), 5) == true

    and: 'no idle bookkeeping -> never met'
    script.dabV2PreAdjustDwellMet([lastActiveMode: 'cooling'], idleAt + (9L * MIN), 5) == false
  }

  def "trigger gate is met only when an active room is within the threshold of the activation trigger"() {
    setup:
    def script = buildScript()

    expect: 'cooling: a room warmed back to within 1.0C below/at the setpoint is within trigger'
    script.dabV2WithinPreAdjustTrigger(
      [[roomId: 'a', tempC: 21.5, active: true]], 22.0, 'cooling', 1.0) == true

    and: 'cooling: a well-cooled room (far below setpoint) is NOT within trigger'
    script.dabV2WithinPreAdjustTrigger(
      [[roomId: 'a', tempC: 20.0, active: true]], 22.0, 'cooling', 1.0) == false

    and: 'heating: a room cooled back to within 1.0C above/at the setpoint is within trigger'
    script.dabV2WithinPreAdjustTrigger(
      [[roomId: 'a', tempC: 20.5, active: true]], 20.0, 'heating', 1.0) == true

    and: 'heating: a well-heated room (far above setpoint) is NOT within trigger'
    script.dabV2WithinPreAdjustTrigger(
      [[roomId: 'a', tempC: 22.0, active: true]], 20.0, 'heating', 1.0) == false

    and: 'inactive rooms never satisfy the trigger gate'
    script.dabV2WithinPreAdjustTrigger(
      [[roomId: 'a', tempC: 21.9, active: false]], 22.0, 'cooling', 1.0) == false
  }

  // ---------------------------------------------------------------------------
  // 1. Pre-adjust fires only after dwell AND within trigger (R10.8)
  // ---------------------------------------------------------------------------

  def "pre-adjust fires only when BOTH the dwell and trigger gates are met"() {
    setup:
    def script = buildScript()
    Long idleAt = 1_000_000L
    // A room warmed back close to the cooling setpoint (within 1.0C) so the next
    // cooling cycle is predicted soon.
    def roomsWithin = [[roomId: 'a', tempC: 21.5, active: true, coolingRate: 0.1,
                        currentOpen: 80.0, ventIds: ['va']]]
    def roomsFar = [[roomId: 'a', tempC: 20.0, active: true, coolingRate: 0.1,
                     currentOpen: 80.0, ventIds: ['va']]]
    Map prev = coolingIdlePrev(['a': 80.0d], idleAt)

    when: 'dwell met AND within trigger'
    def fired = script.evaluateDabV2Zone(roomsWithin, 22.0, 'idle', null, [hour: 12],
        prev, idleAt + (6L * MIN))

    then: 'the bounded pre-adjust pre-positions the vent'
    fired.preAdjust == true
    fired.balancing == true
    !fired.targets.isEmpty()
    fired.targets.containsKey('a')

    when: 'dwell NOT met (brief idle) but within trigger'
    def noDwell = script.evaluateDabV2Zone(roomsWithin, 22.0, 'idle', null, [hour: 12],
        prev, idleAt + (1L * MIN))

    then: 'no pre-adjust and no commands'
    noDwell.preAdjust != true
    noDwell.balancing == false
    noDwell.targets.isEmpty()

    when: 'dwell met but NOT within trigger (room far from setpoint)'
    def noTrigger = script.evaluateDabV2Zone(roomsFar, 22.0, 'idle', null, [hour: 12],
        prev, idleAt + (6L * MIN))

    then: 'no pre-adjust and no commands'
    noTrigger.preAdjust != true
    noTrigger.balancing == false
    noTrigger.targets.isEmpty()
  }

  // ---------------------------------------------------------------------------
  // 2. Pre-adjust reuses the anchored allocation and never closes below floor
  // ---------------------------------------------------------------------------

  def "pre-adjust reuses the anchored allocation (does not run a separate control law)"() {
    setup:
    def script = buildScript()
    Long idleAt = 1_000_000L
    def rooms = [
      [roomId: 'a', tempC: 21.5, active: true, coolingRate: 0.2, currentOpen: 90.0, ventIds: ['va']],
      [roomId: 'b', tempC: 21.6, active: true, coolingRate: 0.1, currentOpen: 40.0, ventIds: ['vb']]
    ]
    Map anchor = ['a': 90.0d, 'b': 40.0d]
    Map prev = coolingIdlePrev(anchor, idleAt)

    when:
    def res = script.evaluateDabV2Zone(rooms, 22.0, 'idle', null, [hour: 12],
        prev, idleAt + (6L * MIN))

    then: 'the reused anchored targets are pre-positioned (same room set as the anchor)'
    res.preAdjust == true
    res.targets.keySet() == anchor.keySet()

    and: 'the cycle identity is preserved (no fresh active cycle is started by pre-adjust)'
    res.cycleId == prev.cycleId
    res.newAnchor == false
  }

  def "pre-adjust never closes combined airflow below the Safety_Floor"() {
    setup:
    def script = buildScript()
    Long idleAt = 1_000_000L
    // A deliberately low anchor (combined 15% over two vents) would violate the
    // 40% floor; pre-adjust must raise it rather than pre-position below the floor.
    // The rooms have warmed back to/above the cooling setpoint (within trigger and
    // not-yet-satisfied) so re-activation is imminent.
    def rooms = [
      [roomId: 'a', tempC: 22.4, active: true, coolingRate: 0.2, currentOpen: 20.0, ventIds: ['va']],
      [roomId: 'b', tempC: 22.3, active: true, coolingRate: 0.1, currentOpen: 10.0, ventIds: ['vb']]
    ]
    Map anchor = ['a': 20.0d, 'b': 10.0d]
    Map prev = coolingIdlePrev(anchor, idleAt)

    when:
    def res = script.evaluateDabV2Zone(rooms, 22.0, 'idle', null, [hour: 12],
        prev, idleAt + (6L * MIN))

    then: 'combined open stays at/above the configured floor'
    res.preAdjust == true
    res.combinedOpenPct >= 40.0d
    res.floorBinding == true

    and: 'the floor-forced opens are flagged so dispatch bypasses anti-chatter (R6.2)'
    !res.floorRequiredRooms.isEmpty()
  }

  // ---------------------------------------------------------------------------
  // 3. Brief fan->idle->active bounce does not trigger a move (R10.8)
  // ---------------------------------------------------------------------------

  def "a brief fan-idle-active bounce does not trigger a pre-adjust move"() {
    setup:
    def script = buildScript()
    Long idleAt = 1_000_000L
    def rooms = [[roomId: 'a', tempC: 21.6, active: true, coolingRate: 0.1,
                  currentOpen: 80.0, ventIds: ['va']]]
    Map prev = coolingIdlePrev(['a': 80.0d], idleAt)

    when: 'the zone blips idle for well under the dwell, then re-activates'
    def blip = script.evaluateDabV2Zone(rooms, 22.0, 'idle', null, [hour: 12],
        prev, idleAt + (30L * 1000L))   // 30s idle

    then: 'no pre-adjust move is issued during the brief idle'
    blip.preAdjust != true
    blip.balancing == false
    blip.targets.isEmpty()

    when: 're-activating back into cooling immediately afterwards'
    def back = script.evaluateDabV2Zone(rooms, 22.0, 'cooling', null, [hour: 12],
        blip, idleAt + (31L * 1000L))

    then: 'normal balancing resumes (the bounce itself caused no idle move)'
    back.balancing == true
  }

  // ---------------------------------------------------------------------------
  // 4. No commands while idle otherwise, EXCEPT a move required to reach floor
  // ---------------------------------------------------------------------------

  def "when gates are not met and airflow is at rest, no commands are issued while idle"() {
    setup:
    def script = buildScript()
    Long idleAt = 1_000_000L
    // Far from trigger so pre-adjust does not fire; all vents at rest (closed).
    def rooms = [
      [roomId: 'a', tempC: 20.0, active: true, coolingRate: 0.1, currentOpen: 0.0, ventIds: ['va']],
      [roomId: 'b', tempC: 20.0, active: true, coolingRate: 0.1, currentOpen: 0.0, ventIds: ['vb']]
    ]
    Map prev = coolingIdlePrev(['a': 80.0d, 'b': 40.0d], idleAt)

    when:
    def res = script.evaluateDabV2Zone(rooms, 22.0, 'idle', null, [hour: 12],
        prev, idleAt + (6L * MIN))

    then: 'a fully-rested idle zone is left untouched'
    res.preAdjust != true
    res.balancing == false
    res.targets.isEmpty()
  }

  def "while idle, a move strictly required to reach the floor is still issued"() {
    setup:
    def script = buildScript()
    Long idleAt = 1_000_000L
    // Pre-adjust gate NOT met (brief idle, dwell not elapsed), but the current
    // apertures are partially open BELOW the floor (combined 5% over two vents) —
    // a genuine floor violation that must be corrected even while idle (R10.6).
    // The rooms are still warm (above the cooling setpoint) so the floor can
    // actually raise them.
    def rooms = [
      [roomId: 'a', tempC: 22.4, active: true, coolingRate: 0.1, currentOpen: 10.0, ventIds: ['va']],
      [roomId: 'b', tempC: 22.3, active: true, coolingRate: 0.1, currentOpen: 0.0, ventIds: ['vb']]
    ]
    Map prev = coolingIdlePrev(['a': 80.0d, 'b': 40.0d], idleAt)

    when: 'evaluated during a brief idle (dwell not yet met)'
    def res = script.evaluateDabV2Zone(rooms, 22.0, 'idle', null, [hour: 12],
        prev, idleAt + (1L * MIN))

    then: 'a floor-only correction is issued (not a balancing recompute, not a pre-adjust)'
    res.preAdjust != true
    res.idleFloorOnly == true
    res.balancing == true
    !res.targets.isEmpty()
    !res.floorRequiredRooms.isEmpty()

    and: 'ONLY floor-required rooms are commanded — no comfort/balancing targets'
    res.targets.keySet().every { res.floorRequiredRooms.contains(it) }

    and: 'the correction restores combined airflow to at/above the floor'
    res.combinedOpenPct >= 40.0d
  }

  // ---------------------------------------------------------------------------
  // 5. A fired pre-adjust actually dispatches commands to the vents
  // ---------------------------------------------------------------------------

  def "a fired pre-adjust result dispatches its targets to the vents"() {
    setup:
    devices = ['va': fakeVent('va', 80), 'vb': fakeVent('vb', 40)]
    def script = buildScript([:], [:], [cycleSeq: 7L])
    def dispatched = []
    script.metaClass.patchVent = { dev, pct, Map extra = [:] -> dispatched << [dev.getDeviceNetworkId(), pct] }
    Long now = 10_000_000L
    script.metaClass.now = { -> now }
    Long idleAt = 9_000_000L
    def rooms = [
      [roomId: 'a', tempC: 21.5, active: true, coolingRate: 0.2, currentOpen: 90.0, ventIds: ['va']],
      [roomId: 'b', tempC: 21.6, active: true, coolingRate: 0.1, currentOpen: 40.0, ventIds: ['vb']]
    ]
    Map prev = coolingIdlePrev(['a': 90.0d, 'b': 40.0d], idleAt, 7L)
    def res = script.evaluateDabV2Zone(rooms, 22.0, 'idle', null, [hour: 12],
        prev, idleAt + (6L * MIN))
    assert res.preAdjust == true

    when:
    script.dispatchDabV2Targets(res, rooms)

    then: 'the pre-positioned targets are commanded to the physical vents'
    !dispatched.isEmpty()
  }
}
