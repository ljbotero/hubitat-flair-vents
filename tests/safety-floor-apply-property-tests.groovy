
import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based test for the PURE Safety_Floor enforcement (task 5.2):
 * {@code Dabv2SafetyFloor.apply(targets, rooms, settings)}.
 *
 * Implements Correctness Property 1 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 1: Safety floor is inviolable
 *   "For any set of rooms, proposed targets, and settings (including
 *    degenerate inputs — empty active set, single room, all satisfied), the
 *    combined open percentage of Safety_Floor.apply(...) (smart vents counted
 *    individually + conventional + currently-open inactive) is >= the clamped
 *    configured floor; the floor only ever RAISES apertures (new[r] >= old[r])
 *    and is never relaxed by leakage (combined uses commanded aperture only)."
 *
 * Validates: Requirements 6.1, 6.2, 6.3, 6.5, 6.9, 8.5, 15.4, 5.4.
 *
 * <b>Reference-faithful contract (task 8.3 / decision D9).</b> The floor mirrors
 * the validated Python Reference {@code balance.apply_safety_floor} exactly:
 *
 *   Phase 1 — raise active NOT-YET-SATISFIED rooms (signedErrorC &gt; 0) until
 *     the floor is met or that capacity is exhausted. Satisfied active rooms are
 *     NEVER reopened.
 *   Phase 2 — last resort: when no active not-yet-satisfied capacity remains and
 *     the TRUE TOTAL-AIRFLOW view (every device, including the configured count
 *     of currently-closed inactive dampers) is below the floor, reopen inactive
 *     rooms and ADD them to the returned targets.
 *
 * The pass therefore (a) ONLY EVER RAISES apertures, (b) may ADD inactive-room
 * keys in Phase 2, and (c) meets the floor whenever raisable capacity remains
 * (active not-yet-satisfied OR inactive). When capacity is genuinely exhausted
 * the floor can be physically unreachable; the property asserts exactly that
 * disjunction.
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS (100)
 * reproducible randomized scenarios, deliberately including the degenerate
 * shapes (empty active set / single room / all satisfied) and the inactive
 * last-resort reopen.
 */
class SafetyFloorApplyPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-6d

  def 'Feature: hubitat-flair-vents-dab-v2, Property 1: Safety floor is inviolable'() {
    given: 'a randomized scenario (rooms / proposed targets / settings)'
    def g = PropertyGen.forIteration(i)
    Map scenario = makeScenario(g, i)
    List rooms = scenario.rooms as List
    Map<String, Double> targets = scenario.targets as Map<String, Double>
    Map s = scenario.settings as Map
    double floor = lib.sfClampSafetyFloor(s.safetyFloorPct)

    when: 'the Safety_Floor is enforced'
    def res = lib.sfApply(targets, rooms, s)
    Map<String, Double> result = (Map<String, Double>) res[0]
    boolean floorBinding = (boolean) res[1]
    Map roomById = rooms.collectEntries { [(it.roomId): it] }

    then: 'every commanded aperture stays within [0, 100]'
    result.values().every { it >= -EPS && it <= 100.0d + EPS }

    and: 'the floor only ever RAISES apertures: new[r] >= old[r] for every proposed room'
    targets.every { String rid, Double oldPct -> result[rid] >= oldPct - EPS }

    and: 'the proposed targets are preserved; any ADDED keys are inactive rooms reopened as a last resort'
    result.keySet().containsAll(targets.keySet())
    (result.keySet() - targets.keySet()).every { String rid ->
      roomById[rid] != null && !roomById[rid].active && result[rid] >= -EPS
    }

    and: 'floorBinding is true iff at least one aperture was raised (proposed raised OR inactive reopened)'
    boolean raisedProposed = targets.any { String rid, Double oldPct -> result[rid] > oldPct + EPS }
    boolean reopenedInactive = (result.keySet() - targets.keySet()).any { result[it] > EPS }
    floorBinding == (raisedProposed || reopenedInactive)

    and: 'combined >= clamped floor, unless ALL raisable capacity is exhausted'
    double combined = combinedOf(result, roomById, s)
    boolean capacityExhausted = raisableCapacityExhausted(result, roomById, s)
    combined >= floor - EPS || capacityExhausted

    and: 'leakage never relaxes the floor: result is invariant to per-room leak'
    def res2 = lib.sfApply(targets, withDifferentLeak(rooms, g), s)
    Map<String, Double> result2 = (Map<String, Double>) res2[0]
    boolean floorBinding2 = (boolean) res2[1]
    result2 == result && floorBinding2 == floorBinding

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Scenario generation — covers the full input space plus the degenerate
  // shapes called out by Property 1 (empty active set / single / all satisfied)
  // and the inactive last-resort reopen. Inactive rooms are modelled as
  // single-vent dampers and `inactiveCount` is kept consistent with the number
  // of inactive rooms (inactiveOpenPctSum = 0, closed) so the Phase-1 and
  // Phase-2 device views agree; the floor-met oracle below relies on that.
  // ---------------------------------------------------------------------------

  private Map makeScenario(PropertyGen g, int i) {
    int nRooms
    String shape
    if (i % 13 == 0) { shape = 'empty-active'; nRooms = g.nextInt(0, 3) }
    else if (i % 11 == 0) { shape = 'single'; nRooms = 1 }
    else if (i % 7 == 0) { shape = 'all-satisfied'; nRooms = g.nextInt(1, 5) }
    else { shape = 'mixed'; nRooms = g.nextInt(1, 6) }

    List rooms = []
    Map<String, Double> targets = [:]
    int inactiveRoomCount = 0
    (0..<nRooms).each { int k ->
      String roomId = "r${k}"
      boolean active
      if (shape == 'empty-active') { active = false }
      else if (shape == 'single' || shape == 'all-satisfied') { active = true }
      else { active = g.nextInt(0, 9) < 7 }   // ~70% active in mixed

      boolean satisfied = (shape == 'all-satisfied') ? true : g.nextBoolean()
      double err = satisfied ? g.nextDouble(-3.0d, 0.0d) : g.nextDouble(0.01d, 6.0d)

      Map r = [:]
      r.roomId = roomId
      r.active = active
      r.tempC = g.nextDouble(15.0d, 30.0d)
      r.efficiency = g.nextDouble(0.01d, 0.5d)
      r.leak = g.nextDouble(0.0d, 0.25d)
      r.currentOpen = g.nextDouble(0.0d, 100.0d)
      // Active rooms may be multi-vent; inactive rooms are single-vent dampers
      // so `inactiveCount` (set below) matches the per-vent device view.
      int nVents = active ? g.nextInt(1, 3) : 1
      r.ventIds = (0..<nVents).collect { "${roomId}#v${it}".toString() }
      r.signedErrorC = err
      rooms << r

      // Allocator emits targets for ACTIVE rooms only; satisfied -> 0 (overshoot close).
      // Inactive rooms are NOT in the proposed targets; Phase 2 adds them if needed.
      if (active) {
        targets[roomId] = satisfied ? 0.0d : g.nextDouble(0.0d, 100.0d)
      } else {
        inactiveRoomCount++
      }
    }

    Map s = lib.dabv2NewAllocSettings()
    // Floor: sometimes valid, sometimes out-of-band / NaN to exercise clamping.
    int floorRoll = g.nextInt(0, 9)
    if (floorRoll == 0) { s.safetyFloorPct = Double.NaN }
    else if (floorRoll == 1) { s.safetyFloorPct = g.nextDouble(91.0d, 130.0d) }
    else if (floorRoll == 2) { s.safetyFloorPct = g.nextDouble(0.0d, 19.0d) }
    else { s.safetyFloorPct = g.nextDouble(20.0d, 90.0d) }

    s.conventionalVents = g.nextInt(0, 5)
    s.conventionalOpenPct = g.nextDouble(0.0d, 100.0d)
    // Keep the inactive device count consistent with the modelled inactive
    // (single-vent, closed) rooms so the Phase-1 and Phase-2 device views agree.
    s.inactiveCount = inactiveRoomCount
    s.inactiveOpenPctSum = 0.0d
    s.granularity = g.nextInt(1, 10)

    return [rooms: rooms, targets: targets, settings: s]
  }

  /** Clone the room list, perturbing only the (floor-irrelevant) leak field. */
  private static List withDifferentLeak(List rooms, PropertyGen g) {
    return rooms.collect { src ->
      Map r = [:]
      r.roomId = src.roomId
      r.active = src.active
      r.tempC = src.tempC
      r.efficiency = src.efficiency
      r.leak = g.nextDouble(0.3d, 0.9d)   // deliberately large, different leak
      r.currentOpen = src.currentOpen
      r.ventIds = src.ventIds
      r.signedErrorC = src.signedErrorC
      return r
    }
  }

  // ---------------------------------------------------------------------------
  // Test-side oracle: replicate the module's per-vent expansion + combined math
  // (commanded aperture only) so the assertion is independent of apply()'s
  // internals. Active rooms always contribute their vents; an inactive room
  // contributes only while held open (pct > 0) — exactly as the module does.
  // ---------------------------------------------------------------------------

  private double combinedOf(Map<String, Double> result,
                                   Map roomById, Map s) {
    Map<String, Double> perVent = [:]
    result.each { String roomId, Double pct ->
      def r = roomById[roomId]
      if (r == null) { perVent[roomId] = pct; return }
      if (!r.active && pct <= 0.0d) { return }
      if (r.ventIds != null && !r.ventIds.isEmpty()) {
        r.ventIds.each { String vid -> perVent[vid] = pct }
      } else {
        perVent[roomId] = pct
      }
    }
    return lib.sfCombinedOpenPct(perVent, s)
  }

  /**
   * All raisable capacity is exhausted: there is no active NOT-YET-SATISFIED
   * room with headroom AND (no inactive capacity to reopen — either inactiveCount
   * is 0 or every inactive room is already at 100). This is exactly the condition
   * under which the Reference floor cannot raise any further, so the floor may be
   * physically unreachable.
   */
  private static boolean raisableCapacityExhausted(Map<String, Double> result,
                                                   Map roomById, Map s) {
    boolean unsatActiveHeadroom = result.any { String roomId, Double pct ->
      def r = roomById[roomId]
      r != null && r.active && r.signedErrorC > 0.0d && pct < 100.0d - EPS
    }
    boolean inactiveHeadroom = false
    if (s.inactiveCount > 0) {
      inactiveHeadroom = roomById.values().any { r ->
        if (r.active) { return false }
        double pct = result[r.roomId] == null ? 0.0d : (result[r.roomId] as double)
        pct < 100.0d - EPS
      }
    }
    return !unsatActiveHeadroom && !inactiveHeadroom
  }
}
