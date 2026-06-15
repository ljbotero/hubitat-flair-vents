
// DAB v2 vent-dispatch fake-backed tests (Task 9.3; R6.11, R10.4, R15.1/15.2/
// R15.3, R21.3/21.4, R14.4).
//
// App-orchestration (non-pure) dispatch behavior exercised against lightweight
// fakes of the Hubitat surface (child devices, state/atomicState, async HTTP).
// Covers:
//   - group-level cooldown/anti-chatter: ONE decision per room-group governs all
//     its vents (R15.2); a floor-required open bypasses the cooldown (R6.2/R10.5);
//   - per-cycle batch limit caps ordinary moves while floor-required moves are
//     never throttled (R10.4 + R6.2);
//   - on a Flair command failure, a floor-required OPEN is retried toward
//     more-open (fail-open, R6.11);
//   - a commanded-but-unconfirmed CLOSE is NOT recorded as confirmed airflow, so
//     it is not treated as reduced airflow in the next allocation (R6.11);
//   - rate-limit compliance: dispatch routes through the concurrency throttle and
//     does NOT fire an HTTP request when the in-flight cap is reached (R21.3/21.4);
//   - credentials are never logged (R14.4).
//
// Run `./gradlew test --tests '*Dabv2Dispatch*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class Dabv2DispatchTest extends Specification {

  private static final String TOKEN = 'super-secret-flair-token-abc123'
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

  private CapturingLog log
  private Map stateMap
  private Map atomicStateMap
  private Map<String, Object> devices
  // Captures runInMillis(...) scheduled by the production retry path. Captured at
  // the executor boundary (not via a script metaClass override) so the call is
  // intercepted regardless of the delay argument's runtime type (the bounded
  // backoff path passes a primitive `long`).
  private List scheduledMillis = []

  // A minimal fake vent: tracks its confirmed `percent-open` plus sendEvent calls.
  // Coerced to ChildDeviceWrapper so the sandbox's getChildDevice cast succeeds.
  private Object fakeVent(String dni, int percentOpen) {
    Map self = [:]
    self.dni = dni
    self.percentOpen = percentOpen
    self.getDeviceNetworkId = { -> self.dni }
    self.getLabel = { -> "Vent ${self.dni}".toString() }
    self.getId = { -> self.dni }
    self.hasAttribute = { Object... a -> a[0] == 'percent-open' }
    self.currentValue = { Object... a -> a[0] == 'percent-open' ? self.percentOpen : null }
    self.toString = { -> "FakeVent(${self.dni})".toString() }
    return self as ChildDeviceWrapper
  }

  private Object buildScript(Map userSettings = [:], Map state = [:], Map atomic = [:]) {
    log = new CapturingLog()
    stateMap = ([flairAccessToken: TOKEN] + state)
    atomicStateMap = atomic
    scheduledMillis = []
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> atomicStateMap
      _ * getLog() >> log
      _ * getChildDevice(_) >> { String id -> devices[id] }
      _ * runInMillis(_, _, _) >> { d, String handler, Map opts ->
        scheduledMillis << [ms: d, handler: handler]
      }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ([debugLevel: 1, safetyFloorPct: 40] + userSettings))
    script.state = stateMap
    script.atomicState = atomicStateMap
    return script
  }

  // ---------------------------------------------------------------------------
  // Group-level cooldown / anti-chatter (R15.2, R10.4)
  // ---------------------------------------------------------------------------

  def "group cooldown holds an ordinary move and applies it to no vent in the group"() {
    setup:
    devices = ['g#v0': fakeVent('g#v0', 50), 'g#v1': fakeVent('g#v1', 50)]
    def script = buildScript()
    // Capture dispatched (ventId,target) pairs without real HTTP.
    def dispatched = []
    script.metaClass.patchVent = { dev, pct, Map extra = [:] -> dispatched << [dev.getDeviceNetworkId(), pct] }
    // A move recorded 1s ago for this group => still inside the 3-min cooldown.
    Long now = 10_000_000L
    script.metaClass.now = { -> now }
    script.atomicState.lastGroupMoveMs = ['room1': now - 1000L]
    def rooms = [[roomId: 'room1', ventIds: ['g#v0', 'g#v1'], currentOpen: 50.0d]]
    def zoneResult = [balancing: true, targets: ['room1': 70.0d], floorRequiredRooms: ([] as Set)]

    when:
    script.dispatchDabV2Targets(zoneResult, rooms)

    then: 'the whole group is held — neither vent is commanded'
    dispatched.isEmpty()
  }

  def "a floor-required open bypasses the group cooldown and moves every vent in the group"() {
    setup:
    devices = ['g#v0': fakeVent('g#v0', 30), 'g#v1': fakeVent('g#v1', 30)]
    def script = buildScript()
    def dispatched = []
    script.metaClass.patchVent = { dev, pct, Map extra = [:] -> dispatched << [dev.getDeviceNetworkId(), pct, extra.floorRequired] }
    Long now = 10_000_000L
    script.metaClass.now = { -> now }
    script.atomicState.lastGroupMoveMs = ['room1': now - 1000L]   // inside cooldown
    def rooms = [[roomId: 'room1', ventIds: ['g#v0', 'g#v1'], currentOpen: 30.0d]]
    def zoneResult = [balancing: true, targets: ['room1': 80.0d], floorRequiredRooms: (['room1'] as Set)]

    when:
    script.dispatchDabV2Targets(zoneResult, rooms)

    then: 'both grouped vents are commanded the identical target despite the cooldown'
    dispatched.size() == 2
    dispatched.collect { it[1] }.toSet() == [80] as Set
    dispatched.every { it[2] == true }   // flagged floor-required (fail-open aware)
  }

  // ---------------------------------------------------------------------------
  // Per-cycle batch limit (R10.4) — floor-required never throttled (R6.2)
  // ---------------------------------------------------------------------------

  def "batch limit caps ordinary group moves but never a floor-required move"() {
    setup:
    devices = ['a#v': fakeVent('a#v', 0), 'b#v': fakeVent('b#v', 0), 'c#v': fakeVent('c#v', 0)]
    def script = buildScript()
    def dispatched = []
    script.metaClass.patchVent = { dev, pct, Map extra = [:] -> dispatched << dev.getDeviceNetworkId() }
    Long now = 10_000_000L
    script.metaClass.now = { -> now }
    def rooms = [
      [roomId: 'a', ventIds: ['a#v'], currentOpen: 0.0d],
      [roomId: 'b', ventIds: ['b#v'], currentOpen: 0.0d],
      [roomId: 'c', ventIds: ['c#v'], currentOpen: 0.0d],
    ]
    // c is floor-required; a and b are ordinary moves; batch cap = 1 ordinary move.
    def zoneResult = [balancing: true,
                      targets: ['a': 40.0d, 'b': 60.0d, 'c': 90.0d],
                      floorRequiredRooms: (['c'] as Set)]

    when:
    script.dispatchDabV2Targets(zoneResult, rooms, [maxMovesPerCycle: 1])

    then: 'the floor-required move always dispatches'
    dispatched.contains('c#v')

    and: 'ordinary moves are capped to the batch limit (only one of a,b)'
    (dispatched.findAll { it == 'a#v' || it == 'b#v' }).size() == 1
  }

  // ---------------------------------------------------------------------------
  // Fail-open on command failure (R6.11)
  // ---------------------------------------------------------------------------

  def "a failed floor-required open is retried toward more-open"() {
    setup:
    devices = ['v0': fakeVent('v0', 20)]
    def script = buildScript()
    def retries = []
    script.metaClass.patchVentDevice = { dev, pct, Map extra = [:] -> retries << [dev.getDeviceNetworkId(), pct, extra] }
    // A timeout/unconfirmed result (null response) is the failure case (R6.11).
    def errResp = null

    when:
    script.handleVentPatch(errResp, [device: devices['v0'], targetOpen: 80, floorRequired: true])

    then: 'the floor-required open is re-issued (fail toward more-open)'
    retries.size() == 1
    retries[0][1] == 80
    retries[0][2].floorRequired == true
  }

  def "a failed (unconfirmed) close is NOT recorded as confirmed airflow"() {
    setup:
    def vent = fakeVent('v0', 70)
    devices = ['v0': vent]
    def script = buildScript()
    def events = []
    script.metaClass.safeSendEvent = { dev, Map ev -> events << ev }
    def errResp = null   // timeout / unconfirmed close

    when: 'a commanded close to 10% fails / is unconfirmed'
    script.handleVentPatch(errResp, [device: vent, targetOpen: 10, floorRequired: false])

    then: 'confirmed percent-open is never updated to the unconfirmed close value'
    events.findAll { it.name == 'percent-open' }.isEmpty()
    vent.currentValue('percent-open') == 70   // still the last CONFIRMED (more-open) value
  }

  // ---------------------------------------------------------------------------
  // Rate-limit compliance (R21.3/21.4)
  // ---------------------------------------------------------------------------

  def "dispatch does not fire an HTTP request when the in-flight cap is reached"() {
    setup:
    def vent = fakeVent('v0', 0)
    devices = ['v0': vent]
    def script = buildScript([:], [:], [activeRequests: 8])   // 8 == MAX_CONCURRENT_REQUESTS
    int httpCalls = 0
    script.metaClass.asynchttpPatch = { String cb, Map p, d -> httpCalls++ }

    when: 'a vent command is issued while at the concurrency cap'
    script.patchVent(vent, 80)

    then: 'no HTTP PATCH is fired; the request is deferred instead (no 429 storm)'
    httpCalls == 0
    scheduledMillis.size() == 1
  }

  // ---------------------------------------------------------------------------
  // Credentials are never logged (R14.4)
  // ---------------------------------------------------------------------------

  def "dispatch never logs Flair credentials"() {
    setup:
    devices = ['g#v0': fakeVent('g#v0', 0), 'g#v1': fakeVent('g#v1', 0)]
    def script = buildScript()
    script.metaClass.asynchttpPatch = { String cb, Map p, d -> }   // swallow real HTTP
    Long now = 10_000_000L
    script.metaClass.now = { -> now }
    def rooms = [[roomId: 'room1', ventIds: ['g#v0', 'g#v1'], currentOpen: 0.0d]]
    def zoneResult = [balancing: true, targets: ['room1': 80.0d], floorRequiredRooms: (['room1'] as Set)]

    when:
    script.dispatchDabV2Targets(zoneResult, rooms)

    then: 'no log record contains the access token or an Authorization/Bearer header'
    log.records.every { rec ->
      String msg = rec[1]?.toString() ?: ''
      !msg.contains(TOKEN) && !msg.contains('Bearer') && !msg.toLowerCase().contains('authorization')
    }
  }
}
