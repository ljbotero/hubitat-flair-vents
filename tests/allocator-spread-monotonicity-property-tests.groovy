
import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based test for the PURE Allocator predicted-spread monotonicity
 * invariant (task 6.4): {@code Dabv2Allocator.predictedSpread(rooms, targets,
 * mode, setpointC, horizonMin)}.
 *
 * Implements Correctness Property 7 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 7: Spread monotonicity
 *   "For any allocation, applying a strictly spread-improving move never
 *    increases the predicted active-room spread, and reducing a satisfied
 *    room's aperture never increases the predicted spread."
 *   Validates: Requirements 7.1, 10.3.
 *
 * <b>The projection model (and why the property is well-defined).</b>
 * {@code predictedSpread} projects each ACTIVE room over a FIXED, shared horizon
 * {@code H} at the conditioning rate its commanded aperture implies
 * ({@code T_i - e_i*flow_i(a_i)*H} cooling; {@code +} heating), anchors on the
 * shared setpoint by clamping SATISFIED rooms (cooling {@code temp <= setpoint};
 * heating {@code temp >= setpoint}) at the setpoint, and returns {@code max-min}
 * over the active rooms ({@code 0} when fewer than two are active). Because the
 * horizon is fixed and shared, each room's projection depends ONLY on its own
 * commanded aperture — the projections are mutually independent and each is
 * monotone in aperture toward the setpoint. That independence is what makes
 * "spread-improving move" well-defined without circularity, and it is the exact
 * model the parity Reference ({@code balance.predicted_spread}) uses (D9).
 *
 * <b>Definition of a "strictly spread-improving move" (Property 7, part 1).</b>
 * Give MORE conditioning (a strictly larger aperture) to the room at the WORST
 * extreme of the projected band — the hottest projection when cooling, the
 * coldest when heating — bounded so that the room's NEW projection stays within
 * the CURRENT {@code [min, max]} band (it does not overshoot past the opposite
 * extreme). This is precisely the synchronized-convergence move: drag the
 * laggard toward the pack. A move that overshoots the opposite extreme is a
 * different kind of move (it can re-open the spread on the far side) and is
 * outside this property's scope, so the test only asserts the bound when the
 * move stays in band (mirrors the Reference's `assume`). Because only the
 * extreme room moves and it stays in band, the band can only shrink — the spread
 * never rises.
 *
 * <b>Reducing a satisfied room's aperture (Property 7, part 2).</b> A satisfied
 * room is already clamped at the setpoint in the projection, so reducing its
 * aperture leaves its projection pinned at the setpoint and changes no other
 * room — the spread is unchanged (hence never increased). This is asserted on a
 * non-trivial baseline (every active room commanded 80 %, satisfied rooms then
 * driven to 0 %) so a regression that let a satisfied room's aperture move its
 * projection would be caught.
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS (100)
 * reproducible randomized scenarios. Every scenario is constructed with >= 2
 * active rooms AND >= 1 satisfied active room AND >= 1 unsatisfied active room,
 * so BOTH sub-claims assert against a non-degenerate spread on every iteration.
 */
class AllocatorSpreadMonotonicityPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-6d

  def 'Feature: hubitat-flair-vents-dab-v2, Property 7: Spread monotonicity'() {
    given: 'a randomized active-room scenario with >= 2 active, >= 1 satisfied, >= 1 unsatisfied'
    def g = PropertyGen.forIteration(i)
    Map scenario = makeScenario(g, i)
    List rooms = scenario.rooms as List
    double setpointC = scenario.setpointC as double
    String mode = scenario.mode as String
    double horizon = scenario.horizon as double
    boolean heating = mode.toLowerCase().contains('heat')

    List active = rooms.findAll { it.active }
    List satisfied = active.findAll { satisfied(it, setpointC, heating) }

    expect: 'the scenario preconditions hold (so neither sub-claim is vacuous)'
    active.size() >= 2
    !satisfied.isEmpty()

    // === Property 7, part 2 — reducing a satisfied room's aperture never raises spread ===
    when: 'every active room is commanded 80 %, then satisfied rooms are driven to 0 %'
    Map<String, Double> baseHi = active.collectEntries { [(it.roomId): 80.0d] }
    Map<String, Double> reduced = new LinkedHashMap<String, Double>(baseHi)
    satisfied.each { reduced.put(it.roomId, 0.0d) }
    double spreadBeforeReduce = lib.allocPredictedSpread(rooms, baseHi, mode, setpointC, horizon)
    double spreadAfterReduce = lib.allocPredictedSpread(rooms, reduced, mode, setpointC, horizon)

    then: 'reducing the satisfied rooms never increases the predicted spread'
    spreadAfterReduce <= spreadBeforeReduce + EPS

    // === Property 7, part 1 — a strictly spread-improving move never raises spread ===
    when: 'more conditioning is given to the worst-extreme room, bounded to stay in band'
    Map<String, Double> baseLo = active.collectEntries { [(it.roomId): 40.0d] }
    Map<String, Double> proj = active.collectEntries {
      [(it.roomId): projection(it, baseLo[it.roomId] as double, setpointC, heating, horizon)]
    }
    double projMin = proj.values().min() as double
    double projMax = proj.values().max() as double
    // Worst extreme: hottest when cooling (drag down), coldest when heating (lift up).
    String extremeId = heating ? proj.min { it.value }.key : proj.max { it.value }.key
    def extreme = active.find { it.roomId == extremeId }
    double delta = g.nextDouble(1.0d, 40.0d)
    double newAperture = Math.min(100.0d, (baseLo[extremeId] as double) + delta)
    double newExtremeProj = projection(extreme, newAperture, setpointC, heating, horizon)
    // Only a move that keeps the extreme inside the current band is "spread-improving".
    boolean inBand = newExtremeProj >= projMin - 1e-9d && newExtremeProj <= projMax + 1e-9d

    Map<String, Double> proposed = new LinkedHashMap<String, Double>(baseLo)
    proposed.put(extremeId, newAperture)
    double spreadBeforeMove = lib.allocPredictedSpread(rooms, baseLo, mode, setpointC, horizon)
    double spreadAfterMove = lib.allocPredictedSpread(rooms, proposed, mode, setpointC, horizon)

    then: 'when the move stays in band, it never increases the predicted spread'
    !inBand || (spreadAfterMove <= spreadBeforeMove + EPS)

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Scenario generation. Guarantees >= 2 active rooms with >= 1 satisfied and
  // >= 1 unsatisfied active room (so both sub-claims assert on a real spread),
  // across both modes, learned-curve and scalar-leak rooms, multi-vent groups,
  // and irrelevant inactive rooms.
  // ---------------------------------------------------------------------------

  private Map makeScenario(PropertyGen g, int i) {
    String mode = g.nextBoolean() ? 'cooling' : 'heating'
    double setpointC = g.nextDouble(18.0d, 24.0d)
    boolean heating = mode == 'heating'
    double horizon = g.nextDouble(5.0d, 60.0d)

    List rooms = []
    // One guaranteed satisfied + one guaranteed unsatisfied active room.
    rooms << makeRoom(g, 'sat', true, true, setpointC, heating)
    rooms << makeRoom(g, 'uns', true, false, setpointC, heating)
    // 0-4 extra rooms of any disposition (active/inactive, satisfied/unsatisfied).
    int extra = g.nextInt(0, 4)
    (0..<extra).each { int k ->
      boolean active = g.nextInt(0, 9) < 7
      boolean roomSatisfied = g.nextBoolean()
      rooms << makeRoom(g, "r${k}", active, roomSatisfied, setpointC, heating)
    }

    return [rooms: rooms, setpointC: setpointC, mode: mode, horizon: horizon]
  }

  private Map makeRoom(PropertyGen g, String roomId, boolean active,
      boolean roomSatisfied, double setpointC, boolean heating) {
    // Satisfied (hyst 0): cooling temp <= setpoint; heating temp >= setpoint.
    double tempC
    if (heating) {
      tempC = roomSatisfied ? setpointC + g.nextDouble(0.0d, 4.0d)
                            : setpointC - g.nextDouble(0.2d, 6.0d)
    } else {
      tempC = roomSatisfied ? setpointC - g.nextDouble(0.0d, 4.0d)
                            : setpointC + g.nextDouble(0.2d, 6.0d)
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
  // Independent oracle mirroring predictedSpread's per-room projection: fixed
  // horizon, rate = efficiency * flow(aperture), SATISFIED rooms (hyst 0)
  // clamped at the setpoint. Used only to pick the extreme room and check the
  // in-band precondition; the spread assertions call the implementation.
  // ---------------------------------------------------------------------------

  private double projection(Map r, double aperturePct, double setpointC,
      boolean heating, double horizon) {
    double aperture = Math.max(0.0d, Math.min(100.0d, aperturePct))
    double rate = Math.max(0.0d, r.efficiency * flowAt(r, aperture))
    double projected
    if (heating) {
      projected = r.tempC + (rate * horizon)
      if (r.tempC >= setpointC) { projected = Math.min(projected, setpointC) }
    } else {
      projected = r.tempC - (rate * horizon)
      if (r.tempC <= setpointC) { projected = Math.max(projected, setpointC) }
    }
    return projected
  }

  private static boolean satisfied(Map r, double setpointC, boolean heating) {
    return heating ? (r.tempC >= setpointC) : (r.tempC <= setpointC)
  }

  private double flowAt(Map r, double aperturePct) {
    if (r.curve != null) { return lib.lrnVentCurveFlow(r.curve, aperturePct) }
    return lib.lrnFlowLinear(r.leak, aperturePct / 100.0d)
  }
}
