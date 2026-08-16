/* groovylint-disable MethodName */

// Scheduler-safe retry data + retry-job independence (forum #382).
//
// Root cause pinned here: Hubitat serializes runIn/runInMillis `data:` maps to
// JSON and deserializes them when the job fires. The throttle-deferral branch of
// getDataAsync (and the 429 path in handleDataPathThrottle, and patchDataAsync)
// placed the LIVE device wrapper into scheduler data for every non-/room URI, so
// the deferred retry delivered a groovy.json.internal.LazyMap as `data.device`
// and handlePuckReadingGet crashed with
//   MissingMethodException: sendEvent() ... (groovy.json.internal.LazyMap, LinkedHashMap)
// (bundle line 5102 in 0.237). On top of that, every deferral scheduled
// 'retryGetDataAsyncWrapper' with the DEFAULT overwrite:true, so concurrent
// deferrals from one poll burst clobbered each other's retry job and all but the
// last deferred request were silently dropped — which is how a newly added
// Puck 2's discovery GET could vanish while existing devices kept working.
//
// Pinned contract:
//   * sanitizeRetryData: every deferral stores `deviceId` (device network id),
//     never a live device object (AGENTS: "Store IDs, never device objects").
//   * All retry scheduling uses overwrite:false so deferred jobs coexist.
//   * rehydrateRetryData: the retry wrapper looks the child back up by id and
//     hands the LIVE wrapper to getDataAsync/patchDataAsync; a deleted child
//     drops the retry with a logged error instead of crashing downstream.
//   * End to end: a deferred puck-reading GET whose scheduler data went through
//     a REAL JSON round-trip still sendEvent()s against the live child wrapper.
//   * handleRoomsWithPucks: the room-relationships puck path works again (its
//     respJson reference was try-scoped and threw MissingPropertyException on
//     every call, silently disabling one of the four puck discovery sources).
//
// Run `./gradlew test --tests '*SchedulerSafeRetryData*'`.

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class SchedulerSafeRetryDataTest extends Specification {

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

  CapturingLog log
  Map children
  Map addCounts
  Map stateMap       // executor-backed state (stable across calls)
  Map atomicStateMap // executor-backed atomicState (throttle-spec pattern)
  List scheduled     // captured runInMillis: [handler, opts]
  List httpGets      // captured asynchttpGet: [callback, params, data]
  List httpPatches   // captured asynchttpPatch: [callback, params, data]
  List events        // captured sendEvent(device, map) pairs (either boundary)
  def script
  def liveChild

  def setup() {
    log = new CapturingLog()
    children = [:]
    addCounts = [:]
    // Saturated throttle (8/8, MAX_CONCURRENT_REQUESTS) with no progress
    // timestamp: initRequestTracking never resets it, so getDataAsync defers.
    atomicStateMap = [activeRequests: 8]
    stateMap = [:]
    scheduled = []
    httpGets = []
    httpPatches = []
    events = []
    liveChild = [getDeviceNetworkId: { -> 'puck-42' }, getId: { -> 'puck-42' },
                 hasAttribute      : { String a -> false }] as ChildDeviceWrapper
    children['puck-42'] = liveChild
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> { stateMap }
      _ * getAtomicState() >> { atomicStateMap }
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      // Constant clock: getInstanceId() falls back to "test-${now()}" in the
      // harness, so a moving clock would give every call its own cache id.
      _ * now() >> 1_000_000L
      _ * getChildDevice(_) >> { String id -> children[id] }
      _ * addChildDevice(*_) >> { args ->
        String id = args[2]?.toString()
        addCounts[id] = (addCounts[id] ?: 0) + 1
        def child = [getDeviceNetworkId: { -> id }, getId: { -> id }] as ChildDeviceWrapper
        children[id] = child
        return child
      }
      _ * runInMillis(_, _, _) >> { Long d, String handler, Map opts -> scheduled << [handler, opts] }
      _ * asynchttpGet(_, _, _) >> { String cb, Map params, cbData -> httpGets << [cb, params, cbData] }
      _ * asynchttpPatch(_, _, _) >> { String cb, Map params, cbData -> httpPatches << [cb, params, cbData] }
      // sendEvent(device, map) dispatches to the executor under hubitat_ci;
      // capture the (device, event) pair at that boundary.
      _ * sendEvent(*_) >> { args -> events << [args[0], args[1]] }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
  }

  // Simulate Hubitat's scheduler persistence: data maps are serialized to JSON
  // and handed back deserialized when the job fires.
  private static Object schedulerRoundTrip(Object data) {
    return new JsonSlurper().parseText(JsonOutput.toJson(data))
  }

  private static Map okResponse(Map json) {
    return [
      hasProperty: { String p -> false },
      getStatus  : { -> 200 },
      getJson    : { -> json },
    ]
  }

  // ===========================================================================
  // Sanitize on deferral: deviceId in, live device out; overwrite:false
  // ===========================================================================

  def 'a throttle-deferred GET stores the device NETWORK ID, never the device object, and never clobbers sibling retries'() {
    when: 'a puck-reading GET is submitted while the throttle is saturated'
    script.getDataAsync('https://api.flair.co/api/pucks/42/current-reading',
        'handlePuckReadingGetWithCache', [device: liveChild, cacheKey: 'pucks_reading_42'])

    then: 'the retry job is scheduled with JSON-safe data'
    scheduled.size() == 1
    scheduled[0][0] == 'retryGetDataAsyncWrapper'
    Map opts = scheduled[0][1]
    opts.overwrite == false
    opts.data.data.deviceId == 'puck-42'
    opts.data.data.device == null

    and: 'the rest of the payload (cacheKey) survives untouched'
    opts.data.data.cacheKey == 'pucks_reading_42'
    opts.data.retryCount == 1
  }

  def 'two concurrent deferrals each keep their own retry job'() {
    when: 'two different GETs defer inside the same burst'
    script.getDataAsync('https://api.flair.co/api/pucks/42/current-reading',
        'handlePuckReadingGetWithCache', [device: liveChild, cacheKey: 'a'])
    script.getDataAsync('https://api.flair.co/api/structures/1/pucks',
        'handleDeviceList', [deviceType: 'pucks'])

    then: 'both retries are scheduled independently (overwrite:false on each)'
    scheduled.size() == 2
    scheduled.every { it[0] == 'retryGetDataAsyncWrapper' && it[1].overwrite == false }
    scheduled.collect { it[1].data.uri } as Set ==
        ['https://api.flair.co/api/pucks/42/current-reading',
         'https://api.flair.co/api/structures/1/pucks'] as Set
  }

  def 'a 429 data-path retry is sanitized and scheduled with overwrite:false'() {
    given: 'a well-formed 429 response'
    def throttled = [hasError: { -> true }, getStatus: { -> 429 }]

    when:
    boolean handled = script.handleDataPathThrottle(throttled,
        [uri: 'https://api.flair.co/api/pucks/42/current-reading',
         callback: 'handlePuckReadingGetWithCache',
         data: [device: liveChild, cacheKey: 'pucks_reading_42'], retryCount: 0])

    then: 'the 429 is absorbed and the scheduled retry carries no device object'
    handled
    scheduled.size() == 1
    Map opts = scheduled[0][1]
    opts.overwrite == false
    opts.data.data.deviceId == 'puck-42'
    opts.data.data.device == null
  }

  def 'a throttle-deferred PATCH is sanitized and rehydrated the same way'() {
    when: 'a vent PATCH defers at 8/8'
    script.patchDataAsync('https://api.flair.co/api/vents/42', 'handleVentPatch',
        [data: [type: 'vents']], [device: liveChild])

    then:
    scheduled.size() == 1
    scheduled[0][0] == 'retryPatchDataAsyncWrapper'
    Map opts = scheduled[0][1]
    opts.overwrite == false
    opts.data.data.deviceId == 'puck-42'
    opts.data.data.device == null

    when: 'the job fires after the scheduler JSON round-trip with slots free'
    atomicStateMap.activeRequests = 0
    script.retryPatchDataAsyncWrapper(schedulerRoundTrip(opts.data))

    then: 'the PATCH goes out with the LIVE child wrapper in its callback data'
    httpPatches.size() == 1
    httpPatches[0][2].device.is(liveChild)
  }

  // ===========================================================================
  // End to end: deferred puck reading survives the JSON round-trip (forum #382)
  // ===========================================================================

  def 'a deferred puck-reading GET sendEvents against the LIVE child after the scheduler round-trip'() {

    when: 'the GET defers at 8/8, then its retry fires after a REAL JSON round-trip'
    script.getDataAsync('https://api.flair.co/api/pucks/42/current-reading',
        'handlePuckReadingGetWithCache', [device: liveChild, cacheKey: 'pucks_reading_42'])
    def roundTripped = schedulerRoundTrip(scheduled[0][1].data)
    atomicStateMap.activeRequests = 0
    script.retryGetDataAsyncWrapper(roundTripped)

    then: 'the retried GET went out with the rehydrated live wrapper'
    httpGets.size() == 1
    httpGets[0][0] == 'handlePuckReadingGetWithCache'
    httpGets[0][2].device.is(liveChild)

    when: 'the async callback processes a Puck 2 sensor reading'
    def reading = [data: [attributes: ['room-temperature-c': 20.0, 'humidity': 45]]]
    script."${httpGets[0][0]}"(okResponse(reading), httpGets[0][2])

    then: 'every emitted event targets the live child — a JSON round-tripped LazyMap has no getDeviceNetworkId() and would blow up here'
    !events.isEmpty()
    events.every { it[0].getDeviceNetworkId() == 'puck-42' }

    and: 'the temperature event is the converted reading'
    def temp = events.find { it[1].name == 'temperature' }
    temp != null
    (temp[1].value as double) == 68.0d
  }

  def 'a retry for a deleted child is dropped with a logged error instead of crashing downstream'() {
    when: 'the deferred retry fires but the child no longer exists'
    script.getDataAsync('https://api.flair.co/api/pucks/ghost/current-reading',
        'handlePuckReadingGetWithCache', [device: liveChild, cacheKey: 'k'])
    def roundTripped = schedulerRoundTrip(scheduled[0][1].data)
    children.clear()
    atomicStateMap.activeRequests = 0
    script.retryGetDataAsyncWrapper(roundTripped)

    then: 'no HTTP request is issued and the drop is logged'
    httpGets.isEmpty()
    log.records.any { it[1]?.toString()?.contains('no longer exists') }
  }

  // ===========================================================================
  // handleRoomsWithPucks: room-relationships path repaired (respJson scope)
  // ===========================================================================

  def 'a puck referenced only in room relationships is onboarded (previously dead respJson scope path)'() {
    given: 'a rooms payload whose only puck signal is a relationships reference'
    def resp = okResponse([data: [
      [id: 'room-1', attributes: [name: 'Den'],
       relationships: [pucks: [data: [[id: 'puck2-rel', type: 'pucks']]]]],
    ]])

    when:
    script.handleRoomsWithPucks(resp, null)

    then: 'the referenced puck is onboarded exactly once'
    children.containsKey('puck2-rel')
    addCounts['puck2-rel'] == 1
  }
}
