

import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based test for the PURE Allocator determinism invariant (task 6.7):
 * {@code Dabv2Allocator.allocate(rooms, setpointC, mode, settings, duct)} and
 * {@code Dabv2Allocator.predictedSpread(...)}.
 *
 * Implements Correctness Property 6 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 6: Determinism
 *   "For any inputs, calling allocate(...) twice yields identical outputs — the
 *    pure modules use no time, randomness, or hidden global state."
 *   Validates: Requirements 5.8, 5.7.
 *
 * <b>Why this is a genuine sensitivity check (not a tautology).</b> A test that
 * merely called {@code allocate} twice on the SAME object reference and compared
 * results would still pass for many subtly non-deterministic implementations —
 * e.g. one that memoized into a static cache keyed by object identity, or one
 * that read a hidden mutable static accumulator on the FIRST call only, or one
 * whose output iteration order depended on {@code HashMap} bucket layout shared
 * across calls. To rule those out this property asserts identity across THREE
 * independent dimensions on every iteration:
 *
 *   (1) <b>Repeated calls, same instance.</b> {@code allocate(A)} called twice
 *       must be byte-for-byte identical — catches hidden mutable static state
 *       that accumulates or flips between calls, and catches any in-place
 *       mutation of the shared input that would change the second call.
 *   (2) <b>Two equal-but-DISTINCT input graphs.</b> Scenario {@code A} and
 *       scenario {@code B} are built from two SEPARATE generators seeded with
 *       the SAME iteration index, yielding structurally-equal but
 *       reference-distinct {@code RoomAllocInput}/{@code AllocSettings}/
 *       {@code DuctSignals} object graphs. {@code allocate(A) == allocate(B)}
 *       proves the output depends ONLY on input VALUES, never on object
 *       identity, allocation address, or insertion-order-of-distinct-objects.
 *   (3) <b>Input is not mutated.</b> A deep snapshot of the input graph taken
 *       before allocation must equal the graph after — so "calling twice"
 *       genuinely means "twice on identical inputs" (the Allocator cannot rely
 *       on having mutated its own inputs on the first pass).
 *
 * Equality is asserted on EVERY field of {@code AllocResult} (targets,
 * predictedFinishMin, predictedSpreadC, airflowLimited, floorBinding) using
 * EXACT equality (no tolerance): a deterministic pure function must reproduce
 * identical IEEE-754 bits, identical map iteration order (both are
 * {@code LinkedHashMap}/{@code LinkedHashSet}), and the identical boolean.
 * {@code predictedSpread} is asserted deterministic on its own as well.
 *
 * To guarantee the equality assertions are never vacuous, each scenario has
 * >= 1 active room and the test asserts the produced {@code targets} map is
 * non-empty — so real allocation work is compared on every iteration, across
 * both modes, learned-curve and scalar-leak rooms, multi-vent groups, mixed
 * satisfied/unsatisfied/inactive rooms, cross-coupling on/off, and present/
 * absent duct signals.
 *
 * The class name contains "Property" so {@code ./gradlew test --tests
 * '*Property*'} selects it; the {@code where:} block drives >=
 * PropertyGen.ITERATIONS (100) reproducible randomized scenarios.
 */
class AllocatorDeterminismPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  def 'Feature: hubitat-flair-vents-dab-v2, Property 6: Determinism'() {
    given: 'two equal-but-distinct input graphs built from identically-seeded generators'
    Map a = makeScenario(PropertyGen.forIteration(i))
    Map b = makeScenario(PropertyGen.forIteration(i))
    List roomsA = a.rooms as List
    List roomsB = b.rooms as List
    double setpointA = a.setpointC as double
    double setpointB = b.setpointC as double
    String modeA = a.mode as String
    String modeB = b.mode as String
    Map sA = a.settings as Map
    Map sB = b.settings as Map
    Map ductA = a.duct as Map
    Map ductB = b.duct as Map

    and: 'the two graphs really are equal-by-value yet distinct references'
    snapshotRooms(roomsA) == snapshotRooms(roomsB)
    !roomsA.is(roomsB)
    (roomsA.isEmpty() || roomsB.isEmpty() || !roomsA[0].is(roomsB[0]))

    and: 'a deep snapshot of input graph A taken BEFORE allocation (mutation guard)'
    def inputSnapshotBefore = snapshotRooms(roomsA)

    when: 'allocate is called twice on instance A, and once on the distinct-equal instance B'
    Map a1 = lib.allocAllocate(roomsA, setpointA, modeA, sA, ductA)
    Map a2 = lib.allocAllocate(roomsA, setpointA, modeA, sA, ductA)
    Map b1 = lib.allocAllocate(roomsB, setpointB, modeB, sB, ductB)

    then: 'the scenario did real work (>= 1 active room => non-empty targets; equality is never vacuous)'
    roomsA.any { it != null && it.active }
    !a1.targets.isEmpty()

    and: 'repeated calls on the same instance are byte-for-byte identical (no hidden state)'
    resultsEqual(a1, a2)

    and: 'the equal-but-distinct input graph yields identical output (no identity dependence)'
    resultsEqual(a1, b1)

    and: 'allocate did not mutate its inputs (so "twice on identical inputs" holds)'
    snapshotRooms(roomsA) == inputSnapshotBefore

