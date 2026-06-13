
import spock.lang.Specification
import spock.lang.Shared
import spock.lang.Unroll

/**
 * Unit tests for the PURE Learning_Model room-efficiency learner (task 4.3):
 * deriveEffectiveness, predictedRate, the cold-start seed, the adaptive-alpha
 * EMA, the dual heat/cool index, and the reachable regime gate's exact boundary.
 *
 * These complement the Property 14 spec (LearningRoomEfficiencyPropertySpec)
 * with specific worked examples and edge cases, matching the Reference
 * `hvac_vent_optimizer/learning.py` (derive_effectiveness / predicted_rate /
 * update_room_efficiency / effective_rate).
 *
 * Validates: Requirements 11.1, 11.4, 11.7, 11.9, 11.11.
 */
class LearningRoomEfficiencyUnitSpec extends Specification {

  private static final double EPS = 1e-9d

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  // ---- deriveEffectiveness (R11.4 cold-start source) -----------------------

  def 'deriveEffectiveness: trusted positive fit yields eRoom = full-open rate, leak = intercept/full-open'() {
    when: 'a well-sampled rising fit: rate ~= 0.005*pct + 0.1 (full-open = 0.6)'
    def eff = lib.lrnDeriveEffectiveness(0.005d, 0.1d, lib.LRN_MODEL_MIN_N)

    then: 'eRoom is the 100% rate and leak is the closed/open ratio, both clamped'
    Math.abs(eff.eRoom - 0.6d) <= EPS
    Math.abs(eff.leak - (0.1d / 0.6d)) <= EPS
    eff.leak >= 0.0d && eff.leak <= lib.LRN_LEAK_MAX
  }

  def 'deriveEffectiveness: too few samples falls back to LEAK_DEFAULT but still reports eRoom'() {
    when:
    def eff = lib.lrnDeriveEffectiveness(0.005d, 0.1d, lib.LRN_MODEL_MIN_N - 1)

    then: 'leak is the default until trusted; eRoom still reflects the full-open rate'
    Math.abs(eff.leak - lib.LRN_LEAK_DEFAULT) <= EPS
    Math.abs(eff.eRoom - 0.6d) <= EPS
  }

  def 'deriveEffectiveness: degenerate non-positive full-open rate -> eRoom 0 and LEAK_DEFAULT'() {
    when: 'a fit whose full-open rate is <= 0'
    def eff = lib.lrnDeriveEffectiveness(-0.01d, 0.2d, 50)  // 100*-0.01 + 0.2 = -0.8

    then:
    eff.eRoom == 0.0d
    Math.abs(eff.leak - lib.LRN_LEAK_DEFAULT) <= EPS
  }

  def 'deriveEffectiveness: a large intercept/open ratio is clamped to LEAK_MAX'() {
    when: 'intercept nearly equals full-open => raw ratio ~1, must clamp to LEAK_MAX'
    def eff = lib.lrnDeriveEffectiveness(0.0001d, 0.5d, 50) // full-open = 0.51

    then:
    Math.abs(eff.leak - lib.LRN_LEAK_MAX) <= EPS
  }

  // ---- predictedRate (R11.4 combined prediction) ---------------------------

  def 'predictedRate: composes eRoom with the linear flow curve (closed=leak, open=full)'() {
    expect: 'closed vent passes only the leak fraction of eRoom'
    Math.abs(lib.lrnPredictedRate(0.6d, 0.1d, 0.0d) - (0.6d * 0.1d)) <= EPS

    and: 'fully open delivers the full-open rate'
    Math.abs(lib.lrnPredictedRate(0.6d, 0.1d, 100.0d) - 0.6d) <= EPS

    and: 'half open is leak + (1-leak)*0.5 of eRoom'
    Math.abs(lib.lrnPredictedRate(0.6d, 0.1d, 50.0d) - (0.6d * (0.1d + (0.9d * 0.5d)))) <= EPS
  }

  def 'predictedRate: never negative and non-decreasing in aperture'() {
    given:
    double prev = -1.0d

    expect:
    (0..100).each { int pct ->
      double r = lib.lrnPredictedRate(-5.0d, 0.2d, pct as double) // negative eRoom -> 0
      assert r >= 0.0d
    }
    (0..100).each { int pct ->
      double r = lib.lrnPredictedRate(0.8d, 0.15d, pct as double)
      assert r >= prev - EPS
      prev = r
    }
  }

  // ---- cold-start linear seed (R11.4) --------------------------------------

  def 'seedLinear curve from deriveEffectiveness leak gives flow(0)=leak and flow(100)=1'() {
    given: 'leak derived from a thin fit (falls back to LEAK_DEFAULT)'
    def eff = lib.lrnDeriveEffectiveness(0.004d, 0.05d, 2)
    def curve = lib.lrnSeedLinear(eff.leak)

    expect:
    Math.abs(lib.lrnVentCurveFlow(curve, 0.0d) - eff.leak) <= 1e-6d
    Math.abs(lib.lrnVentCurveFlow(curve, 100.0d) - 1.0d) <= 1e-6d
  }

  // ---- updateRoomEfficiency robustness + dual index ------------------------

  def 'updateRoomEfficiency: first sample seeds baseline and selected cell exactly'() {
    given:
    def m = lib.lrnNewRoomModel()

    when:
    lib.lrnUpdateRoomEfficiency(m, 0.25d, 1, 'cooling')

    then: 'baseline and cell[1] both seeded to the sample; counts == 1'
    Math.abs((m.cooling.baseline as double) - 0.25d) <= EPS
    Math.abs(m.cooling.regimes[1].rate - 0.25d) <= EPS
    m.cooling.regimes[1].n == 1
    m.cooling.n == 1
    and: 'heating untouched'
    m.heating.baseline == null
  }

