
// R7.4 configurable minimum vent opening — property + example specs (Task 17.1;
// R7.22–R7.28).
//
// Implements Correctness Property 29 from design.md (§R7.4, "Correctness
// Properties") EXACTLY — one property per feature method, tagged with the exact
// property heading:
//
//   Property 29: Min-opening precedence never violates the floor  (R7.27)
//     For any global/per-vent minimum, the ordering
//       safety floor > inactive-room close > minimum opening
//     holds; minimum opening never lowers airflow below the floor.
//     Validates: Requirements 7.26, 7.27, 7.28.
//
// ---------------------------------------------------------------------------
// STRICT TDD (Task 17.1 writes FAILING specs; Task 17.2 implements):
//
//   * The property + helper-level example legs are GENUINELY RED: the pure
//     minimum-opening helper `dabv2ApplyMinOpening(targets, rooms, globalMinPct,
//     perVentMin, closeInactive)` does NOT exist yet, so the dynamic call throws
//     `groovy.lang.MissingMethodException` and the feature method fails (RED).
//     Task 17.2 adds the pure helper (raise each ACTIVE room-group to at least
//     its resolved minimum, leaving inactive-closed groups untouched) — turning
//     these GREEN.
//
//   * The end-to-end wiring example is GENUINELY RED: `evaluateDabV2Zone` does
//     not yet apply a configured minimum opening before the final `sfApply`, so
//     a satisfied (overshoot-closed) active room is commanded 0 % today. Task
//     17.2 wires the helper between `dabV2GroupNormalize` and `sfApply`, raising
//     the satisfied room to the configured minimum (subject to the floor) —
//     turning this GREEN.
//
// The class name contains "Property" so `--tests '*Property*'` selects it; the
// property `where:` block drives PropertyGen.ITERATIONS reproducible scenarios.
//
// Run `./gradlew test --tests '*MinOpeningPrecedence*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification

class MinOpeningPrecedencePropertySpec extends Specification {

  // Methods-only DAB v2 library, loaded as a script so its top-level pure methods
  // and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final String APP_FILE = Dabv2AppHarness.combinedAppText()
  private static final List VALIDATION_FLAGS = [
    Flags.DontValidateMetadata,
    Flags.DontValidatePreferences,
    Flags.DontValidateDefinition,
    Flags.DontRestrictGroovy,
    Flags.DontRequireParseMethodInDevice,
    Flags.AllowWritingToSettings,
    Flags.AllowReadingNonInputSettings,
  ]
  private static final double EPS = 1e-6d