    and: 'predictedSpread is itself deterministic across repeated and distinct-equal calls'
    double sp1 = lib.allocPredictedSpread(roomsA, a1.targets, modeA, setpointA, sA.horizonMin)
    double sp2 = lib.allocPredictedSpread(roomsA, a1.targets, modeA, setpointA, sA.horizonMin)
    double sp3 = lib.allocPredictedSpread(roomsB, b1.targets, modeB, setpointB, sB.horizonMin)
    Double.compare(sp1, sp2) == 0
    Double.compare(sp1, sp3) == 0
    Double.compare(sp1, a1.predictedSpreadC) == 0

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Full-field exact equality of two AllocResults. Maps/Sets compare by value
  // AND iteration order (LinkedHashMap/LinkedHashSet); doubles compare by exact
  // IEEE-754 bits via Double.compare (so e.g. -0.0 vs 0.0 or any 1-ulp drift
  // would fail). A deterministic pure function reproduces all of these exactly.
  // ---------------------------------------------------------------------------

  private static boolean resultsEqual(Map x, Map y) {
    assert mapsExactlyEqual(x.targets, y.targets)
    assert mapsExactlyEqual(x.predictedFinishMin, y.predictedFinishMin)
    assert Double.compare(x.predictedSpreadC, y.predictedSpreadC) == 0
    assert x.airflowLimited == y.airflowLimited
    assert new ArrayList<String>(x.airflowLimited) == new ArrayList<String>(y.airflowLimited)
    assert x.floorBinding == y.floorBinding
    return true
  }

  /** Same keys in the same iteration order, each value equal to exact bits. */
  private static boolean mapsExactlyEqual(Map<String, Double> x, Map<String, Double> y) {
    if (x.size() != y.size()) { return false }
    List<String> kx = new ArrayList<String>(x.keySet())
    List<String> ky = new ArrayList<String>(y.keySet())
    if (kx != ky) { return false }                 // key set AND insertion order
    for (String k : kx) {
      Double vx = x.get(k)
      Double vy = y.get(k)
      if (vx == null || vy == null) {
        if (vx != vy) { return false }
      } else if (Double.compare(vx.doubleValue(), vy.doubleValue()) != 0) {
        return false
      }
    }
    return true
  }

  /** Value snapshot of the input room graph for the mutation / equality guards. */
  private List<Map> snapshotRooms(List rooms) {
    if (rooms == null) { return [] }
    return rooms.collect { r ->
      r == null ? null : [
        roomId      : r.roomId,
        tempC       : r.tempC,
        active      : r.active,
        efficiency  : r.efficiency,
        leak        : r.leak,
        currentOpen : r.currentOpen,
        ventIds     : r.ventIds == null ? null : new ArrayList<String>(r.ventIds),
        signedErrorC: r.signedErrorC,
        hasCurve    : r.curve != null,
        knee        : r.curve == null ? -1 : lib.lrnVentCurveKnee(r.curve),
        leakFlow    : r.curve == null ? -1.0d : lib.lrnVentCurveFlow(r.curve, 0.0d)
      ]
    }
  }

  // ---------------------------------------------------------------------------
  // Scenario generation — full input space. Called TWICE per iteration with two
  // separate generators seeded with the same index, so the two returned graphs
  // are equal-by-value but reference-distinct. >= 1 active room is guaranteed so
  // the targets map is non-empty and the equality assertions do real work.
  // ---------------------------------------------------------------------------

  private Map makeScenario(PropertyGen g) {
    String mode = g.nextBoolean() ? 'cooling' : 'heating'
    double setpointC = g.nextDouble(18.0d, 24.0d)
    boolean heating = mode == 'heating'

    Map s = lib.dabv2NewAllocSettings([
      hysteresisC            : g.nextDouble(0.0d, 0.6d),
      granularity            : g.nextInt(1, 10),
      safetyFloorPct         : g.nextDouble(20.0d, 90.0d),
      conventionalVents      : g.nextInt(0, 3),
      conventionalOpenPct    : g.nextDouble(0.0d, 100.0d),
      crosscoupling          : g.nextBoolean(),
      airflowLimitedMarginPct: g.nextDouble(1.0d, 10.0d),
      airflowLimitedErrorC   : g.nextDouble(0.1d, 1.5d),
      horizonMin             : g.nextDouble(5.0d, 60.0d)
    ])

    int nRooms = g.nextInt(1, 6)
    List rooms = []
    (0..<nRooms).each { int k ->
      // Room 0 is forced active so >= 1 active room exists (non-vacuous targets).
      boolean forced = (k == 0)
      boolean active = forced ? true : (g.nextInt(0, 9) < 7)
      boolean roomSatisfied = g.nextBoolean()
      rooms << makeRoom(g, "r${k}", active, roomSatisfied, setpointC, heating, s.hysteresisC)
    }

    Map duct = makeDuct(g, setpointC)
    return [rooms: rooms, setpointC: setpointC, mode: mode, settings: s, duct: duct]
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

  /** Optional duct signals: ~1/3 null, otherwise random present/absent fields. */
  private Map makeDuct(PropertyGen g, double setpointC) {
    if (g.nextInt(0, 2) == 0) { return null }
    Map d = [:]
    d.ductTempC = g.nextBoolean() ? (Double) g.nextDouble(setpointC - 10.0d, setpointC + 10.0d) : null
    d.ductPressure = g.nextBoolean() ? (Double) g.nextDouble(-1.0d, 3.0d) : null
    return d
  }
}
