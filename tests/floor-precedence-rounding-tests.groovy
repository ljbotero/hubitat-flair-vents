
// Floor-precedence & rounding-ordering specs (Task 3.3; R5.10, R5.11, R5.12).
//
// These are example / regression specs (NOT new numbered Correctness
// Properties) that pin down the documented ordering of the airflow choke
// points relative to granularity rounding:
//
//   safety floor  >  inactive-room close  >  configured minimum opening
//                                          >  granularity grid
//
// Requirements pinned here:
//   R5.10  Granularity rounding must NEVER push combined airflow below the
//          airflow safety floor; the single floor choke point runs AFTER
//          rounding so a rounded-down position can never starve the HVAC.
//   R5.11  A granularity-rounded position that would fall below the configured
//          minimum opening (R7.4) is reconciled, commanding at least the
//          minimum subject to the safety floor and inactive-room close rules.
//          (The minimum-opening tier itself is implemented in Task 17 and is
//          exercised by Property 29; here we pin the floor/inactive/grid tiers
//          that exist today and document the ordering the reconciliation lands
//          in.)
//   R5.12  When a room is inactive and "close vents on inactive rooms" is on,
//          the inactive-room close is applied BEFORE granularity rounding is
//          used to satisfy the safety floor on the remaining active vents.
//
// STRICT TDD: these specs encode the REQUIRED post-Task-3.4 behavior and are
// expected to be RED against the current code on the LEGACY path. The legacy
// dispatch (`atomicStateUpdateVentOpening`) raises vents to MIN_COMBINED_VENT_FLOW
// via `adjustVentOpeningsToEnsureMinimumAirflowTarget` (continuous increments)
// and THEN rounds each commanded position with `roundToNearestMultiple` --
// rounding runs AFTER the floor with no re-check, so a round-DOWN drops combined
// airflow below the floor (genuinely RED for G in {25,100} below). The DAB v2
// path already routes rounding through `dabV2GroupNormalize` BEFORE the single
// `sfApply` choke point (which raises on the same grid), so its end-to-end
// guard and the floor/inactive precedence guards already hold (regression
// guards that must STAY green through Task 3.4).
//
// The class name intentionally does NOT contain "Property" -- these are
// example/regression specs, not seeded property tests.
//
// Run `./gradlew test --tests '*FloorPrecedenceRounding*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

class FloorPrecedenceRoundingSpec extends Specification {

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

  // The five supported granularity grids (R5.1).
  private static final List GRID = [5, 10, 25, 50, 100]

  // The legacy combined-airflow safety floor enforced by
  // `adjustVentOpeningsToEnsureMinimumAirflowTarget` (MIN_COMBINED_VENT_FLOW).
  private static final double LEGACY_FLOOR = 30.0d
  private static final double EPS = 1e-6d

  // One sandbox script per granularity (settings.ventGranularity drives both
  // the legacy `roundToNearestMultiple` and the DAB v2 alloc-settings grid).
  @Shared Map<Integer, Object> scriptByG = [:]

  def setupSpec() {
    GRID.each { int g -> scriptByG[g] = buildScript(g) }
  }

