
import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based test for the PURE Allocator monotonicity-in-need invariant
 * (task 6.3): {@code Dabv2Allocator.allocate(rooms, setpointC, mode, settings)}.
 *
 * Implements Correctness Property 4 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 4: Allocation is monotonic in need
 *   "For any two active rooms that are equal in all respects except that one
 *    has a larger error-to-setpoint or a lower effectiveness, that room
 *    receives an aperture greater than or equal to the other's."
 *   Validates: Requirements 5.3, 8.3.
 *
 * <b>Why controlled pairs.</b> Across an arbitrary active-room set the commanded
 * aperture is the product of several interacting mechanisms — the bottleneck
 * horizon {@code tau*}, each room's KNEE clamp, the closed-vent leak floor, and
 * the quantize-DOWN to {@code granularity}. Comparing two rooms that differ on
 * MORE than one of {error, effectiveness, curve, leak, knee} confounds the
 * monotonicity claim (e.g. a room with a larger error but a much higher knee can
 * legitimately receive a smaller aperture). Requirement 5.3 / 8.3 scope the
 * claim to two rooms "equal in all respects except" the one varying dimension,
 * so this test embeds a CONTROLLED PAIR — two rooms sharing the same learned
 * curve, leak, vent count and (depending on the case) the same error or the same
 * effectiveness — inside a randomized set of filler rooms. Holding every confound
 * fixed makes the ordering meaningful: with the curve/leak/knee identical, the
 * throttle is {@code aperture = quantizeDown(inverse(requiredFlow), step)} with
 * {@code requiredFlow = (err / tau*) / effectiveness}, and {@code inverse} and
 * {@code quantizeDown} are both monotone non-decreasing, so a strictly larger
 * {@code requiredFlow} (from a larger error and/or a lower effectiveness) maps to
 * a greater-or-equal aperture. The leak-floor (0 %) and knee-clamp branch
 * boundaries are themselves monotone in {@code requiredFlow}, so the ordering is
 * preserved end-to-end — including when the needier room is the bottleneck (it is
 * then commanded at its knee, which is >= the other room's clamped aperture).
 *
 * Three controlled cases are exercised across the iteration stream:
 *   - {@code 'error'}: identical effectiveness + curve; the needy room has a
 *     strictly larger error-to-setpoint.
 *   - {@code 'effectiveness'}: identical error + curve; the needy room has a
 *     strictly LOWER effectiveness (room efficiency).
 *   - {@code 'combined'}: the needy room has BOTH a larger error AND a lower
 *     effectiveness.
 * In every case the needy room's commanded aperture must be >= the other's.
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS (100)
 * reproducible randomized scenarios, each containing the controlled pair
 * (guaranteed active + unsatisfied) plus 0-3 random filler active rooms that may
 * own the bottleneck horizon.
 */
class AllocatorMonotonicityPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-6d
  private static final double HYST = 0.3d

  def 'Feature: hubitat-flair-vents-dab-v2, Property 4: Allocation is monotonic in need'() {
    given: 'a randomized scenario embedding a controlled needy/easy room pair'
    def g = PropertyGen.forIteration(i)
    Map scenario = makeScenario(g, i)
    List rooms = scenario.rooms as List
    double setpointC = scenario.setpointC as double
    String mode = scenario.mode as String
    Map s = scenario.settings as Map
    String needyId = scenario.needyId as String
    String easyId = scenario.easyId as String

    expect: 'both controlled-pair rooms are active and unsatisfied (the precondition)'
    rooms.find { it.roomId == needyId }.active
    rooms.find { it.roomId == easyId }.active
    !satisfied(rooms.find { it.roomId == needyId }, setpointC, mode, s.hysteresisC)
    !satisfied(rooms.find { it.roomId == easyId }, setpointC, mode, s.hysteresisC)

    when: 'the Allocator allocates (PRE-floor)'
    Map res = lib.allocAllocate(rooms, setpointC, mode, s)
    Map<String, Double> targets = res.targets

    then: 'the needier room (larger error and/or lower effectiveness) gets aperture >= the other'
    double needyAperture = targets[needyId] as double
    double easyAperture = targets[easyId] as double
    needyAperture >= easyAperture - EPS

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Scenario generation. Builds 0-3 random filler active rooms (any satisfaction)
  // plus a CONTROLLED PAIR that is equal in all respects except the one varying
  // dimension for the case selected by the iteration index.
  // ---------------------------------------------------------------------------

  private Map makeScenario(PropertyGen g, int i) {
    String mode = g.nextBoolean() ? 'cooling' : 'heating'
    double setpointC = g.nextDouble(18.0d, 24.0d)
    boolean heating = mode == 'heating'

    List rooms = []

    // Filler rooms — random active/inactive, satisfied/unsatisfied. They may own
    // the bottleneck horizon tau* but never break the pair ordering (the needy
    // pair room always has the larger tau of the two, so the easy room can never
    // become the global bottleneck while the needy room is throttled below knee).
    int nFiller = g.nextInt(0, 3)
    (0..<nFiller).each { int k ->
      boolean active = g.nextInt(0, 9) < 7
      boolean roomSatisfied = g.nextBoolean()
      rooms << makeFiller(g, "f${k}", active, roomSatisfied, setpointC, heating)
    }

    // The controlled pair — shared curve, leak and vent count. The needy room is
    // strictly needier on the dimension under test; both are active + unsatisfied.
    int caseSel = i % 3
    String caseName = caseSel == 0 ? 'error' : (caseSel == 1 ? 'effectiveness' : 'combined')

    double sharedLeak = g.nextDouble(0.0d, 0.2d)
    boolean useCurve = g.nextBoolean()
    Map sharedCurve = useCurve ? lib.lrnSeedLinear(sharedLeak) : null
    double baseEff = g.nextDouble(0.05d, 0.5d)

    // Error magnitudes (distance INTO the unsatisfied region, beyond hysteresis).
    double easyErr = g.nextDouble(0.2d, 2.0d)
    double extraErr = g.nextDouble(0.3d, 3.0d)   // strictly positive bump for the needy room

    double needyErr
    double easyErrUsed
    double needyEff
    double easyEff
    switch (caseName) {
      case 'error':
        // Equal effectiveness + curve; needy has the larger error.
        needyErr = easyErr + extraErr
        easyErrUsed = easyErr
        needyEff = baseEff
        easyEff = baseEff
        break
      case 'effectiveness':
        // Equal error + curve; needy has the strictly LOWER effectiveness.
        needyErr = easyErr
        easyErrUsed = easyErr
        easyEff = baseEff
        needyEff = baseEff * g.nextDouble(0.3d, 0.9d)   // strictly lower
        break
      default: // 'combined'
        // Needy has BOTH a larger error AND a lower effectiveness.
        needyErr = easyErr + extraErr
        easyErrUsed = easyErr
        easyEff = baseEff
        needyEff = baseEff * g.nextDouble(0.3d, 0.9d)
        break
    }

    rooms << makePairRoom(g, 'needy', needyErr, needyEff, sharedLeak, sharedCurve,
        setpointC, heating)
    rooms << makePairRoom(g, 'easy', easyErrUsed, easyEff, sharedLeak, sharedCurve,
        setpointC, heating)

    Map s = lib.dabv2NewAllocSettings()
    s.hysteresisC = HYST
    s.granularity = g.nextInt(1, 10)
    s.safetyFloorPct = g.nextDouble(20.0d, 90.0d)
    s.conventionalVents = g.nextInt(0, 3)
    s.conventionalOpenPct = g.nextDouble(0.0d, 100.0d)

    return [rooms: rooms, setpointC: setpointC, mode: mode, settings: s,
            needyId: 'needy', easyId: 'easy', caseName: caseName]
  }

  /**
   * Build a controlled-pair room whose temperature sits exactly {@code errBeyond}
   * deg C INTO the unsatisfied region (past the hysteresis target), so its
   * convergence error == {@code errBeyond}. Shares the supplied leak + curve so
   * the only differences across the pair are the varied dimension(s).
   */
  private Map makePairRoom(PropertyGen g, String roomId, double errBeyond,
      double efficiency, double leak, Map curve,
      double setpointC, boolean heating) {
    double tempC
    if (heating) {
      // unsatisfied: temp < setpoint + hyst; error = (setpoint + hyst) - temp
      tempC = (setpointC + HYST) - errBeyond
    } else {
      // unsatisfied: temp > setpoint - hyst; error = temp - (setpoint - hyst)
      tempC = (setpointC - HYST) + errBeyond
    }

    Map r = [:]
    r.roomId = roomId
    r.active = true
    r.tempC = tempC
    r.efficiency = efficiency
    r.leak = leak
    r.currentOpen = g.nextDouble(0.0d, 100.0d)
    r.ventIds = ['v0', 'v1'].collect { "${roomId}#${it}".toString() }
    r.signedErrorC = heating ? (setpointC - tempC) : (tempC - setpointC)
    r.curve = curve
    return r
  }

  private Map makeFiller(PropertyGen g, String roomId, boolean active,
      boolean roomSatisfied, double setpointC, boolean heating) {
    double tempC
    if (heating) {
      tempC = roomSatisfied ? setpointC + HYST + g.nextDouble(0.0d, 4.0d)
                            : setpointC + HYST - g.nextDouble(0.1d, 5.0d)
    } else {
      tempC = roomSatisfied ? setpointC - HYST - g.nextDouble(0.0d, 4.0d)
                            : setpointC - HYST + g.nextDouble(0.1d, 5.0d)
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
    r.curve = g.nextBoolean() ? lib.lrnSeedLinear(leak) : null
    return r
  }

  // ---------------------------------------------------------------------------
  // Independent oracle: classification only (the ordering assertion needs no
  // re-derivation of the throttle — it checks the allocator's emitted apertures
  // directly).
  // ---------------------------------------------------------------------------

  private static boolean satisfied(Map r, double setpointC, String mode, double hyst) {
    boolean heating = (mode != null && mode.toLowerCase().contains('heat'))
    return heating ? (r.tempC >= setpointC + hyst) : (r.tempC <= setpointC - hyst)
  }
}
