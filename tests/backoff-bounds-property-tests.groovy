
// R4 bounded-backoff property test (Task 6.1; R4.5/R4.6/R4.7).
//
// Implements Correctness Property 25 from design.md (§6.2) EXACTLY — one
// property per feature method, tagged with the exact property heading:
//
//   Property 25: Backoff bounds  (R4.5/R4.6/R4.7)
//     For any attempt and any Retry-After value, `dabv2BackoffIntervalMs`
//     never exceeds the 60 s cap, and the retry path's scheduled retry count
//     never exceeds `MAX_API_RETRY_ATTEMPTS`.
//     Validates: Requirements 4.5, 4.6, 4.7.
//
// STRICT TDD (Task 6.1): this spec encodes the REQUIRED behavior that Task 6.2
// must satisfy. It is split deliberately so the red-vs-green status is explicit:
//
//   GREEN now (pure helper already exists from Task 1.4):
//     • `dabv2BackoffIntervalMs(attempt, retryAfterMs)` is always in [0, 60000]
//       — Retry-After honored & clamped to 60 s; absent → exponential base 1 s
//       ×2, capped at 60 s (R4.5/R4.6).
//     • The retry-count bound itself (`retryCount < MAX_API_RETRY_ATTEMPTS`)
//       already exists in `getDataAsync`, so reaching the max schedules NO
//       further retry (R4.7).
//
//   RED until Task 6.2 wires bounded backoff into the retry path:
//     • The production throttle-retry path currently schedules with the FIXED
//       `API_CALL_DELAY_MS` (3000 ms), not `dabv2BackoffIntervalMs(...)`. 3000 ms
//       is never a valid bounded-backoff output, so the assertion that the
//       scheduled delay equals the bounded backoff (and is ≤ 60 s) FAILS until
//       Task 6.2 replaces the fixed delay with
//       `runInMillis(dabv2BackoffIntervalMs(...), wrapper)`.
//
// The class name contains "Property" so `--tests '*Property*'` selects it; the
// `where:` block drives PropertyGen.ITERATIONS (100) reproducible scenarios.
//
// Run `./gradlew test --tests '*BackoffBounds*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification

class BackoffBoundsPropertySpec extends Specification {

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

  private static final long CAP_MS = 60000L
  private static final long BASE_MS = 1000L

  // One reusable sandbox script; the pure helper is stateless and the
  // throttle-retry path only reads atomicState and schedules via runInMillis.
  @Shared Object script
  // Captures every runInMillis(...) scheduled by the production retry path.
  @Shared List scheduled = []

  def setupSpec() {
    final log = new CapturingLog()
    // Stable atomicState held at the concurrency cap so canMakeRequest() is
    // false and getDataAsync deterministically takes the throttle-retry branch
    // (the concurrency gate itself is a separate concern, R4.8); we assert how
    // the retry is SCHEDULED, not whether throttling engages.
    Map state = [flairAccessToken: 'test-token']
    Map atomic = [activeRequests: 8]   // 8 == MAX_CONCURRENT_REQUESTS
    AppExecutor executorApi = Mock {
      _ * getState() >> state
      _ * getAtomicState() >> atomic
      _ * getLog() >> log
      // Capture scheduling at the platform boundary. The production retry path
      // calls runInMillis with a primitive `long` delay (from dabv2BackoffIntervalMs);
      // intercepting on the executor API (rather than via a script metaClass
      // override) reliably captures the call regardless of the delay's runtime type.
      _ * runInMillis(_, _, _) >> { d, String handler, Map opts ->
        scheduled << [ms: d, handler: handler]
      }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': [debugLevel: 1])
    // Bind the backing maps directly on the script so the app's state/atomicState
    // reads reflect the cap (mirrors the dabv2-dispatch harness); without this the
    // in-flight counter reads empty and canMakeRequest() never engages the throttle
    // branch we are asserting on.
    script.state = state
    script.atomicState = atomic
  }

  // Reference value: what the documented bounded backoff MUST produce.
  private static long expectedBackoff(int attempt, Long retryAfterMs) {
    if (retryAfterMs != null) { return Math.min(retryAfterMs, CAP_MS) }
    int exp = attempt < 0 ? 0 : attempt
    double scaled = (double) BASE_MS * Math.pow(2.0d, (double) exp)
    return (scaled >= (double) CAP_MS) ? CAP_MS : Math.min((long) scaled, CAP_MS)
  }

  // ---------------------------------------------------------------------------
  // Property 25 — bounded backoff never exceeds 60 s; retry count bounded.
  // ---------------------------------------------------------------------------
  def 'Feature: hubitat-flair-vents-dab-v2, Property 25: backoff bounds — never exceeds the 60 s cap and the retry count never exceeds MAX_API_RETRY_ATTEMPTS'() {
    given: 'a randomized attempt and a present-or-absent Retry-After value'
    def gen = PropertyGen.forIteration(i)
    int attempt = gen.nextInt(0, 12)
    boolean hasRetryAfter = gen.nextBoolean()
    // Retry-After spans below and far above the 60 s cap so clamping is exercised.
    Long retryAfterMs = hasRetryAfter ? (gen.nextInt(0, 180) * 1000L) : null

    int maxAttempts = script.MAX_API_RETRY_ATTEMPTS
    int retryCount = gen.nextInt(0, maxAttempts)   // spans 0..MAX (inclusive)

    when: 'the production throttle-retry path is driven'
    scheduled.clear()
    script.getDataAsync('https://api.flair.co/api/test', 'noOpHandler', null, retryCount)

    then: 'GREEN: the pure backoff helper never exceeds the 60 s cap for any input (R4.5/R4.6)'
    long backoff = script.dabv2BackoffIntervalMs(attempt, retryAfterMs)
    backoff >= 0L
    backoff <= CAP_MS

    and: 'GREEN: Retry-After honored+clamped to 60 s; absent → exponential base 1 s ×2 capped (R4.4/R4.5/R4.6)'
    backoff == expectedBackoff(attempt, retryAfterMs)

    and: 'GREEN: the retry count is bounded — at MAX_API_RETRY_ATTEMPTS no further retry is scheduled (R4.7)'
    retryCount < maxAttempts || scheduled.isEmpty()

    and: 'RED until 6.2: below the bound exactly one retry is scheduled using bounded backoff, never the fixed delay, ≤ 60 s (R4.5/R4.6/R4.7)'
    retryCount >= maxAttempts || (
      scheduled.size() == 1
      && scheduled[0].ms <= CAP_MS
      && scheduled[0].ms == script.dabv2BackoffIntervalMs(retryCount, null)
    )

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }
}
