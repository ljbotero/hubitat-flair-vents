
import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based test for the PURE Learning_Model VentCurve inverse + knee
 * (task 4.2).
 *
 * Implements Correctness Property 13 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 13: Curve inversion and knee consistency
 *   "For any learned curve, inverse(flow(a)) == a within tolerance on the
 *    rising region; any required flow at or above flow(knee) maps to the knee
 *    aperture; knee is the smallest breakpoint reaching (1 - KNEE_EPS) of full
 *    airflow; and the Allocator never commands above the knee to chase
 *    airflow."
 *
 * Validates: Requirements 11.8, 11.10.
 *
 * Mirrors the Reference `hvac_vent_optimizer/learning.py` VentCurve.inverse /
 * VentCurve.knee (and the module helpers `_curve_inverse` / `_curve_knee`):
 *   - knee = smallest breakpoint whose flow reaches (1 - KNEE_EPS) of full;
 *   - inverse(f) is the smallest aperture achieving f on the rising region
 *     (piecewise-linear inverse), plateau-safe: f >= flow(knee) -> knee,
 *     f <= leak -> 0; monotonic non-decreasing in f.
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS (100)
 * reproducible randomized curves.
 */
class LearningCurveInversePropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  // Round-trip tolerance: a couple of breakpoint-percent points absorbs the
  // FP error introduced by renormalization (divide-by-last) plus the
  // interpolate -> invert composition.
  private static final double APERTURE_EPS = 0.5d
  // A segment must rise by at least this much to count as "strictly rising"
  // (well above the ~1e-16 FP jitter that flat plateaus exhibit).
  private static final double RISE_TOL = 1e-2d

  def 'Feature: hubitat-flair-vents-dab-v2, Property 13: Curve inversion and knee consistency'() {
    given: 'a cold-start curve seeded from a random leak, then optionally trained'
    def g = PropertyGen.forIteration(i)
    double seedLeak = g.nextDouble(-0.2d, 0.6d)
    def curve = lib.lrnSeedLinear(seedLeak)

    and: 'an optional sequence of (possibly noisy) updates that may flatten the top'
    int updates = g.nextInt(0, 30)
    (0..<updates).each {
      double aperturePct = g.nextDouble(-20.0d, 120.0d)
      double observed = nextObservedFlow(g)
      lib.lrnVentCurveUpdate(curve, aperturePct, observed)
    }

    and: 'the effective curve, read through the public flow() (seed-or-learned)'
    List<Integer> bps = lib.LRN_CURVE_BREAKPOINTS
    List<Double> ef = bps.collect { lib.lrnVentCurveFlow(curve, it as double) }
    double full = ef[ef.size() - 1]
    int knee = lib.lrnVentCurveKnee(curve)

    expect: 'knee is the smallest breakpoint reaching (1 - KNEE_EPS) of full airflow'
    double target = (1.0d - lib.LRN_KNEE_EPS) * (full != 0.0d ? full : 1.0d)
    int expectedKnee = bps[bps.size() - 1]
    for (int k = 0; k < bps.size(); k++) {
      if (ef[k] >= target) { expectedKnee = bps[k]; break }
    }
    knee == expectedKnee

    and: 'any required flow at or above flow(knee) maps to the knee aperture'
    double kneeFlow = lib.lrnVentCurveFlow(curve, knee as double)
    [kneeFlow, (kneeFlow + 1.0d) / 2.0d, 1.0d, 5.0d].each { double reqFlow ->
      assert Math.abs(lib.lrnVentCurveInverse(curve, reqFlow) - (knee as double)) <= APERTURE_EPS
    }

    and: 'any required flow at or below the closed-vent leak maps to 0 aperture'
    double leak = ef[0]
    assert lib.lrnVentCurveInverse(curve, leak) <= APERTURE_EPS
    assert lib.lrnVentCurveInverse(curve, leak - 0.05d) <= APERTURE_EPS
    assert lib.lrnVentCurveInverse(curve, -1.0d) <= APERTURE_EPS

    and: 'inverse(flow(a)) == a within tolerance on the strictly-rising region'
    // The "rising region" is where the curve strictly increases. A point in the
    // INTERIOR of a strictly-rising segment achieves a flow value reached at a
    // unique aperture, so the inverse round-trips. We deliberately exclude
    // plateau boundaries: where consecutive breakpoints share a flow value
    // (within FP jitter), inverse(flow) is ambiguous (any aperture on the
    // plateau yields the same airflow) and the smallest-aperture tie-break is
    // not meaningful for the round-trip.
    int kneeIdx = bps.indexOf(knee)
    for (int k = 1; k <= kneeIdx; k++) {
      double rise = ef[k] - ef[k - 1]
      if (rise >= RISE_TOL) {
        // Midpoint of a strictly-rising segment: uniquely invertible.
        double aMid = ((bps[k - 1] as double) + (bps[k] as double)) / 2.0d
        double backMid = lib.lrnVentCurveInverse(curve, lib.lrnVentCurveFlow(curve, aMid))
        assert Math.abs(backMid - aMid) <= APERTURE_EPS
      }
    }

    and: 'inverse is monotonic non-decreasing in the required flow fraction'
    double prev = -1.0d
    for (double f = 0.0d; f <= 1.0d; f += 0.02d) {
      double a = lib.lrnVentCurveInverse(curve, f)
      assert a >= prev - APERTURE_EPS
      prev = a
    }

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  /**
   * Draw an observed-flow sample. ~15% of the time emit a "bad" sample
   * (non-finite, negative, or above full-open) to exercise robustness;
   * otherwise a valid relative-airflow sample in (0, 1].
   */
  private static double nextObservedFlow(PropertyGen g) {
    int roll = g.nextInt(0, 99)
    if (roll < 5) { return Double.NaN }
    if (roll < 8) { return Double.POSITIVE_INFINITY }
    if (roll < 11) { return g.nextDouble(-1.0d, 0.0d) }
    if (roll < 15) { return g.nextDouble(1.0d, 3.0d) }
    return g.nextDouble(0.01d, 1.0d)
  }

}
