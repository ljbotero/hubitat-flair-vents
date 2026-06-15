
// R4 throttling: HTTP 429 on the data path vs. the auth path (Task 6.3; R4.1/R4.2/R4.3).
//
// FAILING example specs (strict TDD). These encode the REQUIRED behavior that
// Task 6.4 ("Implement generalized 429 handling on both paths") must satisfy.
// They are RED against the current code, which handles 429 only on the auth
// path as a terminal error and never reschedules a retry:
//
//   Data path (R4.1): a 429 received by a data-path callback
//     (e.g. handleDeviceList) must be treated as a TRANSIENT throttle and must
//     schedule a bounded retry of the affected resource — NOT surfaced as an
//     unrecoverable error. Today isValidResponse(429) drops the response into the
//     generic error branch and the callback simply `return`s: nothing is
//     rescheduled, so the affected resource is never re-fetched. RED.
//
//   Auth path (R4.2/R4.3): a 429 received by handleAuthResponse must retry via
//     the existing auth-retry path (a scheduled re-auth/retry wrapper) AND must
//     NOT clear a still-valid token. Today handleAuthResponse(429) only sets
//     state.authError and returns: no retry is scheduled. RED on the
//     retry-scheduled assertion. (Note: the retry must NOT be the token-clearing
//     autoReauthenticate path — R4.3 requires the valid token be preserved.)
//
// Scheduling is captured at the executor (platform) boundary — runInMillis /
// runIn — exactly like dabv2-dispatch-tests / backoff-bounds-property-tests, so
// the bounded delay's runtime type does not matter. Immediate re-auth HTTP
// (asynchttpPost) and data re-fetch (asynchttpGet) are captured via the script
// metaClass so the specs are robust to whichever retry mechanism Task 6.4 picks.
//
// Run `./gradlew test --tests '*Throttle429*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class Throttle429DataVsAuthPathsSpec extends Specification {

  private static final String TOKEN = 'still-valid-flair-token-xyz789'
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

  private CapturingLog log
  private Map stateMap
  private Map atomicStateMap
  // Scheduling captured at the executor boundary.
  private List scheduledMillis = []
  private List scheduledRunIn = []
  // Immediate (unscheduled) retry attempts captured via the script metaClass.
  private List asyncGets = []
  private List asyncPosts = []

  // A minimal fake HTTP response with an error status, mirroring the
  // hasProperty/hasError/getStatus shape used by isValidResponse and the
  // existing api-communication-tests mocks.
  private Object errorResponse(int status, Map headers = [:]) {
    return [
      hasProperty: { String p -> p == 'hasError' },
      hasError: { -> true },
      getStatus: { -> status },
      getErrorMessage: { -> 'Too Many Requests' },
      getHeaders: { -> headers },
      getJson: { -> null },
      getData: { -> null }
    ]
  }

  private Object buildScript(Map state = [:], Map atomic = [activeRequests: 1]) {
    log = new CapturingLog()
    stateMap = state
    atomicStateMap = atomic
    scheduledMillis = []
    scheduledRunIn = []
    asyncGets = []
    asyncPosts = []
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> atomicStateMap
      _ * getLog() >> log
      _ * runInMillis(_, _, _) >> { d, String handler, Map opts -> scheduledMillis << [ms: d, handler: handler, opts: opts] }
      _ * runIn(_, _) >> { d, String handler -> scheduledRunIn << [s: d, handler: handler] }
      _ * runIn(_, _, _) >> { d, String handler, Map opts -> scheduledRunIn << [s: d, handler: handler, opts: opts] }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': [debugLevel: 1, clientId: 'cid', clientSecret: 'secret'])
    script.state = stateMap
    script.atomicState = atomicStateMap
    // Capture immediate (non-scheduled) retry attempts so the specs pass under
    // whichever retry mechanism Task 6.4 chooses (scheduled wrapper or direct call).
    script.metaClass.asynchttpGet = { Object... a -> asyncGets << a }
    script.metaClass.asynchttpPost = { Object... a -> asyncPosts << a }
    return script
  }

  // True iff the production code arranged for ANOTHER attempt of the request
  // (scheduled via runInMillis/runIn, or fired immediately via async HTTP).
  private boolean retryWasArranged() {
    return !scheduledMillis.isEmpty() || !scheduledRunIn.isEmpty() ||
           !asyncGets.isEmpty() || !asyncPosts.isEmpty()
  }

  private boolean allScheduledDelaysBounded() {
    return scheduledMillis.every { (it.ms as long) >= 0L && (it.ms as long) <= CAP_MS }
  }

  // ---------------------------------------------------------------------------
  // R4.1 — Data path: 429 is transient and schedules a bounded retry.
  // ---------------------------------------------------------------------------
  def "data-path 429 is treated as transient and schedules a bounded retry of the affected resource"() {
    setup: 'a valid token is held and a data-path request is in flight'
    def script = buildScript([flairAccessToken: TOKEN], [activeRequests: 1])
    def resp429 = errorResponse(429)
    // The affected resource (the vents endpoint) plus the retry context a
    // bounded retry needs to re-fetch it.
    def data = [deviceType: 'vents',
                uri: 'https://api.flair.co/api/structures/SID/vents',
                callback: 'handleDeviceList',
                retryCount: 0]

    when: 'the data-path callback receives the 429'
    script.handleDeviceList(resp429, data)

    then: 'the throttle is transient — a retry of the affected resource is arranged'
    retryWasArranged()

    and: 'any scheduled retry uses a bounded backoff (never exceeding the 60 s cap)'
    allScheduledDelaysBounded()

    and: 'the 429 is NOT surfaced as an unrecoverable auth failure (token preserved, no re-auth/token clear)'
    script.state.flairAccessToken == TOKEN
    scheduledRunIn.every { it.handler != 'autoReauthenticate' }
  }

  // ---------------------------------------------------------------------------
  // R4.2/R4.3 — Auth path: 429 retries via the auth-retry path and preserves a
  // still-valid token.
  // ---------------------------------------------------------------------------
  def "auth-path 429 retries via the existing auth-retry path and never clears a still-valid token"() {
    setup: 'a still-valid token is held when the auth endpoint throttles'
    def script = buildScript([flairAccessToken: TOKEN, authInProgress: true], [activeRequests: 1])
    def resp429 = errorResponse(429)

    when: 'the auth-path callback receives the 429'
    script.handleAuthResponse(resp429, null)

    then: 'the still-valid token is preserved (R4.3)'
    script.state.flairAccessToken == TOKEN

    and: 'a re-authentication retry is arranged via the auth-retry path (R4.2)'
    retryWasArranged()

    and: 'the retry does NOT take the token-clearing autoReauthenticate path (R4.3)'
    scheduledRunIn.every { it.handler != 'autoReauthenticate' }

    and: 'any scheduled retry uses a bounded backoff (never exceeding the 60 s cap)'
    allScheduledDelaysBounded()
  }
}