  private Object buildScript(int g) {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': [safetyFloorPct: 30, thermostat1AdditionalStandardVents: 0,
                            ventGranularity: g.toString()])
    script.atomicState = [:]
    return script
  }

  // ---------------------------------------------------------------------------
  // R5.10 — LEGACY path: the floor binds (combined raised to 30%), then
  // dispatch rounding must NOT drop combined below the floor.
  //
  // GENUINELY RED for G in {25,100}: the legacy floor adjuster lands the single
  // active vent at exactly 30.0% (combined == floor), and `roundToNearestMultiple`
  // then rounds it DOWN to 25% (G=25) or 0% (G=100) -- combined falls below the
  // floor because rounding runs AFTER the floor with no re-check.
  // (G in {5,10} keep 30; G=50 rounds UP to 50; those rows stay green.)
  // ---------------------------------------------------------------------------
  @Unroll
  def 'R5.10 legacy: granularity rounding after the floor keeps combined >= floor (G=#g)'() {
    given: 'a single active vent and no conventional vents under granularity G'
    def script = scriptByG[g]
    Map rateAndTempPerVentId = ['r0#v0': [temp: 20.0, name: 'r0', rate: 0.1]]
    Map calcPercentOpen = ['r0#v0': 0.0]

    when: 'the legacy combined-airflow floor is enforced (continuous raise to 30%)'
    Map floored = script.adjustVentOpeningsToEnsureMinimumAirflowTarget(
        rateAndTempPerVentId, 'heating', calcPercentOpen, 0)
    BigDecimal flooredPct = floored['r0#v0'] as BigDecimal

    and: 'the dispatcher rounds the floored position to the configured grid (as atomicStateUpdateVentOpening does)'
    int dispatched = script.roundToNearestMultiple(flooredPct)
    // Legacy combined = (100 * sumDispatched) / (deviceCount * 100); 1 device, 0 standard vents.
    double combined = ((double) dispatched) / 1.0d

    then: 'the floor genuinely bound — the continuous plan met the floor before rounding'
    (flooredPct as double) >= LEGACY_FLOOR - EPS

    and: 'rounding the dispatched value must not lower combined airflow below the floor (R5.10)'
    combined >= LEGACY_FLOOR - EPS

    where:
    g << GRID
  }

  // ---------------------------------------------------------------------------
  // R5.10 — DAB v2 path: rounding is applied (group-normalize) BEFORE the single
  // `sfApply` choke point, which raises on the same grid, so the final dispatch
  // rounding is a no-op and combined never falls below the floor.
  //
  // Regression GUARD: already green; must STAY green through Task 3.4.
  // ---------------------------------------------------------------------------
  @Unroll
  def 'R5.10 DAB v2: sfApply runs after rounding so dispatch rounding never drops combined below the floor (G=#g)'() {
    given: 'a single under-satisfied active room with a closed (0%) pre-floor target'
    def script = scriptByG[g]
    Map settings = script.dabv2NewAllocSettings([
        safetyFloorPct: 30.0d, conventionalVents: 0, conventionalOpenPct: 100.0d,
        inactiveCount: 0, inactiveOpenPctSum: 0.0d, granularity: g])
    List rooms = [[roomId: 'r0', active: true, ventIds: ['r0#v0'], signedErrorC: 2.0d]]

    when: 'the DAB v2 pipeline rounds, then applies the single floor choke point'
    Map normalized = script.dabV2GroupNormalize([r0: 0.0d], g)
    def res = script.sfApply(normalized, rooms, settings)
    Map safeTargets = (Map) res[0]

    and: 'the dispatcher snaps the floor-safe target onto the grid'
    int dispatched = script.roundToNearestMultiple(safeTargets['r0'] as BigDecimal)
    double combined = script.sfCombinedOpenPct(['r0#v0': (double) dispatched], settings)

    then: 'combined open airflow stays at or above the floor after dispatch rounding (R5.10)'
    combined >= 30.0d - EPS

    and: 'the dispatched value is an exact multiple of G in 0-100'
    dispatched >= 0 && dispatched <= 100 && (dispatched % g == 0)

    where:
    g << GRID
  }

  // ---------------------------------------------------------------------------
  // R5.12 / precedence — inactive-room close is honored BEFORE the floor reopens
  // it: with active capacity available the floor is met by raising the ACTIVE
  // vent (on the grid), and the closed inactive room is NOT opened by rounding.
  //
  // Regression GUARD (DAB v2 floor): documents "inactive-room close > granularity
  // grid" — already green; must STAY green through Task 3.4.
  // ---------------------------------------------------------------------------
  def 'R5.12 precedence: inactive-room close is preserved while the floor is met on active vents'() {
    given: 'one active under-satisfied room and one closed inactive room, floor 30%, G=25'
    def script = scriptByG[25]
    Map settings = script.dabv2NewAllocSettings([
        safetyFloorPct: 30.0d, conventionalVents: 0, conventionalOpenPct: 100.0d,
        inactiveCount: 1, inactiveOpenPctSum: 0.0d, granularity: 25])
    List rooms = [
      [roomId: 'r0', active: true,  ventIds: ['r0#v0'], signedErrorC: 2.0d],
      [roomId: 'r1', active: false, ventIds: ['r1#v0'], signedErrorC: -1.0d],
    ]

    when: 'rounding then the single floor choke point are applied to the active-only plan'
    Map normalized = script.dabV2GroupNormalize([r0: 0.0d], 25)
    def res = script.sfApply(normalized, rooms, settings)
    Map safeTargets = (Map) res[0]
    int dispatchedActive = script.roundToNearestMultiple(safeTargets['r0'] as BigDecimal)

    then: 'the floor is met by raising the ACTIVE vent on the grid (>= floor)'
    dispatchedActive >= 30

    and: 'the inactive room is left closed — rounding never reopens it (inactive-close > grid)'
    safeTargets['r1'] == null || (safeTargets['r1'] as double) <= EPS
  }

  // ---------------------------------------------------------------------------
  // Precedence — safety floor OUTRANKS the inactive-room close: when no active
  // capacity remains and total airflow is still below the floor, the inactive
  // room is reopened as a last resort.
  //
  // Regression GUARD: documents "safety floor > inactive-room close" — already
  // green; must STAY green through Task 3.4.
  // ---------------------------------------------------------------------------
  def 'R5.11 precedence: the safety floor outranks the inactive-room close (last-resort reopen)'() {
    given: 'a satisfied (closed) active room and a closed inactive room, floor 30%, G=25'
    def script = scriptByG[25]
    Map settings = script.dabv2NewAllocSettings([
        safetyFloorPct: 30.0d, conventionalVents: 0, conventionalOpenPct: 100.0d,
        inactiveCount: 1, inactiveOpenPctSum: 0.0d, granularity: 25])
    List rooms = [
      [roomId: 'r0', active: true,  ventIds: ['r0#v0'], signedErrorC: -1.0d],
      [roomId: 'r1', active: false, ventIds: ['r1#v0'], signedErrorC: -1.0d],
    ]

    when: 'the floor choke point runs with no raisable active capacity'
    def res = script.sfApply([r0: 0.0d], rooms, settings)
    Map safeTargets = (Map) res[0]
    boolean floorBinding = (boolean) res[1]

    then: 'the inactive room is reopened to satisfy the floor (safety floor > inactive-room close)'
    floorBinding
    (safeTargets['r1'] as double) > EPS
  }
}
