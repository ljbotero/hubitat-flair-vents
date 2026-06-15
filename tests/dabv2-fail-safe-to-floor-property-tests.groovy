
// R4 fail-safe-to-floor property test (Task 6.5; R4.11/R4.12/R4.13/R4.14).
//
// Implements Correctness Property 26 from design.md (§6.3) EXACTLY — one
// property per feature method, tagged with the exact property heading:
//
//   Property 26: Fail-safe-to-floor under throttling  (R4.11/R4.12/R4.13/R4.14)
//     For any throttle pattern, the dispatch plan converges toward — never
//     below — the floor, and floor-required opens are dispatched regardless of
//     coalescing or the per-cycle cap.
//     Validates: Requirements 4.11, 4.12, 4.13, 4.14.
//
// STRICT TDD (Task 6.5): this spec encodes the REQUIRED behavior that Task 6.6
// must satisfy. It drives the REAL dispatch-planning path on the app under the
// hubitat_ci sandbox:
//
//     sfApply (single Safety_Floor choke point, R8.1/R8.2)
//        -> computeFloorRequiredVentIds (classify the floor-forced opens)
//        -> dabV2PlanGroupDispatch (anti-chatter + per-cycle-cap coalescing)
//
// A "throttle pattern" is modelled as the two coalescing controls the design
// names for R4.13/R4.14: an aggressive per-cycle move cap
// (`maxMovesPerCycle`, the `VENT_MOVE_MAX_PER_CYCLE` knob) plus an active
// anti-chatter cooldown on every room-group. Under that pressure the plan must
// still leave combined airflow at or above the floor.
//
// Each iteration builds a zone of 2-6 single-vent active rooms with randomized
// current apertures and allocator base targets, runs sfApply to obtain the
// floor-safe targets + floor-required set, then plans the dispatch under a
// randomized throttle pattern and reconstructs what the plan would actually
// command: APPLIED groups move to their target, HELD groups stay at their
// confirmed current aperture (exactly what `dispatchDabV2Targets` does — it
// only PATCHes applied groups). Combined airflow is the mean per-vent aperture
// of that reconstructed dispatch (1 vent/room, no conventional/inactive vents,
// so `sfCombinedOpenPct` reduces to the mean).
//
// Red-vs-green status is split deliberately so Task 6.6's target is explicit:
//
//   GREEN now (prior dispatch work already satisfies these):
//     (A) Floor-required opens are exempt from the per-cycle cap — every
//         floor-forced open whose target exceeds its current aperture is
//         applied even when the cap is smaller than the floor-required count
//         (R4.14).
//     (B) Floor-required opens are exempt from burst coalescing — the same
//         opens are applied even while every room-group sits inside the
//         anti-chatter cooldown (R4.13).
//
//   RED until Task 6.6 (the genuinely-failing assertion):
//     (C) Fail-safe-to-floor convergence — combined airflow of the actually-
//         dispatched plan is never below the floor (R4.11/R4.12). The current
//         planner classifies a move as floor-required only when sfApply RAISED
//         it above its allocator base target; it does NOT account for an
//         ordinary open that the floor silently depends on. When such a
//         load-bearing ordinary open (target high, current low) is held by the
//         per-cycle cap / cooldown, the reconstructed dispatch drops below the
//         floor — vents are left "stuck closed" under throttling, the post-282/
//         284 failure mode. Task 6.6 ("route the dispatch plan through sfApply;
//         exempt strictly-floor-required moves from coalescing and the cap")
//         must make (C) hold for every throttle pattern.
//
// The class name contains "Property" so `--tests '*Property*'` selects it; the
// `where:` block drives PropertyGen.ITERATIONS reproducible scenarios.
//
// Run `./gradlew test --tests '*FailSafeToFloor*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification

class Dabv2FailSafeToFloorPropertySpec extends Specification {

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

  private static final double EPS = 1e-6d
  private static final long NOW_MS = 10_000_000L

  // Built once and reused: sfApply, computeFloorRequiredVentIds and
  // dabV2PlanGroupDispatch are all PURE, so a single sandbox script serves every
  // randomized iteration with no cross-run state leakage.
  @Shared Object script

  def setupSpec() {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': [debugLevel: 1, safetyFloorPct: 40])
    script.atomicState = [:]
  }