  @Unroll
  def 'updateRoomEfficiency: ignores #label (model unchanged)'() {
    given:
    def m = lib.lrnNewRoomModel()
    lib.lrnUpdateRoomEfficiency(m, 0.3d, 0, 'cooling')
    double baseBefore = m.cooling.baseline as double
    int nBefore = m.cooling.n

    when:
    lib.lrnUpdateRoomEfficiency(m, bad, 0, 'cooling')

    then: 'a null / non-finite sample leaves the EMA and counts untouched'
    Math.abs((m.cooling.baseline as double) - baseBefore) <= EPS
    m.cooling.n == nBefore

    where:
    label              | bad
    'a null sample'    | (Double) null
    'NaN'              | Double.NaN
    'positive Inf'     | Double.POSITIVE_INFINITY
    'negative Inf'     | Double.NEGATIVE_INFINITY
  }

  def 'updateRoomEfficiency: negative finite sample clamps to 0 but still counts'() {
    given:
    def m = lib.lrnNewRoomModel()

    when: 'a negative observation is folded in'
    lib.lrnUpdateRoomEfficiency(m, -1.0d, 2, 'heating')

    then: 'seeded to 0 (clamped), counts advance, and the cell rate is non-positive'
    m.heating.regimes[2].n == 1
    m.heating.regimes[2].rate == 0.0d
    (m.heating.baseline as double) == 0.0d
  }

  def 'updateRoomEfficiency: regime index is clamped into range (no crash)'() {
    given:
    def m = lib.lrnNewRoomModel()

    when:
    lib.lrnUpdateRoomEfficiency(m, 0.4d, 99, 'cooling')   // -> last cell
    lib.lrnUpdateRoomEfficiency(m, 0.4d, -7, 'cooling')   // -> first cell

    then:
    m.cooling.regimes[lib.LRN_EFF_REGIME_COUNT - 1].n == 1
    m.cooling.regimes[0].n == 1
  }

  // ---- effectiveRate reachable gate (exact boundary) -----------------------

  def 'effectiveRate: fresh model returns clamped baseline (RATE_MIN) with no samples'() {
    given:
    def m = lib.lrnNewRoomModel()

    expect: 'no baseline yet -> clamp(0) == RATE_MIN'
    Math.abs(lib.lrnEffectiveRate(m, 0, 'cooling') - lib.LRN_RATE_MIN) <= EPS
  }

  def 'effectiveRate: gate flips exactly at REGIME_MIN_N with a positive cell rate'() {
    given: 'baseline seeded low via a different regime, focus cell driven high'
    def m = lib.lrnNewRoomModel()
    (0..<10).each { lib.lrnUpdateRoomEfficiency(m, 0.02d, 1, 'cooling') }

    when: 'feed REGIME_MIN_N - 1 high samples to regime 0 (still below the gate)'
    (0..<(lib.LRN_REGIME_MIN_N - 1)).each {
      lib.lrnUpdateRoomEfficiency(m, 0.5d, 0, 'cooling')
    }

    then: 'effective rate is still the (low) baseline, not the high cell'
    lib.lrnEffectiveRate(m, 0, 'cooling') < 0.2d

    when: 'one more sample reaches REGIME_MIN_N'
    lib.lrnUpdateRoomEfficiency(m, 0.5d, 0, 'cooling')

    then: 'effective rate is now the diverged learned cell rate (~0.5)'
    Math.abs(lib.lrnEffectiveRate(m, 0, 'cooling') - 0.5d) <= 1e-6d
  }

  def 'effectiveRate: a trusted-but-non-positive cell is rejected (falls back to baseline)'() {
    given: 'baseline positive, focus cell driven with zeros past the count gate'
    def m = lib.lrnNewRoomModel()
    (0..<6).each { lib.lrnUpdateRoomEfficiency(m, 0.03d, 1, 'cooling') }
    (0..<(lib.LRN_REGIME_MIN_N + 1)).each {
      lib.lrnUpdateRoomEfficiency(m, 0.0d, 0, 'cooling')
    }

    expect: 'cell.n >= REGIME_MIN_N but cell.rate == 0 -> use baseline'
    m.cooling.regimes[0].n >= lib.LRN_REGIME_MIN_N
    m.cooling.regimes[0].rate == 0.0d
    Math.abs(lib.lrnEffectiveRate(m, 0, 'cooling') - lib.lrnEffectiveRate(m, 3, 'cooling')) <= EPS
  }

  def 'effectiveRate: an absurdly high learned cell rate is clamped to RATE_MAX'() {
    given:
    def m = lib.lrnNewRoomModel()
    (0..<(lib.LRN_REGIME_MIN_N + 2)).each {
      lib.lrnUpdateRoomEfficiency(m, 99.0d, 0, 'cooling')   // clamps each sample? no
    }

    expect: 'returned rate never exceeds RATE_MAX'
    Math.abs(lib.lrnEffectiveRate(m, 0, 'cooling') - lib.LRN_RATE_MAX) <= EPS
  }

  def "effectiveRate: cooling and heating are fully independent indices"() {
    given:
    def m = lib.lrnNewRoomModel()

    when: 'train only cooling regime 0 past the gate'
    (0..<(lib.LRN_REGIME_MIN_N + 3)).each {
      lib.lrnUpdateRoomEfficiency(m, 0.4d, 0, 'cooling')
    }

    then: 'cooling diverges while heating is still the cold-start baseline (RATE_MIN)'
    Math.abs(lib.lrnEffectiveRate(m, 0, 'cooling') - 0.4d) <= 1e-6d
    Math.abs(lib.lrnEffectiveRate(m, 0, 'heating') - lib.LRN_RATE_MIN) <= EPS
  }
}
