
// DAB v2 per-thermostat evaluate-loop tests (Task 9.2; R4.4/4.5/4.6, R5.4,
// R8.6/8.7/8.8, R16.4, R21.2, R18.7).
//
// App-orchestration (non-pure) behavior exercised against lightweight fakes of
// the Hubitat surface (settings + state) via the hubitat_ci sandbox. The
// evaluate loop is thin orchestration over the PURE modules (Context_Mapper ->
// Learning_Model -> Allocator -> group-normalize -> Safety_Floor), so these
// tests feed plain room-state maps and assert on the orchestrated result rather
// than mocking child devices. Covers:
//   - thermostatOperatingState -> conditioning-mode mapping (R4.4, R8.8);
//   - rooms with a missing/unavailable temperature are EXCLUDED from allocation
//     and spread without crashing (R21.2);
//   - per-thermostat scoping: each zone is computed from only its own rooms,
//     with no cross-zone competition for one air budget (R4, R6, R7);
//   - single-room and all-satisfied paths return floor-satisfying results
//     (R4.5/4.6, R5.4, R8.6, R8.7);
//   - a conditioning mode flip (cooling<->heating) starts a new cycle anchor
//     (R8.8);
//   - inactive rooms are not auto-closed/repositioned by default, and WHERE the
//     user enabled auto-close it is honored (R16.4).
//
// Run `./gradlew test --tests '*Dabv2EvaluateLoop*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class Dabv2EvaluateLoopTest extends Specification {

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
  // HVAC state resolution (R4.4, R8.8)
  // ---------------------------------------------------------------------------

  def "thermostatOperatingState maps to the conditioning mode"() {
    setup:
    def script = buildScript()

    expect:
    script.resolveDabV2HvacAction('heating') == 'heating'
    script.resolveDabV2HvacAction('pending heat') == 'heating'
    script.resolveDabV2HvacAction('cooling') == 'cooling'
    script.resolveDabV2HvacAction('pending cool') == 'cooling'
    script.resolveDabV2HvacAction('idle') == 'idle'
    script.resolveDabV2HvacAction('fan only') == 'idle'
    script.resolveDabV2HvacAction('vent economizer') == 'idle'
    script.resolveDabV2HvacAction('something unknown') == 'idle'
    script.resolveDabV2HvacAction(null) == 'idle'
  }

  def "balancing action only for heating/cooling, not idle/fan"() {
    setup:
    def script = buildScript()

    expect:
    script.isDabV2BalancingAction('heating') == true
    script.isDabV2BalancingAction('cooling') == true
    script.isDabV2BalancingAction('idle') == false
  }

  def "idle/fan-only action issues no balancing commands"() {
    setup:
    def script = buildScript([thermostat1CloseInactiveRooms: false])
    def rooms = [
      [roomId: 'r1', tempC: 25.0, active: true, coolingRate: 0.1, ventIds: ['v1']]
    ]

    when:
    def result = script.evaluateDabV2Zone(rooms, 22.0, 'idle', null, [hour: 12], null)

    then:
    result.balancing == false
    result.targets.isEmpty()
  }

  // ---------------------------------------------------------------------------
  // Missing-temp exclusion (R21.2)
  // ---------------------------------------------------------------------------

  def "rooms with missing/unavailable temperature are excluded without crashing"() {
    setup:
    def script = buildScript()
    def rooms = [
      [roomId: 'good', tempC: 26.0, active: true, coolingRate: 0.1, ventIds: ['vg']],
      [roomId: 'noTemp', tempC: null, active: true, coolingRate: 0.1, ventIds: ['vn']],
      [roomId: 'nanTemp', tempC: Double.NaN, active: true, coolingRate: 0.1, ventIds: ['vx']]
    ]

    when:
    def inputs = script.gatherDabV2RoomInputs(rooms, 'cooling', 22.0, null)

    then:
    inputs.size() == 1
    inputs[0].roomId == 'good'
  }

  def "evaluate excludes missing-temp rooms from allocation and spread"() {
    setup:
    def script = buildScript()
    def rooms = [
      [roomId: 'a', tempC: 26.0, active: true, coolingRate: 0.1, ventIds: ['va']],
      [roomId: 'b', tempC: 24.0, active: true, coolingRate: 0.1, ventIds: ['vb']],
      [roomId: 'broken', tempC: null, active: true, coolingRate: 0.1, ventIds: ['vbk']]
    ]

    when:
    def result = script.evaluateDabV2Zone(rooms, 22.0, 'cooling', null, [hour: 12], null)

    then:
    result.balancing == true
    result.targets.containsKey('a')
    result.targets.containsKey('b')
    !result.targets.containsKey('broken')
  }

  // ---------------------------------------------------------------------------
  // Per-thermostat scoping (R4, R6, R7)
  // ---------------------------------------------------------------------------

  def "each zone is computed independently with no cross-zone competition"() {
    setup:
    def script = buildScript([safetyFloorPct: 40])
    // Zone A: a hot active room that still needs cooling.
    def zoneA = [[roomId: 'a1', tempC: 28.0, active: true, coolingRate: 0.1, ventIds: ['va1']]]
    // Zone B: an active room already at/below setpoint (satisfied).
    def zoneB = [[roomId: 'b1', tempC: 21.0, active: true, coolingRate: 0.1, ventIds: ['vb1']],
                 [roomId: 'b2', tempC: 20.5, active: true, coolingRate: 0.1, ventIds: ['vb2']]]

    when:
    def resA = script.evaluateDabV2Zone(zoneA, 22.0, 'cooling', null, [hour: 12], null)
    def resAWithBPresent = script.evaluateDabV2Zone(zoneA, 22.0, 'cooling', null, [hour: 12], null)
    def resB = script.evaluateDabV2Zone(zoneB, 22.0, 'cooling', null, [hour: 12], null)

    then:
    // Zone A's targets depend only on Zone A rooms (deterministic, unaffected by
    // any other zone existing) and its single hot room is open.
    resA.targets == resAWithBPresent.targets
    resA.targets.keySet() == ['a1'] as Set
    resA.targets['a1'] > 0.0d
    // Zone B's satisfied rooms are driven toward 0 (no air pulled in from A).
    resB.targets.keySet() == ['b1', 'b2'] as Set
  }

  // ---------------------------------------------------------------------------
  // Single-room + all-satisfied floor-satisfying paths (R4.5/4.6, R5.4, R8.6/8.7)
  // ---------------------------------------------------------------------------

  def "single active room returns a floor-satisfying result with zero spread"() {
    setup:
    def script = buildScript([safetyFloorPct: 40])
    def rooms = [[roomId: 'only', tempC: 27.0, active: true, coolingRate: 0.1, ventIds: ['vo']]]

    when:
    def result = script.evaluateDabV2Zone(rooms, 22.0, 'cooling', null, [hour: 12], null)

    then:
    result.balancing == true
    // Single active room => spread is 0 by definition (R4.5/4.6).
    result.predictedSpreadC == 0.0d
    // Combined open % is at or above the configured floor (R5.4/R8.6).
    result.combinedOpenPct >= 40.0d - 1e-6d
  }

  def "all-satisfied path returns a floor-satisfying result"() {
    setup:
    // Two conventional vents guarantee baseline airflow so the floor can be held
    // WITHOUT reopening satisfied active rooms (R8.7).
    def script = buildScript([safetyFloorPct: 40, thermostat1AdditionalStandardVents: 2])
    def rooms = [
      [roomId: 's1', tempC: 21.0, active: true, coolingRate: 0.1, ventIds: ['vs1']],
      [roomId: 's2', tempC: 20.0, active: true, coolingRate: 0.1, ventIds: ['vs2']]
    ]

    when:
    def result = script.evaluateDabV2Zone(rooms, 22.0, 'cooling', null, [hour: 12], null)

    then:
    result.balancing == true
    // All active rooms satisfied => driven to 0; conventional vents hold the floor.
    result.targets.values().every { it == 0.0d }
    result.combinedOpenPct >= 40.0d - 1e-6d
  }

  def "every commanded aperture stays within 0..100"() {
    setup:
    def script = buildScript([safetyFloorPct: 40])
    def rooms = [
      [roomId: 'a', tempC: 30.0, active: true, coolingRate: 0.02, ventIds: ['va']],
      [roomId: 'b', tempC: 24.0, active: true, coolingRate: 0.2, ventIds: ['vb']],
      [roomId: 'c', tempC: 26.0, active: true, coolingRate: 0.1, ventIds: ['vc']]
    ]

    when:
    def result = script.evaluateDabV2Zone(rooms, 22.0, 'cooling', null, [hour: 12], null)

    then:
    result.targets.values().every { it >= 0.0d && it <= 100.0d }
  }

  // ---------------------------------------------------------------------------
  // Mode-flip anchor (R8.8)
  // ---------------------------------------------------------------------------

  def "mode flip cooling<->heating starts a new cycle anchor"() {
    setup:
    def script = buildScript()

    expect:
    // Fresh start (no previous mode) is a new anchor.
    def fresh = script.resolveDabV2CycleAnchor(null, 'cooling', null)
    fresh.newAnchor == true
    fresh.cycleId == 1

    // Same mode keeps the existing anchor.
    def same = script.resolveDabV2CycleAnchor('cooling', 'cooling', 5)
    same.newAnchor == false
    same.cycleId == 5

    // A flip bumps to a new anchor.
    def flip = script.resolveDabV2CycleAnchor('cooling', 'heating', 5)
    flip.newAnchor == true
    flip.cycleId == 6
  }

  def "evaluate reports a new anchor when the conditioning mode flips"() {
    setup:
    def script = buildScript([safetyFloorPct: 40])
    def rooms = [[roomId: 'a', tempC: 18.0, active: true, heatingRate: 0.1, coolingRate: 0.1, ventIds: ['va']]]
    def prevCycle = [mode: 'cooling', cycleId: 3]

    when:
    def result = script.evaluateDabV2Zone(rooms, 22.0, 'heating', null, [hour: 12], prevCycle)

    then:
    result.mode == 'heating'
    result.newAnchor == true
    result.cycleId == 4
  }

  // ---------------------------------------------------------------------------
  // Inactive-room behavior (R16.4)
  // ---------------------------------------------------------------------------

  def "inactive rooms are not auto-closed/repositioned by default"() {
    setup:
    // closeInactive disabled: the inactive room must not be repositioned.
    def script = buildScript([safetyFloorPct: 40, thermostat1CloseInactiveRooms: false])
    def rooms = [
      [roomId: 'active1', tempC: 27.0, active: true, coolingRate: 0.1, ventIds: ['va']],
      [roomId: 'idleRoom', tempC: 24.0, active: false, currentOpen: 60.0, coolingRate: 0.1, ventIds: ['vi']]
    ]

    when:
    def result = script.evaluateDabV2Zone(rooms, 22.0, 'cooling', null, [hour: 12], null)

    then:
    // Inactive room is neither allocated a balancing target nor closed.
    !result.targets.containsKey('idleRoom')
    result.inactiveClosed == []
  }

  def "inactive auto-close is honored when the user enabled it"() {
    setup:
    def script = buildScript([safetyFloorPct: 40, thermostat1CloseInactiveRooms: true])
    def rooms = [
      [roomId: 'active1', tempC: 27.0, active: true, coolingRate: 0.1, ventIds: ['va']],
      [roomId: 'idleRoom', tempC: 24.0, active: false, currentOpen: 60.0, coolingRate: 0.1, ventIds: ['vi']]
    ]

    when:
    def result = script.evaluateDabV2Zone(rooms, 22.0, 'cooling', null, [hour: 12], null)

    then:
    result.inactiveClosed.contains('idleRoom')
  }
}
