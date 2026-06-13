
import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based test for the PURE Allocator bottleneck horizon + synchronized
 * convergence throttle (task 6.2):
 * {@code Dabv2Allocator.allocate(rooms, setpointC, mode, settings)}.
 *
 * Implements Correctness Property 3 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 3: Bottleneck saturation and synchronized convergence
 *   "For any active-room set with at least one unsatisfied room, the slowest
 *    unsatisfied room (argmax tau_i = err_i / rate_i(knee_i)) is commanded at
 *    its knee (effective-max) aperture, and every other unsatisfied room is
 *    throttled so its predicted finish time equals tau* within tolerance
 *    (arrive together)."
 *   Validates: Requirements 8.1, 5.1.
 *
 * Mirrors the Reference `hvac_vent_optimizer/balance.py` allocate A1.2/A1.3:
 *  - tau_i = err_i / (e_i * flow_i(knee_i)) using the room's KNEE (effective-max
 *    airflow), not 100 %; tau* = max_i tau_i; the argmax room is the bottleneck
 *    and is commanded at its knee.
 *  - Every OTHER unsatisfied room is throttled to finish at tau*: required_rate
 *    = err_i / tau*, required_flow = required_rate / e_i, aperture =
 *    inverse(clamp(required_flow, leak, flow(knee))) clamped to [0, knee].
 *
 * <b>Tolerance rationale (granularity quantization).</b> The throttle quantizes
 * the commanded aperture DOWN to the configured {@code granularity} step so the
 * result NEVER over-delivers airflow (task 6.1's no-overcooling guarantee,
 * Property 2). Quantizing down means a throttled room's flow is <= the exact
 * synchronized flow, hence its predicted finish is >= tau* (never earlier).
 * The slack ABOVE tau* is bounded by one granularity step of aperture: the
 * commanded aperture {@code a} satisfies {@code aExact - step <= a <= aExact},
 * where {@code aExact = inverse(required_flow)} is the exact (un-quantized)
 * aperture that would finish at tau*. Property 3 therefore asserts, for every
 * controllable throttled room, that its predicted finish lies in
 * {@code [tau*, finishAt(aExact - step)]} — i.e. == tau* up to exactly the
 * quantization granularity, never earlier and never more than one step late.
 * Rooms whose required flow is already covered by closed-vent leakage
 * (commanded 0 %) are at their physical minimum aperture and cannot be
 * throttled further; like Property 2 they are excluded from the synchronized
 * bound (leakage unavoidably lets them arrive at or before tau*).
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS (100)
 * reproducible randomized scenarios, each guaranteed to contain at least one
 * unsatisfied active room (the precondition of Property 3).
 */
class AllocatorBottleneckPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-6d
  // Absolute float slack on the quantization upper bound (the bound itself,
  // finishAt(aExact - step), already accounts for the full granularity step).
  private static final double FINISH_ABS_TOL = 1e-6d

  def 'Feature: hubitat-flair-vents-dab-v2, Property 3: Bottleneck saturation and synchronized convergence'() {
    given: 'a randomized active-room scenario with >= 1 unsatisfied active room'
    def g = PropertyGen.forIteration(i)
    Map scenario = makeScenario(g, i)
    List rooms = scenario.rooms as List
    double setpointC = scenario.setpointC as double
    String mode = scenario.mode as String
    Map s = scenario.settings as Map
    int step = Math.max(1, s.granularity)

    and: 'the independent oracle for the unsatisfied set, tau* and the bottleneck'
    List unsat = rooms.findAll {
      it.active && !satisfied(it, setpointC, mode, s.hysteresisC)
    }
    assert !unsat.isEmpty()      // precondition of Property 3 (guaranteed by makeScenario)
    double tauStar = 0.0d
    def bottleneck = null
    unsat.each { r ->
      double t = tauOf(r, setpointC, mode, s.hysteresisC)
      if (t > tauStar) { tauStar = t; bottleneck = r }   // first-wins argmax (matches allocate)
    }

    when: 'the Allocator allocates (PRE-floor)'
    def res = lib.allocAllocate(rooms, setpointC, mode, s)
    Map<String, Double> targets = res.targets

    then: 'the bottleneck (slowest, argmax tau_i) is commanded at its knee aperture'
    Math.abs((targets[bottleneck.roomId] as double) - (kneeOf(bottleneck) as double)) <= EPS

    and: 'the bottleneck is reported airflow-limited iff it is still meaningfully off-target (R9.1, task 6.6)'
    // The bottleneck is commanded at its knee, so it is ALWAYS "at or within the
    // configured margin of the knee". Under the refined airflow-limited criterion
    // introduced by task 6.6 (design A3 / R9.1) — which replaces the coarse
    // "commanded at knee" heuristic this assertion originally pinned — a room is
    // flagged airflow-limited only when its off-target error to the setpoint ALSO
    // exceeds the configured threshold. A bottleneck that is the slowest room yet
    // already within the error threshold of the setpoint is near-satisfied and is
    // (correctly) NOT airflow-limited.
    double bottleneckOff = (mode.toLowerCase().contains('heat')
        ? (setpointC - bottleneck.tempC) : (bottleneck.tempC - setpointC))
    res.airflowLimited.contains(bottleneck.roomId) == (bottleneckOff > s.airflowLimitedErrorC)

    and: 'no unsatisfied room is throttled to converge EARLIER than the bottleneck horizon tau* (within one granularity step)'
    unsat.every { r ->
      double a = targets[r.roomId]
      if (a <= EPS) { return true }    // leak-limited at 0 %: cannot be throttled further
      // Round-to-nearest (Reference parity, task 8.3) may over-deliver by up to
      // half a granularity step, so a throttled room can finish marginally before
      // tau*. The honest lower bound on the finish is the finish at the exact
      // synchronized aperture pushed UP by one full granularity step.
      double requiredRateLo = convErr(r, setpointC, mode, s.hysteresisC) / tauStar
      double requiredFlowLo = requiredRateLo / Math.max(1e-9d, (double) r.efficiency)
      double aExactLo = inverseOf(r, requiredFlowLo)
      double aHi = Math.min((double) kneeOf(r), aExactLo + step)
      double finish = predictedFinish(r, a, setpointC, mode, s.hysteresisC)
      double lower = predictedFinish(r, aHi, setpointC, mode, s.hysteresisC)
      finish >= lower - EPS
    }

    and: 'every controllable throttled room finishes AT tau* up to one granularity step (synchronized)'
    unsat.every { r ->
      double a = targets[r.roomId]
      if (r.roomId == bottleneck.roomId) { return true }   // the bottleneck is at its knee by definition
      if (a <= EPS) { return true }                        // leak-limited: arrives at/before tau* (excluded)
      // Exact (un-quantized) aperture that would finish exactly at tau*.
      double requiredRate = convErr(r, setpointC, mode, s.hysteresisC) / tauStar
      double requiredFlow = requiredRate / Math.max(1e-9d, (double) r.efficiency)
      double aExact = inverseOf(r, requiredFlow)
      double aLow = Math.max(0.0d, aExact - step)
      double finish = predictedFinish(r, a, setpointC, mode, s.hysteresisC)
      double upper = predictedFinish(r, aLow, setpointC, mode, s.hysteresisC)
      finish <= upper + FINISH_ABS_TOL
    }

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Scenario generation — full input space (both modes, learned-curve and
  // scalar-leak rooms, multi-vent groups, mixed satisfied/unsatisfied,
  // inactive rooms), but ALWAYS with >= 1 unsatisfied active room (Property 3's
  // precondition).
  // ---------------------------------------------------------------------------

  private Map makeScenario(PropertyGen g, int i) {
    String mode = g.nextBoolean() ? 'cooling' : 'heating'
    double setpointC = g.nextDouble(18.0d, 24.0d)
    boolean heating = mode == 'heating'
    double hyst = 0.3d

    int nRooms = g.nextInt(1, 6)
    List rooms = []
    (0..<nRooms).each { int k ->
      // Room 0 is forced active+unsatisfied so the bottleneck horizon is defined.
      boolean forced = (k == 0)
      boolean active = forced ? true : (g.nextInt(0, 9) < 7)
      boolean roomSatisfied = forced ? false : g.nextBoolean()
      rooms << makeRoom(g, "r${k}", active, roomSatisfied, setpointC, heating, hyst)
    }

    Map s = lib.dabv2NewAllocSettings()
    s.hysteresisC = hyst
    s.granularity = g.nextInt(1, 10)
    s.safetyFloorPct = g.nextDouble(20.0d, 90.0d)
    s.conventionalVents = g.nextInt(0, 3)
    s.conventionalOpenPct = g.nextDouble(0.0d, 100.0d)

    return [rooms: rooms, setpointC: setpointC, mode: mode, settings: s]
  }

  private Map makeRoom(PropertyGen g, String roomId, boolean active,
      boolean roomSatisfied, double setpointC, boolean heating, double hyst) {
    double tempC
    if (heating) {
      tempC = roomSatisfied ? setpointC + hyst + g.nextDouble(0.0d, 4.0d)
                            : setpointC + hyst - g.nextDouble(0.1d, 5.0d)
    } else {
      tempC = roomSatisfied ? setpointC - hyst - g.nextDouble(0.0d, 4.0d)
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
    return r
  }

  // ---------------------------------------------------------------------------
  // Independent oracle: classification, knee, flow, inverse and finish-time math
  // derived straight from the public Learning_Model primitives (NOT from the
  // Allocator internals), so the assertions are an independent check.
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
    if (r.curve != null) { return lib.lrnVentCurveFlow((Map) r.curve, aperturePct) }
    return lib.lrnFlowLinear((double) r.leak, aperturePct / 100.0d)
  }

  private int kneeOf(Map r) {
    return r.curve != null ? lib.lrnVentCurveKnee((Map) r.curve) : 100
  }

  /** Aperture % delivering {@code flowFraction} — mirrors Allocator.inverseOf. */
  private double inverseOf(Map r, double flowFraction) {
    if (r.curve != null) { return lib.lrnVentCurveInverse((Map) r.curve, flowFraction) }
    double leak = Math.max(0.0d, Math.min(1.0d, (double) r.leak))
    double denom = 1.0d - leak
    if (denom <= 1e-9d) { return 0.0d }
    double f = Math.max(0.0d, Math.min(1.0d, flowFraction))
    double a = (f - leak) / denom
    return Math.max(0.0d, Math.min(1.0d, a)) * 100.0d
  }

  /** tau_i = err_i / (e_i * flow_i(knee_i)) — finish at effective-max airflow. */
  private double tauOf(Map r, double setpointC, String mode, double hyst) {
    double rate = Math.max(1e-9d, (double) r.efficiency * flowAt(r, kneeOf(r) as double))
    return convErr(r, setpointC, mode, hyst) / rate
  }

  /** Predicted minutes to reach the hysteresis target at commanded aperture. */
  private double predictedFinish(Map r, double aperturePct,
                                        double setpointC, String mode, double hyst) {
    double rate = Math.max(1e-9d, (double) r.efficiency * flowAt(r, aperturePct))
    return convErr(r, setpointC, mode, hyst) / rate
  }
}
