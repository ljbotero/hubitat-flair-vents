
// R6 circulation settings / clamps / debounce / default-off EXAMPLE specs
// (Task 8.3; R6.5, R6.6, R6.7, R6.8, R6.9).
//
// Strict-TDD FAILING example specs for spec flair-vents-v0236 task 8.3. They pin
// the configuration-boundary behavior that task 8.4 ("Implement circulation
// settings, clamping, debounce, and default-off") must satisfy. These are
// example specs (not property specs) — the class name intentionally omits
// "Property".
//
// Behavior pinned (Design §R6 (5–9)):
//
//   * circulationOpenPct  — documented default 50, validated range 10–100 %,
//     clamped to the nearest bound                                  (R6.5/R6.6)
//   * circulationDebounceSec — documented default 60 s, validated range
//     0–600 s, clamped to the nearest bound, and a debounce gate that
//     suppresses short-burst circulation thrash                     (R6.7/R6.8)
//   * circulationEnabled  — DISABLED (false) by default so existing installs see
//     no behavior change on upgrade                                 (R6.9)
//
// Why these are genuinely RED until task 8.4:
//   - `getDabV2CirculationOpenPct()` ships today (tasks 8.1/8.2) but only rounds
//     the raw setting; it does NOT clamp to the 10–100 range, so an over/under
//     range value is returned unclamped — the clamp assertions FAIL.
//   - `getDabV2CirculationDebounceSec()` does not exist yet — the default/clamp
//     assertions throw MissingMethodException (RED).
//   - `getDabV2Config()` does not yet carry `circulationDebounceSec` — the
//     config-map assertion FAILS.
//   - `shouldApplyCirculationChange(...)` (the debounce short-burst gate) does
//     not exist yet — the suppression assertions throw MissingMethodException
//     (RED).
//
// Green guards (already satisfied, asserted to pin the contract):
//   - `getDabV2CirculationEnabled()` defaults false (R6.9).
//   - `getDabV2CirculationOpenPct()` defaults 50 when unset (R6.5).
//
// Requirements: 6.5, 6.6, 6.7, 6.8, 6.9
// Design: §R6 (5–9)
//
// Run `./gradlew test --tests '*CirculationSettingsClampDebounce*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class CirculationSettingsClampDebounceTest extends Specification {

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

  private Object buildScript(Map userSettings = [:]) {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': userSettings)
    script.atomicState = [:]
    return script
  }

  // ===========================================================================
  // R6.9 — circulation OFF by default (GREEN GUARD: ships today). Pins the
  // backward-compatibility contract so existing installs are unchanged on
  // upgrade until the feature is explicitly enabled.
  // ===========================================================================

  def "circulationEnabled defaults to false so existing installs are unchanged on upgrade (R6.9)"() {
    given:
    def script = buildScript([:])

    expect: 'no circulation setting present -> feature disabled'
    script.getDabV2CirculationEnabled() == false

    and: 'the resolved DAB v2 config reports circulation disabled by default'
    script.getDabV2Config().circulationEnabled == false
  }

  // ===========================================================================
  // R6.5/R6.6 — circulation open percentage default + clamp to 10–100 %.
  // ===========================================================================

  def "circulationOpenPct defaults to 50 when unset (R6.5)"() {
    given:
    def script = buildScript([:])

    expect: 'documented default of 50 %'
    script.getDabV2CirculationOpenPct() == 50
  }

  def "circulationOpenPct keeps an in-range value (R6.5)"() {
    given:
    def script = buildScript([circulationOpenPct: 75])

    expect: 'an in-range value passes through unchanged'
    script.getDabV2CirculationOpenPct() == 75
  }

  def "circulationOpenPct clamps an under-range value up to the 10 % lower bound (R6.6)"() {
    given:
    def script = buildScript([circulationOpenPct: 5])

    expect: 'RED until 8.4: a value below 10 is clamped to the lower bound'
    script.getDabV2CirculationOpenPct() == 10
  }

  def "circulationOpenPct clamps an over-range value down to the 100 % upper bound (R6.6)"() {
    given:
    def script = buildScript([circulationOpenPct: 150])

    expect: 'RED until 8.4: a value above 100 is clamped to the upper bound'
    script.getDabV2CirculationOpenPct() == 100
  }

  def "the resolved DAB v2 config carries the clamped circulation open percentage (R6.6)"() {
    given:
    def script = buildScript([circulationOpenPct: 150])

    expect: 'RED until 8.4: the config map exposes the clamped percentage'
    script.getDabV2Config().circulationOpenPct == 100
  }

  // ===========================================================================
  // R6.7/R6.8 — circulation debounce default + clamp to 0–600 s.
  // (RED: getDabV2CirculationDebounceSec absent today.)
  // ===========================================================================

  def "circulationDebounceSec defaults to 60 seconds when unset (R6.7)"() {
    given:
    def script = buildScript([:])

    expect: 'RED until 8.4: documented default of 60 seconds'
    script.getDabV2CirculationDebounceSec() == 60
  }

  def "circulationDebounceSec keeps an in-range value (R6.7)"() {
    given:
    def script = buildScript([circulationDebounceSec: 120])

    expect: 'an in-range value passes through unchanged'
    script.getDabV2CirculationDebounceSec() == 120
  }

  def "circulationDebounceSec clamps an under-range value up to the 0 s lower bound (R6.8)"() {
    given:
    def script = buildScript([circulationDebounceSec: -10])

    expect: 'RED until 8.4: a value below 0 is clamped to the lower bound'
    script.getDabV2CirculationDebounceSec() == 0
  }

  def "circulationDebounceSec clamps an over-range value down to the 600 s upper bound (R6.8)"() {
    given:
    def script = buildScript([circulationDebounceSec: 900])

    expect: 'RED until 8.4: a value above 600 is clamped to the upper bound'
    script.getDabV2CirculationDebounceSec() == 600
  }

  def "the resolved DAB v2 config carries the clamped circulation debounce (R6.7/R6.8)"() {
    given:
    def script = buildScript([circulationDebounceSec: 900])

    expect: 'RED until 8.4: the config map exposes the clamped debounce seconds'
    script.getDabV2Config().circulationDebounceSec == 600
  }

  // ===========================================================================
  // R6.7 — the debounce gate suppresses short-burst circulation thrash.
  // (RED: shouldApplyCirculationChange absent today.) Mirrors the existing
  // shouldApplyVentMove anti-chatter gate: a circulation state change inside the
  // debounce window (a prior change is on record) is suppressed; once the window
  // has elapsed (or with no prior change / a zero debounce) it is applied.
  // ===========================================================================

  def "a circulation change inside the debounce window is suppressed (R6.7)"() {
    given:
    def script = buildScript([:])

    expect: 'RED until 8.4: a change 10s after the last one with a 60s debounce is suppressed'
    script.shouldApplyCirculationChange(
      nowMs: 10_000L, lastCirculationMs: 0L, debounceMs: 60_000L) == false
  }

  def "a circulation change after the debounce window has elapsed is applied (R6.7)"() {
    given:
    def script = buildScript([:])

    expect: 'RED until 8.4: a change 61s after the last one with a 60s debounce is applied'
    script.shouldApplyCirculationChange(
      nowMs: 61_000L, lastCirculationMs: 0L, debounceMs: 60_000L) == true
  }

  def "a circulation change with no prior change on record is applied (R6.7)"() {
    given:
    def script = buildScript([:])

    expect: 'RED until 8.4: no prior circulation timestamp -> no debounce to honor'
    script.shouldApplyCirculationChange(
      nowMs: 5_000L, lastCirculationMs: null, debounceMs: 60_000L) == true
  }

  def "a zero debounce never suppresses a circulation change (R6.8 lower bound)"() {
    given:
    def script = buildScript([:])

    expect: 'RED until 8.4: a 0 s debounce disables short-burst suppression'
    script.shouldApplyCirculationChange(
      nowMs: 1L, lastCirculationMs: 0L, debounceMs: 0L) == true
  }
}
