
import spock.lang.Specification
import spock.lang.Shared

/**
 * Property-based test for the PURE Learning_Model room-efficiency learner +
 * reachable regime gate (task 4.3).
 *
 * Implements Correctness Property 14 from design.md EXACTLY (one property per
 * feature method, tagged with the exact property heading):
 *
 *   Property 14: Effective-rate reachability and learning stability
 *   "For any room-efficiency model, once a regime cell has at least
 *    REGIME_MIN_N samples and a positive rate, effectiveRate returns that
 *    regime's rate (which can diverge from the baseline); with fewer samples it
 *    returns the clamped baseline; and all learned rates stay within
 *    [RATE_MIN, RATE_MAX] regardless of noisy or missing samples (never
 *    negative or divergent)."
 *
 * Validates: Requirements 11.1, 11.4, 11.7, 11.9, 11.11.
 *
 * Mirrors the Reference `hvac_vent_optimizer/learning.py`
 * RoomEfficiencyModel / update_room_efficiency / effective_rate:
 *   - dual heat/cool sub-models (each its own baseline EMA + 4 regime cells);
 *   - adaptive-alpha baseline EMA advances on EVERY update; each regime cell's
 *     EMA advances only when its regime is selected;
 *   - the REACHABLE gate (the R11.7 fix): a regime cell's learned rate is used
 *     over the baseline iff it has >= REGIME_MIN_N samples AND a positive rate;
 *     otherwise the clamped baseline is used — so learned rates can actually
 *     diverge from baseline (vs the Reference's earlier unreachable
 *     normalized-weight gate);
 *   - all returned rates clamped to [RATE_MIN, RATE_MAX].
 *
 * The Groovy port carries forward the Integration's task-2.5 contract:
 * REGIME_MIN_N == 3 and the rate band [RATE_MIN, RATE_MAX] == the app's
 * [MIN_TEMP_CHANGE_RATE, MAX_TEMP_CHANGE_RATE] == [0.001, 1.5].
 *
 * The class name contains "Property" so `./gradlew test --tests '*Property*'`
 * selects it; the `where:` block drives >= PropertyGen.ITERATIONS (100)
 * reproducible randomized scenarios.
 */
class LearningRoomEfficiencyPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-9d
  // A clearly-separated divergence margin: baseline and the learned regime rate
  // are seeded far enough apart that, after EMA drift, they stay distinguishable.
  private static final double DIVERGE_MARGIN = 0.05d

  def 'Feature: hubitat-flair-vents-dab-v2, Property 14: Effective-rate reachability and learning stability'() {
    given: 'a reproducible per-iteration RNG and a random mode + regime focus'
    def g = PropertyGen.forIteration(i)
    String mode = g.nextBoolean() ? 'cooling' : 'heating'
    String otherMode = mode == 'cooling' ? 'heating' : 'cooling'
    int count = lib.LRN_EFF_REGIME_COUNT
    int idx = g.nextInt(0, count - 1)
    int otherRegime = (idx + 1) % count   // always a DIFFERENT regime from idx

    // -----------------------------------------------------------------------
    // Sub-property A — bounds invariant under noisy / missing samples (R11.9).
    // Every effectiveRate read, for every regime AND mode, stays within the
    // band no matter how adversarial the update stream is.
    // -----------------------------------------------------------------------
    expect: 'effectiveRate stays in [RATE_MIN, RATE_MAX] after EVERY noisy update'
    def noisyModel = lib.lrnNewRoomModel()
    int updates = g.nextInt(1, 40)
    (0..<updates).each {
      Double sample = nextNoisySample(g)
      int r = g.nextInt(0, count - 1)
      String m = g.nextBoolean() ? 'cooling' : 'heating'
      lib.lrnUpdateRoomEfficiency(noisyModel, sample, r, m)
      for (int rr = 0; rr < count; rr++) {
        double erC = lib.lrnEffectiveRate(noisyModel, rr, 'cooling')
        double erH = lib.lrnEffectiveRate(noisyModel, rr, 'heating')
        assert erC >= lib.LRN_RATE_MIN - EPS && erC <= lib.LRN_RATE_MAX + EPS
        assert erH >= lib.LRN_RATE_MIN - EPS && erH <= lib.LRN_RATE_MAX + EPS
        assert Double.isFinite(erC) && Double.isFinite(erH)
      }
    }

    and: 'out-of-range regime indices clamp into the valid cell band (no crash)'
    double low = lib.lrnEffectiveRate(noisyModel, -5, mode)
    double high = lib.lrnEffectiveRate(noisyModel, 999, mode)
    low == lib.lrnEffectiveRate(noisyModel, 0, mode)
    high == lib.lrnEffectiveRate(noisyModel, count - 1, mode)

    and: 'the reachable gate withholds the learned rate below REGIME_MIN_N, then diverges'
    // Seed a baseline well away from the regime cell's eventual learned rate by
    // first feeding many samples to a DIFFERENT regime (baseline advances on
    // every update; the focus cell stays at n == 0).
    double baseVal = g.nextDouble(0.01d, 0.05d)
    double cellVal = g.nextDouble(0.30d, 0.90d)   // clearly > baseVal, inside band
    def m2 = lib.lrnNewRoomModel()
    (0..<15).each { lib.lrnUpdateRoomEfficiency(m2, baseVal, otherRegime, mode) }

    // Below threshold: REGIME_MIN_N - 1 positive samples in the focus regime.
    (0..<(lib.LRN_REGIME_MIN_N - 1)).each {
      lib.lrnUpdateRoomEfficiency(m2, cellVal, idx, mode)
    }
    double below = lib.lrnEffectiveRate(m2, idx, mode)
    double clampedBaselineBelow = clampRate(modeBaseline(m2, mode))
    // It returns the CLAMPED BASELINE, not yet the (diverged) learned cell rate.
    assert Math.abs(below - clampedBaselineBelow) <= EPS
    assert Math.abs(below - clampRate(cellVal)) > DIVERGE_MARGIN

    and: 'crossing REGIME_MIN_N flips effectiveRate to the diverged learned rate'
    lib.lrnUpdateRoomEfficiency(m2, cellVal, idx, mode)   // cell.n == REGIME_MIN_N
    double at = lib.lrnEffectiveRate(m2, idx, mode)
    double clampedBaselineAt = clampRate(modeBaseline(m2, mode))
    // Now it returns the regime cell's learned rate (== cellVal for a constant
    // stream), which has DIVERGED from the baseline.
    assert Math.abs(at - clampRate(cellVal)) <= EPS
    assert Math.abs(at - clampedBaselineAt) > DIVERGE_MARGIN

    and: 'staying above the threshold keeps using the (still-diverged) learned rate'
    (0..<g.nextInt(1, 10)).each { lib.lrnUpdateRoomEfficiency(m2, cellVal, idx, mode) }
    double after = lib.lrnEffectiveRate(m2, idx, mode)
    assert Math.abs(after - clampRate(cellVal)) <= EPS

    and: 'a non-positive learned cell rate is NEVER selected — falls back to baseline'
    // Drive a regime to >= REGIME_MIN_N samples but with all-zero (non-positive)
    // observations; its cell rate stays 0, so the gate must reject it.
    def m3 = lib.lrnNewRoomModel()
    (0..<12).each { lib.lrnUpdateRoomEfficiency(m3, baseVal, otherRegime, mode) }
    (0..<(lib.LRN_REGIME_MIN_N + 2)).each {
      lib.lrnUpdateRoomEfficiency(m3, 0.0d, idx, mode)
    }
    double zeroCell = lib.lrnEffectiveRate(m3, idx, mode)
    assert Math.abs(zeroCell - clampRate(modeBaseline(m3, mode))) <= EPS

    and: 'dual heat/cool isolation — updating one mode never changes the other (R11.1)'
    def m4 = lib.lrnNewRoomModel()
    List<Double> otherBefore = (0..<count).collect {
      lib.lrnEffectiveRate(m4, it as int, otherMode)
    }
    (0..<20).each {
      lib.lrnUpdateRoomEfficiency(m4, g.nextDouble(0.05d, 0.50d), g.nextInt(0, count - 1), mode)
    }
    (0..<count).each {
      assert Math.abs(lib.lrnEffectiveRate(m4, it as int, otherMode) - otherBefore[it]) <= EPS
    }

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // -- helpers ---------------------------------------------------------------

  /** Read a mode's baseline EMA (null when no sample has seeded it yet). */
  private static Double modeBaseline(Object model, String mode) {
    def sub = mode == 'heating' ? model.heating : model.cooling
    return sub.baseline as Double
  }

  /** Clamp a rate into the learned band exactly like effectiveRate does. */
  private double clampRate(Double rate) {
    double v = rate == null ? 0.0d : rate.doubleValue()
    return Math.max(lib.LRN_RATE_MIN, Math.min(lib.LRN_RATE_MAX, v))
  }

  /**
   * Draw a sample for the bounds-robustness stream. ~25% of the time emit a
   * "bad" value (null / NaN / +Inf / -Inf / negative / absurdly large) to
   * exercise R11.9; otherwise a plausible positive rate.
   */
  private static Double nextNoisySample(PropertyGen g) {
    int roll = g.nextInt(0, 99)
    if (roll < 5) { return null }
    if (roll < 9) { return Double.NaN }
    if (roll < 13) { return Double.POSITIVE_INFINITY }
    if (roll < 16) { return Double.NEGATIVE_INFINITY }
    if (roll < 21) { return g.nextDouble(-2.0d, 0.0d) }   // negative -> clamps to 0
    if (roll < 25) { return g.nextDouble(5.0d, 50.0d) }   // huge -> clamps to RATE_MAX
    return g.nextDouble(0.001d, 1.2d)
  }

}
