
// R4 throttling: stuck-counter detect/reset, notification coalescing, and
// user-visible throttle status (Task 6.7; R4.15/R4.16/R4.17/R4.18/R4.19).
//
// FAILING example specs (strict TDD). These encode the REQUIRED behavior that
// Task 6.8 ("Implement stuck-counter detect/reset, notification coalescing, and
// status") must satisfy. They are RED against the current code:
//
//   Stuck-counter detect/reset (R4.19, design §6.4): increment/decrement must
//     stamp a last-progress timestamp (`atomicState.requestTrackingTs`) and the
//     reset must be GATED by a documented timeout — a counter wedged at the
//     concurrency limit is reset to 0 only AFTER the timeout elapses with no
//     progress, never before. Today `incrementActiveRequests` /
//     `decrementActiveRequests` never stamp `requestTrackingTs`, and
//     `initRequestTracking` only initializes a null counter (it never resets a
//     wedged one). RED on both the stamping and the timeout-gated reset.
//
//   Notification coalescing + status (R4.15/R4.16, design §6.5): repeated
//     identical throttles within `DABV2_ERROR_COALESCE_MS` must coalesce into a
//     SINGLE surfaced status (not a log flood), re-surfacing only after the
//     window elapses. Today there is no throttle-recording entry point. RED
//     (the helper does not exist).
//
//   User-visible status (R4.17/R4.18, design §6.5): the app must expose a
//     user-visible status indicating "throttling occurred" and, after recovery,
//     "control recovered". Today no `atomicState.throttleStatus` surface exists.
//     RED (the helpers do not exist).
//
// Time is controlled by stubbing the framework `now()` on the AppExecutor mock
// (the established pattern, see cycle-finalize-race-tests) and mutating the
// `testNowMs` field, so the documented timeout and coalesce window can be crossed deterministically
// without depending on the wall clock. The exact documented-timeout value is
// not asserted; the specs only require the reset to be gated SOMEWHERE between
// a 1 s "within" probe and a 1 h "past" probe, which any reasonable
// stuck-counter timeout (> the 5 s HTTP timeout, well under an hour) satisfies.
//
// Run `./gradlew test --tests '*ThrottleStuckCounterCoalesceStatus*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class ThrottleStuckCounterCoalesceStatusSpec extends Specification {

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

  // Mirrors the production MAX_CONCURRENT_REQUESTS concurrency cap (the wedge
  // point the stuck-counter recovery must detect).
  private static final int CAP = 8

  private CapturingLog log
  private Map stateMap
  private Map atomicStateMap
  // Injectable wall-clock. hubitat_ci routes the framework `now()` to the
  // AppExecutor mock (NOT through the script metaClass), so time is controlled by
  // stubbing `now()` on the mock and mutating this field — the established
  // pattern (see cycle-finalize-race-tests). Each feature method gets a fresh
  // Spock spec instance, so this resets to 0 per test.
  private long testNowMs = 0L

  private Object buildScript(Map state = [:], Map atomic = [:]) {
    log = new CapturingLog()
    stateMap = state
    atomicStateMap = atomic
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> atomicStateMap
      _ * getLog() >> log
      _ * now() >> { testNowMs }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': [debugLevel: 1])
    script.state = stateMap
    script.atomicState = atomicStateMap
    return script
  }

  // Coerce whatever shape the throttle status surface takes (String or Map) to a
  // lowercased string so the substring assertions are tolerant of the eventual
  // representation chosen by Task 6.8.
  private String statusText(Object script) {
    return (script.atomicState?.throttleStatus?.toString() ?: '').toLowerCase()
  }

  // ===========================================================================
  // R4.19 — stuck concurrency-counter detect/reset (design §6.4)
  // ===========================================================================

  // GENUINELY RED: increment must stamp a last-progress timestamp.
  def "incrementActiveRequests stamps the request-tracking progress timestamp"() {
    setup:
    def script = buildScript([:], [activeRequests: 0])
    Long t = 7_000_000L
    testNowMs = t

    when:
    script.incrementActiveRequests()

    then: 'the counter advanced AND a progress timestamp was stamped (R4.19)'
    script.atomicState.activeRequests == 1
    (script.atomicState.requestTrackingTs as Long) == t
  }

  // GENUINELY RED: decrement must stamp a last-progress timestamp too (progress
  // = a request completing), so a steadily-draining path is never seen as stuck.
  def "decrementActiveRequests stamps the request-tracking progress timestamp"() {
    setup:
    def script = buildScript([:], [activeRequests: 3, requestTrackingTs: 1L])
    Long t = 8_500_000L
    testNowMs = t

    when:
    script.decrementActiveRequests()

    then: 'the counter dropped AND the progress timestamp advanced to now (R4.19)'
    script.atomicState.activeRequests == 2
    (script.atomicState.requestTrackingTs as Long) == t
  }

  // GENUINELY RED: a counter wedged at the limit PAST the documented timeout with
  // no progress is detected and reset to 0 so the request path cannot wedge
  // permanently. Today initRequestTracking only initializes a null counter.
  def "a counter wedged at the limit past the timeout is detected and reset to 0"() {
    setup:
    Long stampedAt = 1_000_000L
    def script = buildScript([:], [activeRequests: CAP, requestTrackingTs: stampedAt])
    // now() is one hour past the last progress: comfortably beyond any documented
    // stuck-counter timeout.
    testNowMs = stampedAt + 3_600_000L

    when:
    script.initRequestTracking()

    then: 'the wedged counter is reset so the request path returns to service (R4.19)'
    script.atomicState.activeRequests == 0
  }

  // GREEN regression guard: a counter sitting at the limit only briefly (within
  // the timeout) is a legitimately busy path and must NOT be reset prematurely.
  def "a counter at the limit within the timeout is preserved (no premature reset)"() {
    setup:
    Long stampedAt = 1_000_000L
    def script = buildScript([:], [activeRequests: CAP, requestTrackingTs: stampedAt])
    // now() is only 1 s past the last progress: well within any documented timeout.
    testNowMs = stampedAt + 1_000L

    when:
    script.initRequestTracking()

    then: 'the busy counter is left intact (reset is timeout-gated, R4.19)'
    script.atomicState.activeRequests == CAP
  }

  // ===========================================================================
  // R4.15/R4.16 — notification coalescing into a single status (design §6.5)
  // ===========================================================================

  // GENUINELY RED: repeated identical throttles coalesce within
  // DABV2_ERROR_COALESCE_MS into a single surfaced status; re-surface after.
  def "repeated identical throttles coalesce within the window then re-surface after it"() {
    setup:
    def script = buildScript([:], [:])
    Long t0 = 2_000_000L
    long window = script.DABV2_ERROR_COALESCE_MS as long

    expect: 'the first throttle surfaces a status (true == newly surfaced)'
    script.dabV2RecordThrottle(t0) == true

    and: 'an identical throttle a few seconds later is coalesced (suppressed)'
    script.dabV2RecordThrottle(t0 + 5_000L) == false

    and: 'a throttle after the coalesce window surfaces again (single status, not a flood)'
    script.dabV2RecordThrottle(t0 + window + 1_000L) == true
  }

  // ===========================================================================
  // R4.17 — user-visible "throttling occurred" status (design §6.5)
  // ===========================================================================

  // GENUINELY RED: recording a throttle exposes a user-visible "throttling" status.
  def "recording a throttle exposes a user-visible throttling-occurred status"() {
    setup:
    def script = buildScript([:], [:])

    when:
    script.dabV2RecordThrottle(3_000_000L)

    then: 'a user-visible status indicates throttling occurred (R4.17)'
    statusText(script).contains('throttl')
  }

  // ===========================================================================
  // R4.18 — user-visible "control recovered" status (design §6.5)
  // ===========================================================================

  // GENUINELY RED: after recovery, the status flips to "control recovered".
  def "noting recovery exposes a user-visible control-recovered status"() {
    setup:
    def script = buildScript([:], [:])
    script.dabV2RecordThrottle(4_000_000L)

    when:
    script.dabV2NoteControlRecovered(4_100_000L)

    then: 'a user-visible status indicates control recovered after throttling (R4.18)'
    statusText(script).contains('recover')
  }
}
