
import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based test for the PURE Safety_Floor need-biased padding + the
 * last-resort inactive reopen (task 5.3):
 * {@code Dabv2SafetyFloor.apply(targets, rooms, settings, logger)}.
 *
 * Implements Correctness Property 9 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 9: Floor padding biases to need
 *   "For any allocation in which the floor binds, the apertures raised are the
 *    active not-yet-satisfied rooms with the largest signed error first
 *    (deterministic tie-break), and satisfied active rooms and inactive rooms
 *    are not reopened unless no active not-yet-satisfied capacity remains."
 *
 * Validates: Requirements 6.4, 6.10, 8.7.
 *
 * <b>Reference-faithful contract (task 8.3 / decision D9).</b> The pass mirrors
 * the validated Python Reference {@code balance.apply_safety_floor} exactly:
 *
 *   Phase 1 — pad active need. Raise the active, NOT-YET-SATISFIED room
 *     (signedErrorC &gt; 0) with the largest signed error first, ties broken by
 *     the LARGER roomId (matching the Reference's {@code max((error, rid))}),
 *     one granularity step at a time, bounded &lt;= 100. A SATISFIED active room
 *     (signedErrorC &lt;= 0) is NEVER reopened by the floor.
 *   Phase 2 — last resort (R6.10). ONLY when no active not-yet-satisfied
 *     capacity remains AND the TRUE TOTAL-AIRFLOW view (every device, including
 *     the configured count of currently-closed inactive dampers) is below the
 *     floor, reopen inactive vents — taken from the rooms list IN LIST ORDER,
 *     each to 100 % before the next — ADD them to the returned targets, and LOG
 *     the reason via the injectable {@link Dabv2SafetyFloor.FloorLogger} hook
 *     (the module stays pure; the app supplies the logger).
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS (100)
 * reproducible randomized scenarios across three deliberately-shaped families:
 * 'normal' (floor met by active not-yet-satisfied need), 'satisfied-mix'
 * (satisfied active rooms are NEVER raised; inactive reopen covers the rest),
 * and 'last-resort' (active + conventional exhausted, forcing the inactive
 * reopen + log).
 */
class SafetyFloorNeedBiasPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-6d

  def 'Feature: hubitat-flair-vents-dab-v2, Property 9: Floor padding biases to need'() {
    given: 'a randomized scenario (active + inactive rooms / targets / settings)'
    def g = PropertyGen.forIteration(i)
    Map scenario = makeScenario(g, i)
    List rooms = scenario.rooms as List
    Map<String, Double> targets = scenario.targets as Map<String, Double>
    Map s = scenario.settings as Map

    when: 'the Safety_Floor is enforced with an injectable logging hook'
    List<String> reasons = []
    def logger = { String r -> reasons << r }
    def res = lib.sfApply(targets, rooms, s, logger)
    Map<String, Double> result = (Map<String, Double>) res[0]
    boolean floorBinding = (boolean) res[1]

    then: 'the proposed targets are preserved; any ADDED keys are inactive rooms'
    result.keySet().containsAll(targets.keySet())
    (result.keySet() - targets.keySet()).every { String rid ->
      rooms.find { it.roomId == rid && !it.active } != null
    }

    and: 'the floor only ever RAISES apertures: new[r] >= old[r] for every proposed room'
    targets.every { String rid, Double oldPct -> result[rid] >= oldPct - EPS }

    and: 'every commanded aperture stays within [0, 100]'
    result.values().every { it >= -EPS && it <= 100.0d + EPS }

    and: 'PHASE 1 need-bias: among active not-yet-satisfied rooms, a later room '
    'in (error desc, roomId desc) order is raised only when every earlier room is at 100'
    List unsat = rooms.findAll {
      it.active && it.signedErrorC > 0.0d && targets.containsKey(it.roomId)
    }.sort { a, b -> (b.signedErrorC <=> a.signedErrorC) ?: (b.roomId <=> a.roomId) }
    biasPrefixHolds(unsat, targets, result)

    and: 'a SATISFIED active room is NEVER reopened by the floor'
    rooms.findAll { it.active && it.signedErrorC <= 0.0d && targets.containsKey(it.roomId) }.every {
      result[it.roomId] <= targets[it.roomId] + EPS
    }

    and: 'inactive rooms are reopened ONLY as a last resort: every active not-yet-satisfied room is at 100 first'
    List inactiveList = rooms.findAll { !it.active }
    boolean inactiveReopened = inactiveList.any { reopenedPct(result, it) > EPS }
    !inactiveReopened || unsat.every { result[it.roomId] >= 100.0d - EPS }

    and: 'the inactive reopen proceeds IN LIST ORDER: each is filled to 100 % before the next is touched'
    reopenIsListOrderedPrefix(inactiveList, result)

    and: 'a last-resort inactive reopen ALWAYS logs the reason (R6.10)'
    !inactiveReopened || reasons.size() >= 1

    and: 'conversely, a reason is logged ONLY when an inactive reopen actually happened'
    reasons.isEmpty() || inactiveReopened

    and: 'floorBinding is true iff at least one aperture was raised'
    boolean raisedProposed = targets.any { String rid, Double oldPct -> result[rid] > oldPct + EPS }
    floorBinding == (raisedProposed || inactiveReopened)

    and: 'the pass is deterministic: an identical call yields identical output + identical logging'
    List<String> reasons2 = []
    def res2 = lib.sfApply(targets, rooms, s,
      { String r -> reasons2 << r })
    ((Map<String, Double>) res2[0]) == result && reasons2 == reasons

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  /** Reopened aperture of an inactive room (0 when it was never added to the result). */
  private static double reopenedPct(Map<String, Double> result, Map r) {
    return result[r.roomId] == null ? 0.0d : (result[r.roomId] as double)
  }

  // ---------------------------------------------------------------------------
  // Bias-prefix oracle: in (error desc, roomId desc) order, the set of raised
  // rooms must be a PREFIX — you never raise a lower-priority room until every
  // higher-priority room is maxed at 100. (The greedy raises a fixed-error room
  // to 100 before moving to the next, so this captures "largest error first"
  // AND the deterministic roomId tie-break in one invariant.)
  // ---------------------------------------------------------------------------
  private static void biasPrefixHolds(List ordered,
                                      Map<String, Double> targets, Map<String, Double> result) {
    for (int x = 0; x < ordered.size(); x++) {
      for (int y = x + 1; y < ordered.size(); y++) {
        boolean laterRaised = result[ordered[y].roomId] > targets[ordered[y].roomId] + EPS
        if (laterRaised) {
          assert result[ordered[x].roomId] >= 100.0d - EPS
        }
      }
    }
  }

  /**
   * List-order reopen oracle: walking the inactive rooms in their LIST order,
   * once a room is found below 100 % every SUBSEQUENT inactive room must be
   * untouched (0 %) — i.e. the reopen fills each inactive room to 100 % before
   * moving to the next, with at most one partially-filled room at the frontier.
   */
  private static void reopenIsListOrderedPrefix(List inactiveList,
                                                Map<String, Double> result) {
    boolean sawBelowFull = false
    for (Map r : inactiveList) {
      double v = reopenedPct(result, r)
      if (sawBelowFull) {
        assert v <= EPS
      }
      if (v < 100.0d - EPS) {
        sawBelowFull = true
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Scenario generation — three shapes that exercise both phases:
  //   'normal'        — floor met purely by padding active not-yet-satisfied need.
  //   'satisfied-mix' — high floor; satisfied active rooms are NEVER raised, so
  //                     the floor is reached by maxing the unsatisfied room then
  //                     reopening inactive vents.
  //   'last-resort'   — active + conventional capacity exhausted; inactive
  //                     reopen + log is forced (R6.10).
  // Inactive rooms are single-vent dampers and `inactiveCount` matches their
  // number (inactiveOpenPctSum = 0) so the Phase-1 / Phase-2 device views agree.
  // ---------------------------------------------------------------------------
  private Map makeScenario(PropertyGen g, int i) {
    switch (i % 3) {
      case 0:  return lastResortScenario(g)
      case 1:  return satisfiedMixScenario(g)
      default: return normalScenario(g)
    }
  }

  /** Floor met by padding active not-yet-satisfied need; satisfied/inactive present but untouched. */
  private Map normalScenario(PropertyGen g) {
    List rooms = []
    Map<String, Double> targets = [:]
    int nActive = g.nextInt(1, 5)
    (0..<nActive).each { int k ->
      boolean satisfied = g.nextInt(0, 4) == 0
      double err = satisfied ? g.nextDouble(-3.0d, 0.0d) : g.nextDouble(0.2d, 6.0d)
      rooms << activeRoom("a${k}", err, g)
      targets["a${k}".toString()] = satisfied ? 0.0d : g.nextDouble(0.0d, 60.0d)
    }
    // A couple of inactive rooms that should NOT be reopened in this family.
    int nInactive = g.nextInt(0, 2)
    (0..<nInactive).each { int k ->
      rooms << inactiveRoom("z${k}", g.nextDouble(-1.0d, 4.0d), g)
    }
    Map s = lib.dabv2NewAllocSettings()
    s.safetyFloorPct = g.nextDouble(20.0d, 60.0d)
    s.conventionalVents = g.nextInt(0, 2)
    s.conventionalOpenPct = g.nextDouble(60.0d, 100.0d)
    s.inactiveCount = nInactive
    s.inactiveOpenPctSum = 0.0d
    s.granularity = g.nextInt(1, 8)
    return [rooms: rooms, targets: targets, settings: s]
  }

  /**
   * High floor: the satisfied active rooms must NOT be raised (Reference
   * contract). The floor is reached by maxing the unsatisfied active room(s)
   * then reopening the inactive vents as a last resort.
   */
  private Map satisfiedMixScenario(PropertyGen g) {
    List rooms = []
    Map<String, Double> targets = [:]
    int nUnsat = g.nextInt(1, 3)
    (0..<nUnsat).each { int k ->
      rooms << activeRoom("u${k}", g.nextDouble(0.5d, 6.0d), g)
      targets["u${k}".toString()] = 0.0d
    }
    int nSat = g.nextInt(1, 3)
    (0..<nSat).each { int k ->
      rooms << activeRoom("s${k}", g.nextDouble(-4.0d, -0.1d), g)
      targets["s${k}".toString()] = 0.0d
    }
    // Inactive vents available so Phase 2 can lift the (deliberately high) floor.
    int nInactive = g.nextInt(2, 4)
    (0..<nInactive).each { int k ->
      rooms << inactiveRoom("z${k}", g.nextDouble(-1.0d, 4.0d), g)
    }
    Map s = lib.dabv2NewAllocSettings()
    s.safetyFloorPct = g.nextDouble(70.0d, 90.0d)   // forces inactive reopen after unsat maxed
    s.conventionalVents = 0
    s.conventionalOpenPct = 0.0d
    s.inactiveCount = nInactive
    s.inactiveOpenPctSum = 0.0d
    s.granularity = g.nextInt(1, 6)
    return [rooms: rooms, targets: targets, settings: s]
  }

  /**
   * Active + conventional capacity is mathematically exhausted: a single active
   * vent maxed at 100 is dragged below the floor by always-open-but-throttled
   * conventional vents, so the floor can ONLY be reached by reopening the
   * (initially closed) inactive rooms — the R6.10 last resort.
   */
  private Map lastResortScenario(PropertyGen g) {
    List rooms = []
    Map<String, Double> targets = [:]
    rooms << activeRoom('a0', g.nextDouble(1.0d, 5.0d), g)
    targets['a0'] = g.nextDouble(0.0d, 40.0d)
    // Two inactive rooms, currently CLOSED (not in the proposed targets).
    rooms << inactiveRoom('z0', 3.0d, g)   // reopened first (list order)
    rooms << inactiveRoom('z1', 1.0d, g)
    Map s = lib.dabv2NewAllocSettings()
    s.safetyFloorPct = 45.0d
    s.conventionalVents = 3
    s.conventionalOpenPct = 0.0d   // wide-open count but throttled airflow drags the average down
    s.inactiveCount = 2
    s.inactiveOpenPctSum = 0.0d
    s.granularity = 5
    return [rooms: rooms, targets: targets, settings: s]
  }

  private static Map activeRoom(String id, double err, PropertyGen g) {
    return room(id, true, err, g)
  }

  private static Map inactiveRoom(String id, double err, PropertyGen g) {
    return room(id, false, err, g)
  }

  private static Map room(String id, boolean active, double err, PropertyGen g) {
    return [
      roomId      : id,
      active      : active,
      tempC       : g.nextDouble(15.0d, 30.0d),
      efficiency  : g.nextDouble(0.01d, 0.5d),
      leak        : g.nextDouble(0.0d, 0.25d),
      currentOpen : g.nextDouble(0.0d, 100.0d),
      ventIds     : [("${id}#v0").toString()],
      signedErrorC: err
    ]
  }
}
