
import spock.lang.Specification
import spock.lang.Shared
import spock.lang.Unroll

/**
 * Unit tests for the PURE Learning_Model multi-vent grouping helpers (task 4.4):
 * {@code groupCombinedFlow} (equal-capacity mean of per-vent flows) and
 * {@code groupPredictedRate} (shared eRoom x combined group flow).
 *
 * Mirrors the Reference `hvac_vent_optimizer/learning.py`
 * (group_combined_flow / group_predicted_rate) EXACTLY:
 *   - combined flow is the MEAN of per-vent flow(leak_i, a_i), so it stays in
 *     [0, 1], is non-decreasing in every aperture, and reduces to flow() for a
 *     single-vent group;
 *   - identical apertures collapse to flow(mean_leak, a);
 *   - an empty group has no airflow and returns 0.0;
 *   - a leaks/apertures length mismatch is a caller-contract violation (mirrors
 *     the Reference ValueError) and raises IllegalArgumentException;
 *   - group_predicted_rate clamps eRoom >= 0 and converts aperture percents to
 *     fractions, equalling predictedRate for a single-vent group.
 *
 * Supports Property 16 (grouped vents act as one logical unit, R15.1).
 *
 * Validates: Requirements 15.1.
 */
class LearningVentGroupUnitSpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-9d

  // ---- groupCombinedFlow: mean of per-vent flows ---------------------------

  def 'groupCombinedFlow: single-vent group equals flowLinear exactly'() {
    expect: 'the N == 1 case is just the single-vent flow (no special-casing)'
    Math.abs(
      lib.lrnGroupCombinedFlow([0.1d], [0.4d]) -
      lib.lrnFlowLinear(0.1d, 0.4d)) <= EPS
  }

  def 'groupCombinedFlow: two vents is the mean of their per-vent flows'() {
    given:
    double f0 = lib.lrnFlowLinear(0.1d, 0.2d)
    double f1 = lib.lrnFlowLinear(0.3d, 0.8d)

    expect:
    Math.abs(
      lib.lrnGroupCombinedFlow([0.1d, 0.3d], [0.2d, 0.8d]) -
      ((f0 + f1) / 2.0d)) <= EPS
  }

  def 'groupCombinedFlow: identical apertures collapse to flow(mean_leak, a)'() {
    given: 'three vents at the same aperture but different leaks'
    double a = 0.5d
    double meanLeak = (0.05d + 0.15d + 0.25d) / 3.0d

    expect: 'mean of flow(leak_i, a) == flow(mean_leak, a)'
    Math.abs(
      lib.lrnGroupCombinedFlow([0.05d, 0.15d, 0.25d], [a, a, a]) -
      lib.lrnFlowLinear(meanLeak, a)) <= EPS
  }

  def 'groupCombinedFlow: empty group returns 0.0 (no airflow)'() {
    expect:
    lib.lrnGroupCombinedFlow([], []) == 0.0d
  }

  def 'groupCombinedFlow: stays within [0, 1] for arbitrary inputs'() {
    expect:
    (0..100).each { int i ->
      double frac = i / 100.0d
      double v = lib.lrnGroupCombinedFlow([0.0d, 0.2d, 0.35d], [frac, 1.0d - frac, frac])
      assert v >= 0.0d - EPS && v <= 1.0d + EPS
    }
  }

  def 'groupCombinedFlow: all vents fully open gives combined flow 1.0'() {
    expect: 'shared eRoom keeps its full-open meaning'
    Math.abs(lib.lrnGroupCombinedFlow([0.1d, 0.2d, 0.3d], [1.0d, 1.0d, 1.0d]) - 1.0d) <= EPS
  }

  def 'groupCombinedFlow: order-independent (a mean is commutative)'() {
    given:
    double forward = lib.lrnGroupCombinedFlow([0.05d, 0.2d, 0.3d], [0.1d, 0.5d, 0.9d])
    double reversed = lib.lrnGroupCombinedFlow([0.3d, 0.2d, 0.05d], [0.9d, 0.5d, 0.1d])

    expect:
    Math.abs(forward - reversed) <= EPS
  }

  def 'groupCombinedFlow: non-decreasing when one aperture rises'() {
    given: 'raise vent 0 from closed to open, others fixed'
    double lo = lib.lrnGroupCombinedFlow([0.1d, 0.2d], [0.0d, 0.5d])
    double hi = lib.lrnGroupCombinedFlow([0.1d, 0.2d], [1.0d, 0.5d])

    expect:
    hi >= lo - EPS
  }

  def 'groupCombinedFlow: out-of-range apertures saturate via flowLinear clamp'() {
    expect: 'apertures clamp into [0, 1] so 1.5 behaves as fully open, -0.5 as closed'
    Math.abs(
      lib.lrnGroupCombinedFlow([0.1d, 0.2d], [1.5d, -0.5d]) -
      ((lib.lrnFlowLinear(0.1d, 1.0d) + lib.lrnFlowLinear(0.2d, 0.0d)) / 2.0d)) <= EPS
  }

  def 'groupCombinedFlow: length mismatch is a contract violation (throws)'() {
    when:
    lib.lrnGroupCombinedFlow([0.1d, 0.2d], [0.5d])

    then:
    thrown(IllegalArgumentException)
  }

  // ---- groupPredictedRate: eRoom x combined group flow ---------------------

  def 'groupPredictedRate: single-vent group equals predictedRate exactly'() {
    expect:
    Math.abs(
      lib.lrnGroupPredictedRate(0.6d, [0.1d], [40.0d]) -
      lib.lrnPredictedRate(0.6d, 0.1d, 40.0d)) <= EPS
  }

  def 'groupPredictedRate: composes eRoom with the combined (percent->fraction) flow'() {
    given:
    double combined = lib.lrnGroupCombinedFlow([0.1d, 0.2d], [0.25d, 0.75d])

    expect: 'aperture percents are converted to fractions before combining'
    Math.abs(
      lib.lrnGroupPredictedRate(0.8d, [0.1d, 0.2d], [25.0d, 75.0d]) -
      (0.8d * combined)) <= EPS
  }

  def 'groupPredictedRate: all vents fully open yields eRoom'() {
    expect: 'combined flow == 1 => predicted rate == eRoom'
    Math.abs(lib.lrnGroupPredictedRate(0.5d, [0.1d, 0.3d], [100.0d, 100.0d]) - 0.5d) <= EPS
  }

  @Unroll
  def 'groupPredictedRate: negative eRoom (#eRoom) clamps to a non-negative rate'() {
    expect:
    lib.lrnGroupPredictedRate(eRoom, [0.1d, 0.2d], [50.0d, 50.0d]) >= 0.0d

    where:
    eRoom << [-0.01d, -1.0d, -100.0d]
  }

  def 'groupPredictedRate: empty group yields 0.0'() {
    expect:
    lib.lrnGroupPredictedRate(0.7d, [], []) == 0.0d
  }

  def 'groupPredictedRate: length mismatch propagates the contract violation'() {
    when:
    lib.lrnGroupPredictedRate(0.6d, [0.1d], [25.0d, 75.0d])

    then:
    thrown(IllegalArgumentException)
  }
}
