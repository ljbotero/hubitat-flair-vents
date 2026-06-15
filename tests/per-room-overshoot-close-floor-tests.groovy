
// Per-room overshoot-close & floor-precedence vs RESOLVED target specs (Task 7.3;
// R3.9, R3.10, R3.11).
//
// These example / regression specs pin the REQUIRED post-Task-7.4 behavior under
// BOTH control strategies (`balance` and the legacy `dab`):
//
//   R3.9   When a room reaches/passes its OWN resolved per-room target in the
//          active conditioning direction, it is sized to 0 % (overshoot-close)
//          BEFORE the safety floor is applied — relative to that room's resolved
//          target, NOT the shared thermostat setpoint.
//   R3.10  If honoring a room's resolved per-room target would lower combined
//          airflow below the safety floor, combined airflow is held at the floor
//          (the safety floor takes precedence over per-room comfort).
//   R3.11  When per-room targets close one or more rooms, airflow is re-added
//          through the single Safety_Floor choke point (`sfApply`) so the floor
//          is satisfied WITHOUT exceeding any room's own resolved target (a room
//          that has reached its resolved target is not reopened while other
//          raisable capacity remains).
//
// ---------------------------------------------------------------------------
// STRICT TDD — RED vs GREEN guards (Task 7.3 writes FAILING specs; Task 7.4
// implements). Each feature method documents its colour:
//
//   * `dab` legs are GENUINELY RED. The legacy sizing entry point
//     `calculateOpenPercentageForAllVents(rateAndTempPerVentId, hvacMode,
//     setpoint, longestTime, closeInactive)` sizes EVERY room against the single
//     shared `setpoint`. There is no per-room-target channel today, so the specs
//     call it with a NEW 6th argument — a `perRoomTargetByVentId` map (ventId ->
//     resolved target °C). That overload does not exist yet, so the dynamic call
//     throws `groovy.lang.MissingMethodException` at runtime and the feature
//     method fails (RED). Task 7.4 adds the defaulted 6th parameter and sizes /
//     overshoot-closes each room against its resolved target, turning these GREEN
//     (the existing legacy floor adjuster already guarantees the floor, so the
//     floor-precedence leg goes green once overshoot-close is wired).
//
//   * `balance` legs are GREEN guards. Task 7.2 already threaded the optional
//     `perRoomTargetC` map into `allocAllocate` (overshoot-close uses
//     `allocRoomSetpoint(...)`), and the single `sfApply` choke point already
//     re-adds airflow by raising ONLY not-yet-satisfied active rooms (never
//     reopening a room that reached its resolved target). These legs pin that
//     wired behavior so Task 7.4 does not regress it under the `balance` path.
//
// The class name intentionally does NOT contain "Property" — these are
// example/regression specs, not seeded property tests.
//
// Run `./gradlew test --tests '*PerRoomOvershootCloseFloor*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification

class PerRoomOvershootCloseFloorSpec extends Specification {

  // Methods-only DAB v2 library, loaded as a script so its top-level methods and
  // @Field constants are callable off-device (the `balance` path).
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final String APP_FILE = Dabv2AppHarness.combinedAppText()
  private static final List VALIDATION_FLAGS = [
    Flags.DontValidateMetadata,
    Flags.DontValidatePreferences,
    Flags.DontValidateDefinition,
    Flags.DontRestrictGroovy,
    Flags.DontRequireParseMethodInDevice,
    Flags.AllowReadingNonInputSettings,
  ]
  private static final AbstractMap USER_SETTINGS = ['debugLevel': 1, 'thermostat1CloseInactiveRooms': true]

  // The legacy combined-airflow safety floor (MIN_COMBINED_VENT_FLOW).
  private static final double LEGACY_FLOOR = 30.0d
  private static final double EPS = 1e-6d

