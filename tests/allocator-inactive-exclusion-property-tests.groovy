
import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based test for the PURE Allocator inactive-room-exclusion invariant
 * (task 6.5): {@code Dabv2Allocator.allocate(...)} and
 * {@code Dabv2Allocator.predictedSpread(...)}.
 *
 * Implements Correctness Property 8 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 8: Inactive rooms excluded
 *   "Adding, removing, or repositioning inactive rooms in the input changes
 *    neither the active rooms' commanded targets NOR the predicted spread, and
 *    the Allocator never emits a target for an inactive room (it never
 *    auto-closes or repositions inactive rooms)."
 *   Validates: Requirements 7.5, 16.1, 16.2.
 *
 * <b>What the property pins down.</b> The {@code balance} objective and the
 * competitive allocation are scoped STRICTLY to active rooms (R7.5, R16.2), and
 * inactive rooms are never auto-closed or repositioned by balancing (R16.1).
 * Concretely, for one fixed set of active rooms, three input lists that differ
 * ONLY in their inactive rooms — (i) active rooms alone, (ii) active rooms
 * interleaved with one arbitrary inactive set, (iii) active rooms interleaved
 * with a DIFFERENT arbitrary inactive set (different count, different
 * temperatures, different positions) — must all yield:
 *   - byte-for-byte identical active-room {@code targets} (same keys, same
 *     insertion order, same values),
 *   - bit-identical {@code predictedSpreadC},
 *   - NO inactive {@code roomId} anywhere in {@code targets}.
 * Interleaving (rather than appending) exercises "repositioning": the active
 * rooms keep their relative order while inactive rooms are scattered through
 * arbitrary positions around them.
 *
 * <b>Why the property is non-vacuous (the in-test sensitivity oracle).</b> Each
 * generated inactive set contains at least one "influential" room whose
 * temperature sits well OUTSIDE the active rooms' band (far above the setpoint
 * when cooling, far below when heating) and is unsatisfied — so if the Allocator
 * leaked inactive influence, that room would (a) extend the projected
 * {@code [min,max]} band and change {@code predictedSpreadC}, and (b) earn a
 * non-zero target. The test PROVES this by re-running {@code predictedSpread}
 * with those same influential rooms flipped to active and asserting the spread
 * STRICTLY changes. That makes the equality assertions meaningful: a regression
 * that stopped filtering {@code !active} would be caught (verified out-of-band
 * by temporarily removing the filter — the property goes red).
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS (100)
 * reproducible randomized scenarios.
 */
class AllocatorInactiveExclusionPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-9d
  /** An influential inactive room sits at least this far outside the active band. */
  private static final double INFLUENTIAL_OFFSET_C = 0.5d

  def 'Feature: hubitat-flair-vents-dab-v2, Property 8: Inactive rooms excluded'() {
    given: 'a fixed active-room set and two arbitrary, different inactive sets'
    def g = PropertyGen.forIteration(i)
    double setpointC = g.nextDouble(18.0d, 24.0d)
    String mode = g.nextBoolean() ? 'cooling' : 'heating'
    boolean heating = mode == 'heating'
    Map s = makeSettings(g)
    double horizon = s.horizonMin

    List active = makeActiveRooms(g, setpointC, heating)
    List inactiveA = makeInactiveRooms(g, 'a', setpointC, heating, g.nextInt(0, 4))
    List inactiveB = makeInactiveRooms(g, 'b', setpointC, heating, g.nextInt(1, 5))

    List listActiveOnly = new ArrayList(active)
    List listA = interleave(g, active, inactiveA)
    List listB = interleave(g, active, inactiveB)

    expect: 'there are at least two active rooms, so the spread is well-defined'
    active.size() >= 2

    when: 'the Allocator runs over each list (differing only in inactive rooms)'
    def resActiveOnly = lib.allocAllocate(listActiveOnly, setpointC, mode, s)
    def resA = lib.allocAllocate(listA, setpointC, mode, s)
    def resB = lib.allocAllocate(listB, setpointC, mode, s)

    then: 'active targets are byte-for-byte identical regardless of inactive rooms'
    resA.targets == resActiveOnly.targets
    resB.targets == resActiveOnly.targets
    new ArrayList<String>(resA.targets.keySet()) == new ArrayList<String>(resActiveOnly.targets.keySet())
    new ArrayList<String>(resB.targets.keySet()) == new ArrayList<String>(resActiveOnly.targets.keySet())

    and: 'the predicted spread is bit-identical regardless of inactive rooms'
    resA.predictedSpreadC == resActiveOnly.predictedSpreadC
    resB.predictedSpreadC == resActiveOnly.predictedSpreadC

    and: 'every active room (and only active rooms) appears in targets'
    resActiveOnly.targets.keySet() == active.collect { it.roomId } as Set
    (inactiveA*.roomId + inactiveB*.roomId).every { !resA.targets.containsKey(it) && !resB.targets.containsKey(it) }

    and: 'the Allocator never emits a target for an inactive room (no auto-close/reposition)'
    !resA.targets.keySet().any { id -> inactiveA.any { it.roomId == id } }
    !resB.targets.keySet().any { id -> inactiveB.any { it.roomId == id } }

    and: 'sensitivity oracle: the influential inactive rooms WOULD change the spread if active'
    double activeSpread = resActiveOnly.predictedSpreadC
    double leakySpread = lib.allocPredictedSpread(
        active + inactiveB.collect { asActiveCopy(it) }, resActiveOnly.targets, mode, setpointC, horizon)
    leakySpread > activeSpread + EPS

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Scenario generation.
  // ---------------------------------------------------------------------------

  private Map makeSettings(PropertyGen g) {
    Map s = lib.dabv2NewAllocSettings()
    s.hysteresisC = g.nextDouble(0.0d, 0.5d)
    s.granularity = g.pick([1, 5, 10]) as int
    s.horizonMin = g.nextDouble(5.0d, 60.0d)
    return s
  }

  /** 2-5 active rooms, mixed satisfied/unsatisfied, temps within the active band. */
  private List makeActiveRooms(PropertyGen g, double setpointC, boolean heating) {
    int n = g.nextInt(2, 5)
    List rooms = []
    (0..<n).each { int k ->
      boolean roomSatisfied = g.nextBoolean()
      double tempC
      if (heating) {
        tempC = roomSatisfied ? setpointC + g.nextDouble(0.0d, 4.0d)
                              : setpointC - g.nextDouble(0.5d, 6.0d)
      } else {
        tempC = roomSatisfied ? setpointC - g.nextDouble(0.0d, 4.0d)
                              : setpointC + g.nextDouble(0.5d, 6.0d)
      }
      rooms << makeRoom(g, "act${k}".toString(), true, tempC, setpointC, heating)
    }
    return rooms
  }

  /**
   * {@code count} inactive rooms; the FIRST is always "influential" — its
   * temperature sits well outside the active band (and is unsatisfied) so that,
   * were inactive influence leaked, both the spread and the targets would change.
   */
  private List makeInactiveRooms(PropertyGen g, String tag,
      double setpointC, boolean heating, int count) {
    List rooms = []
    // Guaranteed influential extreme (far outside the active band, unsatisfied).
    double extreme = heating ? setpointC - g.nextDouble(10.0d, 15.0d)
                             : setpointC + g.nextDouble(10.0d, 15.0d)
    rooms << makeRoom(g, "in_${tag}_x".toString(), false, extreme, setpointC, heating, true)
    (0..<count).each { int k ->
      double tempC = setpointC + g.nextDouble(-8.0d, 8.0d)
      rooms << makeRoom(g, "in_${tag}_${k}".toString(), false, tempC, setpointC, heating)
    }
    return rooms
  }

  private Map makeRoom(PropertyGen g, String roomId, boolean active,
      double tempC, double setpointC, boolean heating, boolean lowLeak = false) {
    Map r = [:]
    r.roomId = roomId
    r.active = active
    r.tempC = tempC
    r.efficiency = g.nextDouble(0.02d, 0.5d)
    double leak = lowLeak ? g.nextDouble(0.0d, 0.05d) : g.nextDouble(0.0d, 0.2d)
    r.leak = leak
    r.currentOpen = g.nextDouble(0.0d, 100.0d)
    int nVents = g.nextInt(1, 3)
    r.ventIds = (0..<nVents).collect { "${roomId}#v${it}".toString() }
    r.signedErrorC = heating ? (setpointC - tempC) : (tempC - setpointC)
    r.curve = g.nextBoolean() ? lib.lrnSeedLinear(leak) : null
    return r
  }

  /** A shallow copy of an inactive room flipped to active (sensitivity oracle only). */
  private static Map asActiveCopy(Map src) {
    Map r = [:]
    r.roomId = src.roomId
    r.active = true
    r.tempC = src.tempC
    r.efficiency = src.efficiency
    r.leak = src.leak
    r.currentOpen = src.currentOpen
    r.ventIds = src.ventIds
    r.signedErrorC = src.signedErrorC
    r.curve = src.curve
    return r
  }

  /**
   * Interleave {@code active} and {@code inactive} into one list, preserving each
   * sublist's relative order but scattering inactive rooms through arbitrary
   * positions (exercises "repositioning" of inactive rooms).
   */
  private static List interleave(PropertyGen g,
      List active, List inactive) {
    List out = []
    int ai = 0
    int ii = 0
    while (ai < active.size() || ii < inactive.size()) {
      boolean takeActive
      if (ai >= active.size()) {
        takeActive = false
      } else if (ii >= inactive.size()) {
        takeActive = true
      } else {
        takeActive = g.nextBoolean()
      }
      if (takeActive) {
        out << active[ai++]
      } else {
        out << inactive[ii++]
      }
    }
    return out
  }
}
