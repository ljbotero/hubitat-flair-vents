
import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based test for the PURE Learning_Model VentCurve (task 4.1).
 *
 * Implements Correctness Property 12 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 12: Vent-effectiveness curve is monotonic and normalized
 *   "For any sequence of online updates (including noisy, missing, or
 *    non-finite samples), the learned curve flow(a) is non-decreasing in
 *    aperture, flow(0) = leak in [0, LEAK_MAX], and flow(100%) = 1 after
 *    normalization — and these hold after EVERY update."
 *
 * Validates: Requirements 11.2, 11.3, 11.9.
 *
 * Mirrors the Reference `hvac_vent_optimizer/learning.py` VentCurve: a
 * piecewise-linear curve over breakpoints [0,5,10,20,35,50,75,100], kept
 * monotonic non-decreasing by weighted isotonic regression (Pool-Adjacent-
 * Violators) and renormalized so flow(100%) == 1 after each online update,
 * with the closed-vent leak clamped to [0, LEAK_MAX] and non-finite samples
 * ignored.
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS (100)
 * reproducible randomized update sequences.
 */
class LearningVentCurvePropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  // Tolerances: division-by-last during renormalize introduces tiny FP error.
  private static final double EPS = 1e-6d

  def 'Feature: hubitat-flair-vents-dab-v2, Property 12: Vent-effectiveness curve is monotonic and normalized'() {
    given: 'a cold-start curve seeded from a random (possibly out-of-range) leak'
    def g = PropertyGen.forIteration(i)
    // Seed leak intentionally spans beyond [0, LEAK_MAX] to exercise clamping.
    double seedLeak = g.nextDouble(-0.2d, 0.6d)
    def curve = lib.lrnSeedLinear(seedLeak)

    and: 'a sequence of noisy / missing / non-finite online updates'
    int updates = g.nextInt(1, 40)

    expect: 'the three invariants hold after EVERY update'
    (0..<updates).each {
      double aperturePct = g.nextDouble(-20.0d, 120.0d)   // includes out-of-range apertures
      double observed = nextObservedFlow(g)               // includes NaN/Inf/negative/>1
      lib.lrnVentCurveUpdate(curve, aperturePct, observed)

      // flow(0) == leak in [0, LEAK_MAX]
      double f0 = lib.lrnVentCurveFlow(curve, 0.0d)
      assert f0 >= -EPS
      assert f0 <= lib.LRN_LEAK_MAX + EPS

      // flow(100%) == 1
      double f100 = lib.lrnVentCurveFlow(curve, 100.0d)
      assert Math.abs(f100 - 1.0d) <= EPS

      // monotonic non-decreasing across a fine aperture sweep, all in [0,1]
      double prev = -1.0d
      for (double a = 0.0d; a <= 100.0d; a += 2.5d) {
        double f = lib.lrnVentCurveFlow(curve, a)
        assert f >= -EPS
        assert f <= 1.0d + EPS
        assert f >= prev - EPS
        prev = f
      }
    }

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  /**
   * Draw an observed-flow sample. ~15% of the time emit a "bad" sample
   * (non-finite, negative, or above full-open) to exercise robustness (R11.9);
   * otherwise a valid relative-airflow sample in (0, 1].
   */
  private static double nextObservedFlow(PropertyGen g) {
    int roll = g.nextInt(0, 99)
    if (roll < 5) { return Double.NaN }
    if (roll < 8) { return Double.POSITIVE_INFINITY }
    if (roll < 11) { return g.nextDouble(-1.0d, 0.0d) }   // negative -> clamps to 0
    if (roll < 15) { return g.nextDouble(1.0d, 3.0d) }    // > full   -> clamps to 1
    return g.nextDouble(0.01d, 1.0d)
  }

}
