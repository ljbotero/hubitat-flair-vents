
import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based tests for the PURE Allocator (task 6.1):
 * {@code Dabv2Allocator.allocate(rooms, setpointC, mode, settings)}.
 *
 * Implements Correctness Properties 2 and 5 from design.md EXACTLY (one
 * property per feature method, each tagged with the exact property heading):
 *
 *   Property 2: No overcooling / overheating bias
 *   "For any active-room set, a room that has reached or passed setpoint in the
 *    conditioning direction is allocated 0 % before the floor is applied, and
 *    no unsatisfied room is throttled to a predicted finish time earlier than
 *    the bottleneck horizon tau*."
 *   Validates: Requirements 7.3, 7.4, 8.2, 5.3.
 *
 *   Property 5: Apertures are bounded
 *   "For any inputs, every commanded aperture produced by the Allocator and the
 *    Safety_Floor lies in the inclusive range 0-100 %."
 *   Validates: Requirements 5.5.
 *
 * Mirrors the Reference `hvac_vent_optimizer/balance.py` allocate (A1.1-A1.3):
 *  - A room is *satisfied* when it has reached/passed setpoint in the
 *    conditioning direction within hysteresis (cooling: temp <= setpoint - hyst;
 *    heating: temp >= setpoint + hyst). Satisfied -> target = 0 (overshoot
 *    close), BEFORE the Safety_Floor is applied.
 *  - The convergence error for an unsatisfied room is its distance to the
 *    hysteresis target (setpoint -/+ hyst), which is strictly positive exactly
 *    when the room is unsatisfied.
 *  - tau_i = err_i / (e_i * flow_i(knee_i)) using the room's KNEE (effective-max
 *    airflow), not 100 %; tau* = max_i tau_i. No unsatisfied room may be
 *    throttled (where it has control, i.e. commanded aperture > 0) to converge
 *    before tau*.
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS (100)
 * reproducible randomized scenarios including the degenerate shapes (no active
 * rooms / single room / all satisfied).
 */
class AllocatorClassifyPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-6d

  def 'Feature: hubitat-flair-vents-dab-v2, Property 2: No overcooling / overheating bias'() {
    given: 'a randomized active-room scenario (mixed satisfied / unsatisfied)'
    def g = PropertyGen.forIteration(i)
    Map scenario = makeScenario(g, i)
    List rooms = scenario.rooms as List
    double setpointC = scenario.setpointC as double
    String mode = scenario.mode as String
    Map s = scenario.settings as Map
    int step = Math.max(1, s.granularity)

    when: 'the Allocator allocates (PRE-floor)'
    def res = lib.allocAllocate(rooms, setpointC, mode, s)
    Map<String, Double> targets = res.targets

    then: 'targets are emitted for ACTIVE rooms only'
    Set<String> activeIds = rooms.findAll { it.active }*.roomId as Set
    targets.keySet() == activeIds

    and: 'every active room that has reached/passed setpoint is closed to 0 % (overshoot close)'
    rooms.findAll { it.active && satisfied(it, setpointC, mode, s.hysteresisC) }.every {
      Math.abs(targets[it.roomId]) <= EPS
    }

    and: 'no unsatisfied room is throttled to converge earlier than the bottleneck horizon tau* (within one granularity step)'
    List unsat = rooms.findAll {
      it.active && !satisfied(it, setpointC, mode, s.hysteresisC)
    }
    double tauStar = bottleneckHorizon(unsat, setpointC, mode, s.hysteresisC)
    // Throttled rooms round to the NEAREST granularity step (Reference parity,
    // task 8.3), which can over-deliver by at most half a step — so a room may
    // finish marginally before tau*. The honest lower bound on the predicted
    // finish is therefore the finish at the exact synchronized aperture pushed
    // UP by one full granularity step (a sound over-approximation of the
    // round-to-nearest band). Rooms commanded to 0 % are at their minimum
    // aperture (leak-limited) and cannot be throttled further.
    unsat.every { r ->
      double a = targets[r.roomId]
      if (a <= EPS) { return true }
      double requiredRate = convErr(r, setpointC, mode, s.hysteresisC) / tauStar
      double requiredFlow = requiredRate / Math.max(1e-9d, r.efficiency)
      double aExact = inverseOf(r, requiredFlow)
      double aHi = Math.min((double) kneeOf(r), aExact + step)
      double finish = predictedFinish(r, a, setpointC, mode, s.hysteresisC)
      double lower = predictedFinish(r, aHi, setpointC, mode, s.hysteresisC)
      finish >= lower - EPS
    }

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  def 'Feature: hubitat-flair-vents-dab-v2, Property 5: Apertures are bounded'() {
    given: 'a randomized scenario'
    def g = PropertyGen.forIteration(i)
    Map scenario = makeScenario(g, i)
    List rooms = scenario.rooms as List
    double setpointC = scenario.setpointC as double
    String mode = scenario.mode as String
    Map s = scenario.settings as Map

    when: 'the Allocator allocates and the Safety_Floor is then enforced'
    def res = lib.allocAllocate(rooms, setpointC, mode, s)
    Map<String, Double> allocTargets = res.targets
    def floored = lib.sfApply(allocTargets, rooms, s)
    Map<String, Double> finalTargets = (Map<String, Double>) floored[0]

    then: 'every aperture produced by the Allocator lies in [0, 100]'
    allocTargets.values().every { it >= -EPS && it <= 100.0d + EPS }

    and: 'every aperture produced by the Safety_Floor lies in [0, 100]'
    finalTargets.values().every { it >= -EPS && it <= 100.0d + EPS }

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Scenario generation — covers the full input space plus the degenerate
  // shapes called out by Property 2 (no active rooms / single / all satisfied),
  // a mix of learned-curve and scalar-leak rooms, both conditioning modes.
  // ---------------------------------------------------------------------------

  private Map makeScenario(PropertyGen g, int i) {
    String mode = g.nextBoolean() ? 'cooling' : 'heating'
    double setpointC = g.nextDouble(18.0d, 24.0d)

    int nRooms
    String shape
    if (i % 13 == 0) { shape = 'no-active'; nRooms = g.nextInt(0, 3) }
    else if (i % 11 == 0) { shape = 'single'; nRooms = 1 }
    else if (i % 7 == 0) { shape = 'all-satisfied'; nRooms = g.nextInt(1, 5) }
    else { shape = 'mixed'; nRooms = g.nextInt(1, 6) }

    boolean heating = mode == 'heating'
    List rooms = []
    (0..<nRooms).each { int k ->
      String roomId = "r${k}"
      boolean active
      if (shape == 'no-active') { active = false }
      else if (shape == 'single' || shape == 'all-satisfied') { active = true }
      else { active = g.nextInt(0, 9) < 7 }

      boolean satisfied = (shape == 'all-satisfied') ? true : g.nextBoolean()
      double hyst = 0.3d
      double tempC
      if (heating) {
        // satisfied (heating): temp >= setpoint + hyst; unsatisfied: below that
        tempC = satisfied ? setpointC + hyst + g.nextDouble(0.0d, 4.0d)
                          : setpointC + hyst - g.nextDouble(0.1d, 5.0d)
      } else {
        // satisfied (cooling): temp <= setpoint - hyst; unsatisfied: above that
        tempC = satisfied ? setpointC - hyst - g.nextDouble(0.0d, 4.0d)
                          : setpointC - hyst + g.nextDouble(0.1d, 5.0d)
      }

      Map r = [:]
      r.roomId = roomId
      r.active = active
      r.tempC = tempC
      r.efficiency = g.nextDouble(0.02d, 0.5d)
      double leak = g.nextDouble(0.0d, 0.2d)
      r.leak = leak
      r.currentOpen = g.nextDouble(0.0d, 100.0d)
      int nVents = g.nextInt(1, 3)
      r.ventIds = (0..<nVents).collect { "${roomId}#v${it}".toString() }
      r.signedErrorC = heating ? (setpointC - tempC) : (tempC - setpointC)
      // ~half the rooms carry a learned curve; the rest use the scalar-leak fallback.
      r.curve = g.nextBoolean() ? lib.lrnSeedLinear(leak) : null
      rooms << r
    }

    Map s = lib.dabv2NewAllocSettings()
    s.hysteresisC = 0.3d
    s.granularity = g.nextInt(1, 10)
    s.safetyFloorPct = g.nextDouble(20.0d, 90.0d)
    s.conventionalVents = g.nextInt(0, 3)
    s.conventionalOpenPct = g.nextDouble(0.0d, 100.0d)

    return [rooms: rooms, setpointC: setpointC, mode: mode, settings: s]
  }

  // ---------------------------------------------------------------------------
  // Independent oracle: classification, knee, flow and finish-time math derived
  // straight from the public Learning_Model primitives (NOT from the Allocator
  // internals), so the assertions are an independent check of allocate(...).
  // ---------------------------------------------------------------------------

  private static boolean satisfied(Map r, double setpointC, String mode, double hyst) {
    boolean heating = (mode != null && mode.toLowerCase().contains('heat'))
    return heating ? (r.tempC >= setpointC + hyst) : (r.tempC <= setpointC - hyst)
  }

  /** Convergence error: distance to the hysteresis target; > 0 iff unsatisfied. */
  private static double convErr(Map r, double setpointC, String mode, double hyst) {
    boolean heating = (mode != null && mode.toLowerCase().contains('heat'))
    return heating ? ((setpointC + hyst) - r.tempC) : (r.tempC - (setpointC - hyst))
  }

  private double flowAt(Map r, double aperturePct) {
    if (r.curve != null) { return lib.lrnVentCurveFlow(r.curve, aperturePct) }
    return lib.lrnFlowLinear(r.leak, aperturePct / 100.0d)
  }

  private int kneeOf(Map r) {
    return r.curve != null ? lib.lrnVentCurveKnee(r.curve) : 100
  }

  /** Aperture % delivering {@code flowFraction} — mirrors Allocator.inverseOf. */
  private double inverseOf(Map r, double flowFraction) {
    if (r.curve != null) { return lib.lrnVentCurveInverse(r.curve, flowFraction) }
    double leak = Math.max(0.0d, Math.min(1.0d, r.leak))
    double denom = 1.0d - leak
    if (denom <= 1e-9d) { return 0.0d }
    double f = Math.max(0.0d, Math.min(1.0d, flowFraction))
    double a = (f - leak) / denom
    return Math.max(0.0d, Math.min(1.0d, a)) * 100.0d
  }

  /** tau* = max over unsatisfied rooms of err_i / (e_i * flow_i(knee_i)). */
  private double bottleneckHorizon(List unsat,
                                          double setpointC, String mode, double hyst) {
    double tau = 0.0d
    unsat.each { r ->
      double rate = Math.max(1e-9d, r.efficiency * flowAt(r, kneeOf(r) as double))
      double t = convErr(r, setpointC, mode, hyst) / rate
      if (t > tau) { tau = t }
    }
    return tau
  }

  /** Predicted minutes to reach the hysteresis target at commanded aperture. */
  private double predictedFinish(Map r, double aperturePct,
                                        double setpointC, String mode, double hyst) {
    double rate = Math.max(1e-9d, r.efficiency * flowAt(r, aperturePct))
    return convErr(r, setpointC, mode, hyst) / rate
  }
}