  // Build the legacy-path app sandbox fresh per feature execution (called from a
  // `given:` block — a valid Spock mock-creation region — so the Mock's
  // getLog()/getState() interaction stubs are honored). Mirrors OvershootCloseTest.
  private Object legacyApp() {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    return new HubitatAppSandbox(APP_FILE).run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS)
  }

  // ===========================================================================
  // R3.9 — overshoot-close against the RESOLVED per-room target
  // ===========================================================================

  // --- `dab` (legacy): GENUINELY RED until Task 7.4 ---------------------------
  def 'dab: a room past its resolved per-room target overshoot-closes to 0% (cooling) [RED until 7.4]'() {
    given: 'cooling at shared setpoint 22C, with one room whose resolved target is WARMER (24C)'
    def app = legacyApp()
    // WarmTarget room: temp 23.5C. Against the shared setpoint (22C) it is still
    // too warm -> would OPEN. Against its OWN resolved target (24C) it has already
    // reached comfort (23.5 <= 24) -> must overshoot-close to 0%.
    // Cold room: temp 26C, resolved target 22C -> still needs cooling -> opens.
    Map rateAndTempPerVentId = [
      'warm#v0': [rate: 0.5, temp: 23.5, active: true, name: 'WarmTarget'],
      'cold#v0': [rate: 0.5, temp: 26.0, active: true, name: 'Cold'],
    ]
    Map perRoomTargetByVentId = ['warm#v0': 24.0, 'cold#v0': 22.0]

    when: 'the legacy sizer runs WITH per-room resolved targets (NEW 6th arg; absent today -> RED)'
    Map result = app.calculateOpenPercentageForAllVents(
      rateAndTempPerVentId, 'cooling', 22.0G, 60, true, perRoomTargetByVentId)

    then: 'the room past its OWN resolved target is sized to 0% (overshoot-close vs resolved target)'
    (result['warm#v0'] as double) == 0.0d

    and: 'a room not yet at its resolved target still opens'
    (result['cold#v0'] as double) > 0.0d
  }

  def 'dab: a room past its resolved per-room target overshoot-closes to 0% (heating) [RED until 7.4]'() {
    given: 'heating at shared setpoint 21C, with one room whose resolved target is COOLER (19C)'
    def app = legacyApp()
    // CoolTarget room: temp 19.5C. Against the shared setpoint (21C) it is still
    // too cool -> would OPEN. Against its OWN resolved target (19C) it is already
    // warm enough (19.5 >= 19) -> must overshoot-close to 0%.
    Map rateAndTempPerVentId = [
      'cool#v0': [rate: 0.5, temp: 19.5, active: true, name: 'CoolTarget'],
      'warm#v0': [rate: 0.5, temp: 16.0, active: true, name: 'NeedsHeat'],
    ]
    Map perRoomTargetByVentId = ['cool#v0': 19.0, 'warm#v0': 21.0]

    when: 'the legacy sizer runs WITH per-room resolved targets (NEW 6th arg; absent today -> RED)'
    Map result = app.calculateOpenPercentageForAllVents(
      rateAndTempPerVentId, 'heating', 21.0G, 60, true, perRoomTargetByVentId)

    then: 'the room past its OWN resolved target overshoot-closes to 0%'
    (result['cool#v0'] as double) == 0.0d

    and: 'a room still below its resolved target keeps conditioning'
    (result['warm#v0'] as double) > 0.0d
  }

  // --- `balance`: GREEN guard (Task 7.2 wired allocRoomSetpoint) --------------
  def 'balance: a room past its resolved per-room target overshoot-closes to 0% [GREEN guard]'() {
    given: 'a cooling scenario at shared setpoint 22C with a per-room resolved-target map'
    Map s = lib.dabv2NewAllocSettings()
    s.hysteresisC = 0.3d
    s.granularity = 5
    s.safetyFloorPct = 40.0d
    s.conventionalVents = 0
    s.conventionalOpenPct = 100.0d
    double setpointC = 22.0d
    List rooms = [
      makeBalanceRoom('comfy', 23.5d),   // wants 24C (warmer) -> reached -> close
      makeBalanceRoom('cold', 26.0d),    // wants 22C (shared) -> still needs cooling
    ]
    // comfy resolves to a WARMER target (24C); cold resolves to the shared setpoint.
    Map perRoomTargetC = ['comfy': 24.0d, 'cold': 22.0d]

    when: 'the allocator runs at the shared setpoint (baseline) and with the resolved-target map'
    Map base = lib.allocAllocate(rooms, setpointC, 'cooling', s, null)
    Map withTargets = lib.allocAllocate(rooms, setpointC, 'cooling', s, null, perRoomTargetC)
    Map<String, Double> baseTargets = base.targets as Map<String, Double>
    Map<String, Double> resolvedTargets = withTargets.targets as Map<String, Double>

    then: 'against the shared setpoint the comfy room would still OPEN (baseline)'
    (baseTargets['comfy'] as double) > 0.0d

    and: 'against its OWN resolved target (24C) the comfy room overshoot-closes to 0%'
    (resolvedTargets['comfy'] as double) == 0.0d

    and: 'the room not yet at its resolved target still opens'
    (resolvedTargets['cold'] as double) > 0.0d
  }

  // ===========================================================================
  // R3.10 / R3.11 — safety floor takes precedence over per-room comfort
  // ===========================================================================

  // --- `dab` (legacy): GENUINELY RED until Task 7.4 --------------------------
  def 'dab: honoring per-room comfort never lowers combined airflow below the floor [RED until 7.4]'() {
    given: 'cooling where BOTH active rooms have reached their own resolved targets'
    def app = legacyApp()
    // Both rooms are at/under their OWN resolved targets, so per-room comfort
    // closes BOTH to 0% -> naive combined = 0% (well below the 30% floor). The
    // floor must then be re-satisfied (R3.10): combined held at >= the floor.
    Map rateAndTempPerVentId = [
      'a#v0': [rate: 0.5, temp: 23.5, active: true, name: 'A'],   // target 24C -> reached
      'b#v0': [rate: 0.5, temp: 25.5, active: true, name: 'B'],   // target 26C -> reached
    ]
    Map perRoomTargetByVentId = ['a#v0': 24.0, 'b#v0': 26.0]

    when: 'per-room overshoot-close runs (NEW 6th arg; absent today -> RED) then the floor is enforced'
    Map closed = app.calculateOpenPercentageForAllVents(
      rateAndTempPerVentId, 'cooling', 22.0G, 60, true, perRoomTargetByVentId)
    Map floored = app.adjustVentOpeningsToEnsureMinimumAirflowTarget(
      rateAndTempPerVentId, 'cooling', closed, 0)
    double combined = combinedOfPlan(floored, 0)

    then: 'per-room comfort closed both rooms pre-floor'
    (closed['a#v0'] as double) == 0.0d
    (closed['b#v0'] as double) == 0.0d

    and: 'the safety floor takes precedence: combined airflow is held at or above the floor (R3.10)'
    combined >= LEGACY_FLOOR - EPS
  }

  // --- `balance`: GREEN guard (single sfApply choke point) -------------------
  def 'balance: sfApply re-adds airflow to the floor without exceeding any resolved target [GREEN guard]'() {
    given: 'an overshoot-closed comfy room (reached resolved target) and a room still needing cooling'
    Map s = lib.dabv2NewAllocSettings([
      safetyFloorPct: 40.0d, conventionalVents: 0, conventionalOpenPct: 100.0d,
      inactiveCount: 0, inactiveOpenPctSum: 0.0d, granularity: 5])
    // comfy reached its resolved target (signedErrorC <= 0) and was overshoot-closed
    // to 0%; cold is still not-yet-satisfied (signedErrorC > 0).
    List rooms = [
      [roomId: 'comfy', active: true, ventIds: ['comfy#v0'], signedErrorC: -1.0d],
      [roomId: 'cold',  active: true, ventIds: ['cold#v0'],  signedErrorC:  2.0d],
    ]
    Map<String, Double> targets = ['comfy': 0.0d, 'cold': 20.0d]
    double floor = lib.sfClampSafetyFloor(s.safetyFloorPct)

    when: 'the single safety-floor choke point re-adds airflow'
    def res = lib.sfApply(targets, rooms, s)
    Map<String, Double> result = (Map<String, Double>) res[0]
    boolean floorBinding = (boolean) res[1]
    double combined = lib.sfCombinedOpenPct(
      ['comfy#v0': (result['comfy'] as double), 'cold#v0': (result['cold'] as double)], s)

    then: 'the floor was binding and combined airflow is re-added to at least the floor (R3.10/R3.11)'
    floorBinding
    combined >= floor - EPS

    and: 'the room that reached its resolved target is NOT reopened (re-add never exceeds a resolved target)'
    (result['comfy'] as double) == 0.0d

    and: 'the re-add raised the not-yet-satisfied room only'
    (result['cold'] as double) >= (targets['cold'] as double) - EPS
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  // A minimal active room map for the `balance` allocator. A satisfied room
  // (overshoot-close) returns 0% immediately, so only tempC is consulted there;
  // an unsatisfied room needs efficiency/leak/curve for sizing.
  private Map makeBalanceRoom(String roomId, double tempC) {
    return [
      roomId     : roomId,
      active      : true,
      tempC       : tempC,
      efficiency  : 0.2d,
      leak        : 0.05d,
      currentOpen : 50.0d,
      ventIds     : ["${roomId}#v0".toString()],
      signedErrorC: 0.0d,
      curve       : lib.lrnSeedLinear(0.05d),
    ]
  }

  // Legacy combined-airflow view: sum of commanded vent percentages divided by
  // the device count (smart vents + conventional vents), mirroring the app's
  // `(100 * sumPercentages) / (totalDeviceCount * 100)`.
  private static double combinedOfPlan(Map plan, int conventionalVents) {
    double sum = conventionalVents > 0 ? conventionalVents * 100.0d : 0.0d
    int count = conventionalVents > 0 ? conventionalVents : 0
    plan.each { ventId, pct -> sum += (pct ?: 0) as double; count++ }
    return count <= 0 ? 0.0d : sum / count
  }
}
