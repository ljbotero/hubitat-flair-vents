
// Learned-rate divergence regression tests (Task 2.5)
//
// Contract under test: the learned-rate selection MUST be able to diverge from
// the baseline. Once a regime cell has accumulated at least REGIME_MIN_N
// positive-rate samples, the effective rate is the (diverged) learned rate
// rather than being clamped back to the baseline EMA. This is the *reachable*
// regime gate (R11.7) that replaces the Reference's unreachable normalized-weight
// gate. In the CURRENT Integration there is no contextual-regime machinery yet
// (review defect class DC-4 verdict = ABSENT/N-A: a single per-room/per-mode EMA
// is maintained and used directly), so this test:
//   1. establishes/calibrates the reachable gate (REGIME_MIN_N) in the current
//      code as the contract carried forward to the pure Learning_Model (task 4.3);
//   2. PROVES divergence is reachable (and would FAIL against a deliberately
//      clamped gate that pinned the effective rate to baseline);
//   3. pins the behavior so the port preserves it.
//
// Requirements: 2.3, 2.4, 11.7, 20.4
// Defect class 4 (learned-rate selection not diverging from baseline),
// docs/review-findings.md (F-04).
//
// Run `./gradlew test --tests '*LearnedRateDivergence*'` to test.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class LearnedRateDivergenceTest extends Specification {

  private static final String APP_FILE = Dabv2AppHarness.combinedAppText()
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice,
            Flags.AllowWritingToSettings,
            Flags.AllowReadingNonInputSettings
          ]
  private static final AbstractMap USER_SETTINGS = ['debugLevel': 1]

  private Object buildScript() {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    return sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS)
  }

  def "learned rate diverges from baseline once it has >= REGIME_MIN_N positive samples"() {
    setup:
    def script = buildScript()
    // Baseline EMA seed (e.g. global/cold-start rate). The learned cell starts
    // here and is pulled away by repeated strong positive-rate observations,
    // exactly as finalizeRoomStates accumulates per-room rates via rollingAverage.
    BigDecimal baseline = 0.02
    BigDecimal learned = baseline
    int n = 0
    (1..5).each {
      learned = script.rollingAverage(learned, 0.20, 1.0, 4)
      n++
    }

    expect: 'the learned EMA itself has moved well away from baseline (sanity)'
    n >= script.REGIME_MIN_N
    Math.abs((learned as double) - (baseline as double)) > 0.05

    when: 'the reachable gate selects the effective rate'
    def effective = script.effectiveLearnedRate(baseline, learned, n)

    then: 'with >= REGIME_MIN_N positive samples it returns the DIVERGED learned rate, not baseline'
    Math.abs((effective as double) - (baseline as double)) > 0.05
    (effective as double) == (learned as double)
  }

  def "gate is reachable: below REGIME_MIN_N stays at baseline, crossing the threshold flips to the learned rate"() {
    setup:
    def script = buildScript()
    BigDecimal baseline = 0.02
    BigDecimal learned = 0.20   // a markedly different learned value

    expect: 'too few samples -> withhold the learned rate (no premature divergence)'
    script.effectiveLearnedRate(baseline, learned, script.REGIME_MIN_N - 1) == baseline

    and: 'at/above the threshold -> select the diverged learned rate (gate is reachable)'
    (script.effectiveLearnedRate(baseline, learned, script.REGIME_MIN_N) as double) == (learned as double)
    (script.effectiveLearnedRate(baseline, learned, script.REGIME_MIN_N + 7) as double) == (learned as double)
  }

  def "selected rate is clamped to the physical bounds and degrades gracefully on bad input"() {
    setup:
    def script = buildScript()
    BigDecimal baseline = 0.02

    expect: 'a learned rate above MAX is clamped down (still diverges, never runs away)'
    (script.effectiveLearnedRate(baseline, 99.0, script.REGIME_MIN_N) as double) == (script.MAX_TEMP_CHANGE_RATE as double)

    and: 'a non-positive learned rate is not selected -> fall back to baseline'
    script.effectiveLearnedRate(baseline, 0.0, script.REGIME_MIN_N) == baseline
    script.effectiveLearnedRate(baseline, -1.0, script.REGIME_MIN_N) == baseline

    and: 'non-finite / null learned samples are ignored -> baseline'
    script.effectiveLearnedRate(baseline, Double.NaN, script.REGIME_MIN_N) == baseline
    script.effectiveLearnedRate(baseline, null, script.REGIME_MIN_N) == baseline
  }
}
