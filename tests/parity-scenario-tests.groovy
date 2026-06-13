
import spock.lang.Specification
import spock.lang.Shared
import spock.lang.Unroll

/**
 * The parity EVIDENCE GATE (task 8.3, decision D9) — Correctness Property 17.
 *
 * Because there is no Groovy simulator and no live A/B (D9), correctness of the
 * port rests entirely on PARITY: the Groovy allocator must reproduce the
 * validated Python Reference allocator's decisions on a shared, comfort-critical
 * scenario set. This data-driven spec is that gate. For EVERY committed
 * {@code *.scenario.json} (cooling + heating mirrors), it:
 *
 *   1. loads the portable scenario into the PURE allocator inputs
 *      ({@link RoomAllocInput}/{@link AllocSettings}/{@link DuctSignals} +
 *      learned {@link Dabv2Learning.VentCurve}) via {@link ParityFixtures};
 *   2. runs the Groovy {@link Dabv2Allocator#allocate} then the single
 *      safety-floor choke point {@link Dabv2SafetyFloor#apply}; and
 *   3. asserts, against the Reference-generated {@code *.expected.json}:
 *      <ul>
 *        <li>every active room's commanded aperture matches the Reference within
 *            {@code tolerance.aperturePoints} (default ±1) AFTER rounding to the
 *            configured granularity (R17.2);</li>
 *        <li>the {@code airflowLimited} set matches the Reference exactly;</li>
 *        <li>the {@code floorBinding} flag matches the Reference exactly;</li>
 *        <li>combined open % is at or above the clamped floor — ZERO violations
 *            (R17.3 / Property 1).</li>
 *      </ul>
 *
 * Any mismatch fails the build and blocks the default-strategy flip (R17.5,
 * task 10.2). This is the strict, sensitive check the gate requires: it MUST
 * fail if the Groovy and Reference outputs diverge by more than the documented
 * tolerance.
 *
 * One property per feature method, tagged with the EXACT Property 17 heading so
 * {@code ./gradlew test --tests '*Property*'} selects it; the {@code where:}
 * block drives one execution per committed scenario fixture.
 *
 * Validates: Requirements 17.2, 17.3, 17.5.
 */
class ParityScenarioSpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  /** FP slack for the floor comparison and rounded-aperture deltas. */
  private static final double EPS = 1e-6d

  @Unroll
  def 'Feature: hubitat-flair-vents-dab-v2, Property 17: Allocator parity with the Reference (evidence gate)'() {
    given: 'a shared parity scenario and its Reference-generated expected output'
    ParityScenario sc = ParityFixtures.loadScenario(id)
    ParityExpected ex = ParityFixtures.loadExpected(id)
    int gran = Math.max(1, sc.settings.granularity)
    int tol = Math.max(1, ex.toleranceAperturePoints)
    double floor = lib.sfClampSafetyFloor(sc.settings.safetyFloorPct)

    and: 'the expected output is genuine Reference evidence (stamped with a SHA)'
    ex.referenceCommit != null && !ex.referenceCommit.trim().isEmpty() &&
      ex.referenceCommit != 'unknown'

    when: 'the Groovy allocator then the single safety-floor choke point run on it'
    Map res = lib.allocAllocate(sc.rooms, sc.setpointC, sc.mode, sc.settings, sc.duct)
    List floored =
      lib.sfApply(res.targets, sc.rooms, sc.settings)
    Map<String, Double> safe = floored[0]
    boolean floorBinding = ((Boolean) floored[1]).booleanValue()

    then: 'the floored result commands exactly the rooms the Reference does (active + any last-resort reopened inactive)'
    Set<String> activeIds = sc.rooms.findAll { it.active }*.roomId as Set
    // The Reference safety floor may ADD inactive rooms in its last-resort reopen
    // (R6.10); the Groovy floor must reopen the SAME rooms, so the commanded key
    // set matches the Reference's expected target key set exactly.
    safe.keySet() == ex.targets.keySet()
    ex.targets.keySet().containsAll(activeIds)

    and: 'every commanded aperture matches the Reference within tolerance after granularity rounding (R17.2)'
    ex.targets.keySet().every { String rid ->
      double g = roundTo(safe.get(rid), gran)
      double e = roundTo(ex.targets.get(rid), gran)
      double delta = Math.abs(g - e)
      assert delta <= tol + EPS :
        "PARITY MISMATCH [${id}] room='${rid}': groovy=${safe.get(rid)} (rounded ${g}) " +
        "vs Reference=${ex.targets.get(rid)} (rounded ${e}); delta=${delta} > tolerance=${tol}"
      true
    }

    and: 'the airflow-limited set matches the Reference exactly'
    assert (res.airflowLimited as Set) == (ex.airflowLimited as Set) :
      "AIRFLOW-LIMITED MISMATCH [${id}]: groovy=${res.airflowLimited as Set} vs Reference=${ex.airflowLimited as Set}"

    and: 'the floorBinding flag matches the Reference exactly'
    assert floorBinding == ex.floorBinding :
      "FLOOR-BINDING MISMATCH [${id}]: groovy=${floorBinding} vs Reference=${ex.floorBinding}"

    and: 'combined open % is at or above the clamped floor — zero violations (R17.3 / Property 1)'
    Map<String, Double> perVent = ParityFixtures.expandPerVent(safe, sc.rooms)
    double combined = lib.sfCombinedOpenPct(perVent, sc.settings)
    assert combined >= floor - EPS :
      "FLOOR VIOLATION [${id}]: combined=${combined} < floor=${floor}"

    where: 'every committed parity scenario fixture (cooling + heating mirrors)'
    id << ParityFixtures.scenarioIds()
  }

  /** Round an aperture to the configured granularity grid (R17.2 — compare post-rounding). */
  private static double roundTo(Double v, int gran) {
    double x = v == null ? 0.0d : v.doubleValue()
    return ((double) Math.round(x / (double) gran)) * (double) gran
  }
}