  private Object buildScript(Map userSettings) {
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

  // Resolved effective minimum for a room-group: MAX over its vents of the
  // per-vent override (when configured) else the global minimum, clamped 0–100.
  private static double effMinFor(Map room, double globalMin, Map perVentMin) {
    double g = Math.max(0.0d, Math.min(100.0d, globalMin))
    List vents = (room?.ventIds) as List
    if (vents == null || vents.isEmpty()) { return g }
    double eff = 0.0d
    vents.each { vidObj ->
      String vid = vidObj == null ? null : String.valueOf(vidObj)
      double m = g
      if (vid != null && perVentMin != null && perVentMin[vid] != null) {
        m = Math.max(0.0d, Math.min(100.0d, (perVentMin[vid] as double)))
      }
      if (m > eff) { eff = m }
    }
    return eff
  }

  // ===========================================================================
  // Property 29 — precedence floor > inactive close > minimum opening
  // ===========================================================================
  def 'Feature: hubitat-flair-vents-dab-v2, Property 29: min-opening precedence never violates the floor — for any global/per-vent minimum the ordering safety floor > inactive-room close > minimum opening holds and minimum opening never lowers airflow below the floor'() {
    given: 'a randomized active/inactive room mix, raw per-room targets, a global+per-vent minimum, and a floor'
    def gen = PropertyGen.forIteration(i)

    double globalMin = (double) gen.pick([0.0d, 10.0d, 25.0d, 40.0d, 60.0d, 100.0d, 150.0d, -20.0d])
    double floor = (double) gen.pick([20.0d, 35.0d, 40.0d, 55.0d, 70.0d, 90.0d])
    boolean closeInactive = gen.nextBoolean()

    int nActive = gen.nextInt(1, 4)
    int nInactive = gen.nextInt(0, 3)

    List rooms = []
    Map targets = [:]
    Map perVentMin = [:]
    (0..<nActive).each { int k ->
      String roomId = "a${k}".toString()
      int nv = gen.nextInt(1, 2)
      List vents = (0..<nv).collect { "${roomId}#v${it}".toString() }
      // Every active room is not-yet-satisfied (signedErrorC > 0) so sfApply can
      // always raise it toward the floor if needed.
      rooms << [roomId: roomId, active: true, ventIds: vents,
                signedErrorC: gen.nextDouble(0.5d, 3.0d)]
      targets[roomId] = gen.nextDouble(0.0d, 100.0d)
      // Sprinkle per-vent overrides on some vents.
      vents.each { String vid ->
        if (gen.nextBoolean()) { perVentMin[vid] = gen.nextDouble(0.0d, 100.0d) }
      }
    }
    (0..<nInactive).each { int k ->
      String roomId = "i${k}".toString()
      rooms << [roomId: roomId, active: false, ventIds: ["${roomId}#v0".toString()],
                signedErrorC: 0.0d]
      // Inactive rooms are closed to 0 when closeInactive (the inactive-room close).
      targets[roomId] = 0.0d
    }

    Map s = lib.dabv2NewAllocSettings([
      safetyFloorPct: floor, conventionalVents: 0, conventionalOpenPct: 100.0d,
      inactiveCount: 0, inactiveOpenPctSum: 0.0d, granularity: 5])
    double clampedFloor = lib.sfClampSafetyFloor(floor)
    Map roomById = lib.sfIndexRooms(rooms)

    when: 'minimum opening is applied BEFORE the final safety-floor choke point'
    Map afterMin = lib.dabv2ApplyMinOpening(targets, rooms, globalMin, perVentMin, closeInactive)
    def res = lib.sfApply(afterMin, rooms, s)
    Map result = (Map) res[0]
    double combined = lib.sfCombinedForRooms(result, roomById, s)

    then: 'minimum opening raised every ACTIVE room-group to at least its resolved minimum (R7.26)'
    rooms.findAll { it.active }.every { Map r ->
      double eff = effMinFor(r, globalMin, perVentMin)
      (afterMin[r.roomId] as double) >= eff - EPS
    }

    and: 'minimum opening did NOT override the inactive-room close (R7.27/R7.28)'
    !closeInactive || rooms.findAll { !it.active }.every { Map r ->
      (afterMin[r.roomId] as double) == (targets[r.roomId] as double)
    }

    and: 'the final floor still wins: combined airflow is at or above the floor (minimum never lowers airflow below it)'
    combined >= clampedFloor - EPS

    and: 'the floor only ever raises — every commanded position is >= its post-minimum value'
    result.every { rid, pct -> (pct as double) >= (afterMin[rid] as double) - EPS }

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ===========================================================================
  // Examples — clamp 0–100, per-vent override, inactive-close precedence
  // ===========================================================================

  def 'global minVentOpenGlobalPct is clamped to 0–100 and applied to all active zone vents (R7.24/R7.25)'() {
    given: 'two active rooms with allocator targets below the (over-range) global minimum'
    List rooms = [
      [roomId: 'a', active: true, ventIds: ['a#v0'], signedErrorC: 1.0d],
      [roomId: 'b', active: true, ventIds: ['b#v0'], signedErrorC: 1.0d],
    ]
    Map targets = ['a': 0.0d, 'b': 10.0d]

    expect: 'a global of 150 clamps to 100 — every active room-group is raised to 100'
    lib.dabv2ApplyMinOpening(targets, rooms, 150.0d, [:], true)['a'] as double == 100.0d
    lib.dabv2ApplyMinOpening(targets, rooms, 150.0d, [:], true)['b'] as double == 100.0d

    and: 'a negative global clamps to 0 — nothing is raised'
    lib.dabv2ApplyMinOpening(targets, rooms, -20.0d, [:], true)['a'] as double == 0.0d
    lib.dabv2ApplyMinOpening(targets, rooms, -20.0d, [:], true)['b'] as double == 10.0d

    and: 'an in-range global of 30 raises only the rooms below it'
    lib.dabv2ApplyMinOpening(targets, rooms, 30.0d, [:], true)['a'] as double == 30.0d
    lib.dabv2ApplyMinOpening(targets, rooms, 30.0d, [:], true)['b'] as double == 30.0d
  }

  def 'a per-vent override minVentOpen[ventId] wins over the global minimum (R7.23)'() {
    given: 'one room with a per-vent override higher than the global minimum'
    List rooms = [
      [roomId: 'a', active: true, ventIds: ['a#v0'], signedErrorC: 1.0d],
      [roomId: 'b', active: true, ventIds: ['b#v0'], signedErrorC: 1.0d],
    ]
    Map targets = ['a': 0.0d, 'b': 0.0d]
    Map perVentMin = ['a#v0': 55.0d]

    when: 'minimum opening is applied with a global of 20'
    Map out = lib.dabv2ApplyMinOpening(targets, rooms, 20.0d, perVentMin, true)

    then: 'the overridden vent room is raised to its per-vent minimum (55)'
    (out['a'] as double) == 55.0d

    and: 'a vent without an override uses the global minimum (20)'
    (out['b'] as double) == 20.0d
  }

  def 'minimum opening never overrides the inactive-room close (R7.27/R7.28)'() {
    given: 'an inactive room closed to 0 alongside an active room, with a high global minimum'
    List rooms = [
      [roomId: 'act', active: true, ventIds: ['act#v0'], signedErrorC: 1.0d],
      [roomId: 'off', active: false, ventIds: ['off#v0'], signedErrorC: 0.0d],
    ]
    Map targets = ['act': 0.0d, 'off': 0.0d]

    when: 'minimum opening is applied with close-inactive enabled and a global of 40'
    Map out = lib.dabv2ApplyMinOpening(targets, rooms, 40.0d, [:], true)

    then: 'the active room is raised to the minimum'
    (out['act'] as double) == 40.0d

    and: 'the inactive (closed) room is left at 0 — minimum does not override the inactive-room close'
    (out['off'] as double) == 0.0d

    when: 'close-inactive is OFF, the inactive room is no longer being closed and the minimum applies'
    Map out2 = lib.dabv2ApplyMinOpening(targets, rooms, 40.0d, [:], false)

    then: 'with no inactive-room close in effect the minimum opening applies to that room too'
    (out2['off'] as double) == 40.0d
  }

  // ===========================================================================
  // End-to-end wiring — evaluateDabV2Zone applies the configured minimum
  // opening BEFORE the final sfApply (RED until 17.2)
  // ===========================================================================
  def 'evaluateDabV2Zone raises a satisfied active room to the configured minimum opening before the floor [RED until 17.2]'() {
    given: 'a cooling zone with a satisfied (overshoot-closed) room, a hot room, and a configured global minimum'
    def script = buildScript([
      debugLevel                   : 1,
      safetyFloorPct               : 20.0,
      thermostat1CloseInactiveRooms: true,
    ])
    // 'cool' sits at/under the setpoint while cooling -> overshoot-closes to 0%.
    // 'hot' is well above the setpoint -> opens. A global minimum of 35 must
    // raise the satisfied room to 35 (subject to the floor) once wired.
    List rooms = [
      [roomId: 'cool', tempC: 21.6d, active: true, currentOpen: 0.0d,
       coolingRate: 0.2, heatingRate: 0.2, ventIds: ['cool#v0']],
      [roomId: 'hot', tempC: 28.0d, active: true, currentOpen: 50.0d,
       coolingRate: 0.2, heatingRate: 0.2, ventIds: ['hot#v0']],
    ]
    Map cfg = script.getDabV2Config()
    cfg.minVentOpenGlobalPct = 35.0
    cfg.minVentOpen = [:]

    when: 'the zone is evaluated while actively cooling'
    Map result = script.evaluateDabV2Zone(rooms, 22.0G, 'cooling', cfg, [hour: 12], null)
    Map targets = result.targets as Map

    then: 'the satisfied active room is commanded at least the configured minimum opening (R7.26)'
    (targets['cool'] as double) >= 35.0d - EPS

    and: 'combined airflow is still at or above the safety floor (R7.27)'
    (result.combinedOpenPct as double) >= script.sfClampSafetyFloor(20.0) - EPS
  }
}