  def 'Feature: hubitat-flair-vents-dab-v2, Property 26: fail-safe-to-floor under throttling — the dispatch plan converges toward (never below) the floor and floor-required opens dispatch regardless of coalescing or the per-cycle cap'() {
    given: 'a randomized single-vent active zone and an aggressive throttle pattern'
    def g = PropertyGen.forIteration(i)
    int nRooms = g.nextInt(2, 6)
    double floor = (double) g.pick([45.0d, 50.0d, 55.0d, 60.0d])
    int gran = 5

    // The floor choke-point settings: 1 vent/room, no conventional/inactive
    // devices, so combined airflow is the plain mean of the per-vent apertures.
    Map settings = script.dabv2NewAllocSettings([
        safetyFloorPct: floor, conventionalVents: 0, conventionalOpenPct: 100.0d,
        inactiveCount: 0, inactiveOpenPctSum: 0.0d, granularity: gran])

    List sfRooms = []
    List planRooms = []
    Map baseTargets = [:]
    Map currentById = [:]
    (0..<nRooms).each { int k ->
      String roomId = "r${k}".toString()
      List ventIds = ["${roomId}#v0".toString()]
      // Apertures biased low and base targets spread across the full range so a
      // "load-bearing ordinary open" (high target, low current that the floor
      // silently relies on) shows up frequently.
      double current = (double) g.nextInt(0, 8) * 5.0d
      double baseTarget = (double) g.nextInt(0, 20) * 5.0d
      // Some rooms still need conditioning (signedErrorC > 0) so sfApply has
      // raisable active capacity; others are satisfied.
      double signedErrorC = g.nextDouble(-2.0d, 4.0d)
      sfRooms << [roomId: roomId, active: true, ventIds: ventIds, signedErrorC: signedErrorC]
      planRooms << [roomId: roomId, ventIds: ventIds, currentOpen: current]
      baseTargets[roomId] = baseTarget
      currentById[roomId] = current
    }

    // The throttle pattern: an aggressive per-cycle cap (0 or 1 ordinary move)
    // and — on roughly half the iterations — an active cooldown on every group.
    int cap = g.nextInt(0, 1)
    boolean cooldownActive = g.nextBoolean()
    Map lastGroupMoveMs = [:]
    if (cooldownActive) {
      sfRooms.each { lastGroupMoveMs[it.roomId] = NOW_MS - 1000L }   // inside the 3-min cooldown
    }

    when: 'the floor choke point runs, then the throttled group dispatch is planned'
    def floored = script.sfApply(baseTargets, sfRooms, settings)
    Map safeTargets = (Map) floored[0]
    Set floorRequired = script.computeFloorRequiredVentIds(baseTargets, safeTargets)
    def plan = script.dabV2PlanGroupDispatch(
        safeTargets, floorRequired, planRooms, lastGroupMoveMs, NOW_MS,
        [maxMovesPerCycle: cap, floorPct: floor, granularity: gran])

    // Reconstruct what dispatchDabV2Targets would actually command: APPLIED
    // groups move to their (rounded) target; HELD groups stay at their confirmed
    // current aperture. Combined airflow is the mean over the single vents.
    Map commanded = [:]
    plan.each { d ->
      commanded[d.roomId as String] =
        d.apply ? (script.roundToNearestMultiple(d.target as BigDecimal) as double)
                : (currentById[d.roomId as String] as double)
    }
    double combined = commanded.isEmpty() ? 0.0d :
        (commanded.values().sum() as double) / commanded.size()
    double clampedFloor = script.sfClampSafetyFloor(floor)

    then: '(A) GREEN: every floor-required OPEN is applied despite the per-cycle cap (R4.14)'
    plan.findAll { d -> d.mustOpen && (d.target as double) > (currentById[d.roomId as String] as double) + EPS }
        .every { d -> d.apply }

    and: '(B) GREEN: those floor-required opens are applied despite the anti-chatter cooldown (R4.13)'
    !cooldownActive || plan.findAll { d ->
        d.mustOpen && (d.target as double) > (currentById[d.roomId as String] as double) + EPS
      }.every { d -> d.apply }

    and: '(C) RED until 6.6: the dispatched plan keeps combined airflow at or above the floor (R4.11/R4.12)'
    combined >= clampedFloor - EPS

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }
}
