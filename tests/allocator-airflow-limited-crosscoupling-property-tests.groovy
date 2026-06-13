
import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based test for the PURE Allocator airflow-limited detection +
 * bounded cross-coupling guard (task 6.6):
 * {@code Dabv2Allocator.allocate(rooms, setpointC, mode, settings, duct)}.
 *
 * Implements Correctness Property 10 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 10: Airflow-limited detection and cross-coupling
 *   "For any active-room set, a room is flagged airflow-limited exactly when its
 *    commanded aperture is at or within the configured margin of its knee AND
 *    its error exceeds the configured threshold; and when at least one room is
 *    airflow-limited with cross-coupling enabled, every at/past-setpoint active
 *    room is driven toward 0 % while the combined open percentage (after the
 *    floor) remains at or above the floor."
 *   Validates: Requirements 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7.
 *
 * Mirrors the Reference {@code hvac_vent_optimizer/balance.py} A3 (airflow-
 * limited detection) + A4 (cross-coupling):
 *  - A3 detection: room i is airflow-limited iff {@code a_i >= knee_i - margin}
 *    AND {@code err_i > error_c} — the precise R9.1 criterion, NOT the coarse
 *    "commanded at knee" heuristic the 6.1/6.2 stub used. The error is the
 *    room's off-target distance to the SETPOINT in the conditioning direction.
 *  - A4 cross-coupling (R9.3/R9.4): when >= 1 room is airflow-limited and still
 *    off-target AND cross-coupling is enabled, every active room AT OR PAST the
 *    setpoint is driven to 0 % pre-floor to redirect air to the laggard; the
 *    Safety_Floor then re-pads the not-yet-satisfied laggard (need-biased), so
 *    the combined open % after the floor never drops below the floor.
 *  - R9.5 duct signals: optional {@code DuctSignals} can VETO the push when no
 *    conditioned air is actually flowing (supply not in the conditioning
 *    direction, or non-positive pressure); their ABSENCE (null) degrades
 *    gracefully to the temperature/effectiveness heuristic (the push applies).
 *  - R9.6 configurable: {@code AllocSettings.crosscoupling} (default true)
 *    enables/disables the guard.
 *
 * <b>Scenario shape (guaranteed per iteration).</b> Every generated scenario
 * contains:
 *  - a LAGGARD: far off-target (error well above the threshold), very low
 *    efficiency, so it is the bottleneck (argmax tau) commanded at its knee and
 *    therefore reliably flagged airflow-limited (the cross-coupling trigger);
 *  - a CANARY: an active room AT or just PAST the setpoint (within hysteresis,
 *    so still technically unsatisfied) engineered so that WITHOUT cross-coupling
 *    it receives a strictly positive throttle aperture (granularity 1 keeps that
 *    aperture observable). Cross-coupling must drive the canary to exactly 0 % —
 *    this is what makes the "driven toward 0 %" assertion non-vacuous: a
 *    regression that dropped the guard would leave the canary at its positive
 *    baseline.
 *  - optional fillers (genuinely below-setpoint high-efficiency rooms, satisfied
 *    rooms, and inactive rooms) for realism.
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS (100)
 * reproducible randomized scenarios across both modes, both cross-coupling
 * toggles, and all three duct conditions (absent / confirming / vetoing).
 */
class AllocatorAirflowLimitedCrosscouplingPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-9d
  private static final double HYST = 0.3d

  def 'Feature: hubitat-flair-vents-dab-v2, Property 10: Airflow-limited detection and cross-coupling'() {
    given: 'a randomized scenario with a flagged laggard and an at/past-setpoint canary'
    def g = PropertyGen.forIteration(i)
    String mode = g.nextBoolean() ? 'cooling' : 'heating'
    boolean heating = mode == 'heating'
    double setpointC = g.nextDouble(18.0d, 24.0d)

    boolean crosscoupling = g.nextBoolean()
    Map s = makeSettings(g, crosscoupling)

    String ductChoice = g.pick(['none', 'confirm', 'veto']) as String
    Map duct = makeDuct(g, ductChoice, heating, setpointC)

    Map built = makeRooms(g, setpointC, heating)
    List rooms = built.rooms as List
    String canaryId = built.canaryId as String
    String laggardId = built.laggardId as String

    and: 'the no-cross-coupling BASELINE aperture of the canary (independent oracle)'
    Map sNoXc = cloneSettings(s)
    sNoXc.crosscoupling = false
    Map baseRes = lib.allocAllocate(rooms, setpointC, mode, sNoXc, null)
    double canaryBase = baseRes.targets[canaryId] as double

    expect: 'the canary baseline is strictly positive (so "driven to 0 %" is non-vacuous)'
    canaryBase > 0.0d + EPS

    when: 'the Allocator allocates with the scenario settings and duct signals'
    Map res = lib.allocAllocate(rooms, setpointC, mode, s, duct)
    Map<String, Double> targets = res.targets

    then: 'DETECTION (R9.1): a room is flagged airflow-limited iff aperture >= knee - margin AND error > threshold'
    activeRooms(rooms).every { Map r ->
      int knee = kneeOf(r)
      double a = targets[r.roomId] as double
      double off = offTarget(r, setpointC, heating)
      boolean expected = (a >= (knee - s.airflowLimitedMarginPct) - EPS) && (off > s.airflowLimitedErrorC)
      res.airflowLimited.contains(r.roomId) == expected
    }

    and: 'the far-off-target laggard at its knee IS flagged (the cross-coupling trigger exists)'
    res.airflowLimited.contains(laggardId)

    and: 'no inactive room is ever flagged airflow-limited'
    rooms.findAll { !it.active }.every { !res.airflowLimited.contains(it.roomId) }

    and: 'CROSS-COUPLING (R9.3/9.4/9.5/9.6) behaves per enablement + duct veto'
    boolean pushApplies = crosscoupling && !ductVetoes(duct, heating, setpointC)
    if (pushApplies) {
      // R9.3: every at/past-setpoint active room is driven to exactly 0 % pre-floor.
      assert activeRooms(rooms).findAll { offTarget(it, setpointC, heating) <= 0.0d }
          .every { Math.abs(targets[it.roomId] as double) <= EPS }
      // Non-vacuous: the canary (positive baseline) was forced to 0 %.
      assert Math.abs(targets[canaryId] as double) <= EPS
    } else {
      // R9.5/R9.6: absent push (disabled or vetoed) leaves the canary untouched
      // at its baseline throttle aperture (graceful degradation to the heuristic).
      assert Math.abs((targets[canaryId] as double) - canaryBase) <= EPS
    }

    and: 'the laggard (off-target, not at/past setpoint) is never zeroed by cross-coupling'
    (targets[laggardId] as double) > 0.0d + EPS

    and: 'FLOOR BOUND (R9.4): after the Safety_Floor, combined open % >= the clamped floor (or active capacity is exhausted)'
    def applied = lib.sfApply(targets, rooms, s)
    Map<String, Double> floored = applied[0] as Map<String, Double>
    double combined = combinedForActive(floored, rooms, s)
    // Reference-faithful floor (task 8.3): cross-coupling drives at/past-setpoint
    // rooms to 0 % and the floor re-pads using active NOT-YET-SATISFIED capacity
    // (and, as a last resort, inactive vents). When no such capacity remains
    // (every not-yet-satisfied active room is at 100 and there are no inactive
    // vents to reopen), the floor is physically at its achievable maximum — the
    // floor never reopens satisfied active rooms, mirroring the Reference. So the
    // bound holds UNLESS that capacity is genuinely exhausted.
    boolean unsatHeadroom = rooms.any {
      it.active && it.signedErrorC > 0.0d && ((floored[it.roomId] ?: 0.0d) as double) < 100.0d - 1e-6d
    }
    boolean inactiveAvail = s.inactiveCount > 0 && rooms.any { !it.active }
    combined >= lib.sfClampSafetyFloor(s.safetyFloorPct) - 1e-6d ||
      (!unsatHeadroom && !inactiveAvail)

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Scenario generation.
  // ---------------------------------------------------------------------------

  private Map makeSettings(PropertyGen g, boolean crosscoupling) {
    Map s = lib.dabv2NewAllocSettings()
    s.hysteresisC = HYST
    s.granularity = 1                       // keep the canary's small share observable
    s.safetyFloorPct = g.nextDouble(20.0d, 45.0d)
    s.conventionalVents = g.nextInt(0, 3)
    s.conventionalOpenPct = 100.0d
    s.crosscoupling = crosscoupling
    s.airflowLimitedMarginPct = g.nextDouble(0.0d, 15.0d)
    s.airflowLimitedErrorC = g.nextDouble(0.3d, 1.0d)
    s.horizonMin = g.nextDouble(10.0d, 45.0d)
    return s
  }

  private Map cloneSettings(Map s) {
    Map c = lib.dabv2NewAllocSettings()
    c.safetyFloorPct = s.safetyFloorPct
    c.conventionalVents = s.conventionalVents
    c.conventionalOpenPct = s.conventionalOpenPct
    c.inactiveOpenPctSum = s.inactiveOpenPctSum
    c.inactiveCount = s.inactiveCount
    c.granularity = s.granularity
    c.crosscoupling = s.crosscoupling
    c.hysteresisC = s.hysteresisC
    c.airflowLimitedMarginPct = s.airflowLimitedMarginPct
    c.airflowLimitedErrorC = s.airflowLimitedErrorC
    c.horizonMin = s.horizonMin
    c.spreadGuardrailC = s.spreadGuardrailC
    c.spreadImprovementDeadbandC = s.spreadImprovementDeadbandC
    return c
  }

  /**
   * Build the active/inactive room set. Guarantees exactly one flagged laggard
   * (bottleneck at knee, far off-target) and one canary (at/past setpoint, with a
   * strictly positive no-cross-coupling baseline aperture).
   */
  private static Map makeRooms(PropertyGen g, double setpointC, boolean heating) {
    List rooms = []

    // LAGGARD: far off-target, very low efficiency -> bottleneck commanded at
    // knee=100 and reliably flagged (error >> threshold).  tau_L = errL/eL in
    // [10, 25] dominates every other room's tau.
    double errL = g.nextDouble(1.5d, 2.5d)
    double eL = g.nextDouble(0.10d, 0.15d)
    double tempL = heating ? setpointC - errL : setpointC + errL
    rooms << room('laggard', true, tempL, setpointC, heating, eL,
        g.nextDouble(0.05d, 0.15d), null, g.nextInt(1, 2))

    // CANARY: at/past setpoint within hysteresis (still unsatisfied). convErr in
    // [0.15, 0.30] (so offTarget in [-0.15, 0]); low efficiency keeps its share
    // observable. leak 0 + linear model => baseline aperture = required_flow*100,
    // bounded by tau_L so it is strictly positive yet well below the knee.
    double convErrC = g.nextDouble(0.15d, 0.30d)
    double eC = g.nextDouble(0.04d, 0.08d)
    double tempC = heating ? (setpointC + HYST - convErrC) : (setpointC - HYST + convErrC)
    rooms << room('canary', true, tempC, setpointC, heating, eC, 0.0d, null, 1)

    // Optional fillers: genuinely below-setpoint, HIGH efficiency -> small tau,
    // small aperture (well below the knee, never flagged), never zeroed.
    int nFill = g.nextInt(0, 2)
    (0..<nFill).each { int k ->
      double offF = g.nextDouble(0.6d, 1.5d)
      double tF = heating ? setpointC - offF : setpointC + offF
      rooms << room("fill${k}".toString(), true, tF, setpointC, heating,
          g.nextDouble(0.30d, 0.50d), g.nextDouble(0.0d, 0.1d), null, g.nextInt(1, 2))
    }

    // Optional satisfied rooms: past setpoint by > hysteresis -> 0 % pre-floor,
    // and (being at/past setpoint) kept at 0 % by cross-coupling.
    int nSat = g.nextInt(0, 2)
    (0..<nSat).each { int k ->
      double pastF = g.nextDouble(0.5d, 3.0d)
      double tS = heating ? setpointC + HYST + pastF : setpointC - HYST - pastF
      rooms << room("sat${k}".toString(), true, tS, setpointC, heating,
          g.nextDouble(0.05d, 0.5d), g.nextDouble(0.0d, 0.2d), null, g.nextInt(1, 2))
    }

    // Optional inactive rooms (must never be flagged or repositioned).
    int nInact = g.nextInt(0, 2)
    (0..<nInact).each { int k ->
      double tI = setpointC + g.nextDouble(-6.0d, 6.0d)
      rooms << room("inact${k}".toString(), false, tI, setpointC, heating,
          g.nextDouble(0.05d, 0.5d), g.nextDouble(0.0d, 0.2d), null, g.nextInt(1, 2))
    }

    return [rooms: rooms, canaryId: 'canary', laggardId: 'laggard']
  }

  private static Map room(String roomId, boolean active, double tempC,
      double setpointC, boolean heating, double efficiency, double leak,
      Object curve, int nVents) {
    Map r = [:]
    r.roomId = roomId
    r.active = active
    r.tempC = tempC
    r.efficiency = efficiency
    r.leak = leak
    r.currentOpen = 0.0d
    r.ventIds = (0..<Math.max(1, nVents)).collect { "${roomId}#v${it}".toString() }
    r.signedErrorC = heating ? (setpointC - tempC) : (tempC - setpointC)
    r.curve = curve
    return r
  }

  /**
   * Build duct signals for the requested condition. {@code confirm} indicates
   * conditioned air really is flowing (supply in the conditioning direction +
   * positive pressure -> no veto); {@code veto} indicates it is not (supply on
   * the wrong side of the setpoint OR non-positive pressure -> veto);
   * {@code none} -> null (graceful degradation).
   */
  private static Map makeDuct(PropertyGen g, String choice, boolean heating, double setpointC) {
    if (choice == 'none') {
      return null
    }
    Map d = [:]
    if (choice == 'confirm') {
      d.ductTempC = heating ? setpointC + g.nextDouble(5.0d, 15.0d)
                            : setpointC - g.nextDouble(5.0d, 15.0d)
      d.ductPressure = g.nextDouble(1.0d, 3.0d)
      return d
    }
    // veto: express through temperature OR pressure (randomly).
    if (g.nextBoolean()) {
      d.ductTempC = heating ? setpointC - g.nextDouble(1.0d, 10.0d)
                            : setpointC + g.nextDouble(1.0d, 10.0d)
      d.ductPressure = g.nextDouble(1.0d, 3.0d)
    } else {
      d.ductTempC = heating ? setpointC + g.nextDouble(5.0d, 15.0d)
                            : setpointC - g.nextDouble(5.0d, 15.0d)
      d.ductPressure = g.nextDouble(-1.0d, 0.0d)
    }
    return d
  }

  // ---------------------------------------------------------------------------
  // Independent oracle helpers (mirror the spec, derived from public primitives).
  // ---------------------------------------------------------------------------

  private static List activeRooms(List rooms) {
    return rooms.findAll { it.active }
  }

  /** Off-target distance to the SETPOINT in the conditioning direction (= signedErrorC). */
  private static double offTarget(Map r, double setpointC, boolean heating) {
    return heating ? (setpointC - r.tempC) : (r.tempC - setpointC)
  }

  private int kneeOf(Map r) {
    return r.curve != null ? lib.lrnVentCurveKnee(r.curve) : 100
  }

  /**
   * Mirror of the Allocator's duct veto: conditioned air is NOT flowing when the
   * available signals say so (non-positive pressure, or supply temperature on the
   * wrong side of the setpoint for the conditioning direction). Null -> no veto.
   */
  private static boolean ductVetoes(Map duct, boolean heating, double setpointC) {
    if (duct == null) {
      return false
    }
    if (duct.ductPressure != null && duct.ductPressure.doubleValue() <= 0.0d) {
      return true
    }
    if (duct.ductTempC != null) {
      double t = duct.ductTempC.doubleValue()
      return heating ? (t <= setpointC) : (t >= setpointC)
    }
    return false
  }

  /** Expand active-room targets to per-vent and compute combined open % via the floor module. */
  private double combinedForActive(Map<String, Double> floored,
      List rooms, Map s) {
    Map<String, Map> byId = [:]
    rooms.each { byId[it.roomId] = it }
    Map<String, Double> perVent = [:]
    floored.each { String id, Double pct ->
      Map r = byId[id]
      if (r == null) {
        return
      }
      double p = pct == null ? 0.0d : pct.doubleValue()
      if (!r.active && p <= 0.0d) {
        return
      }
      if (r.ventIds != null && !r.ventIds.isEmpty()) {
        r.ventIds.each { perVent[it] = p }
      } else {
        perVent[id] = p
      }
    }
    return lib.sfCombinedOpenPct(perVent, s)
  }
}
