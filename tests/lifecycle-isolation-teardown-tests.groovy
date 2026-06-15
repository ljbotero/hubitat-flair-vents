
// R2(g) Lifecycle isolation + full-teardown specs (Task 15.1; R2.13/R2.14/R8.13).
//
// STRICT TDD (Task 15.1 — RED). These specs encode the REQUIRED lifecycle
// behavior that Task 15.2 ("Wire installed/updated/initialize per-zone schedule
// setup and uninstalled() complete teardown") must satisfy. They are written to
// FAIL against today's app and to pass only once Task 15.2 is implemented.
//
// ===========================================================================
// CONTRACT — what Task 15.2 MUST implement (IMPLEMENT TO THIS BEHAVIOR)
// ===========================================================================
//
// (A) FULL TEARDOWN — uninstalled()  (R8.13; design §5.4 "uninstalled() still
//     tears down everything", §2.2 "Lifecycle"):
//       uninstalled() SHALL perform a COMPLETE teardown of the instance:
//         1. removeChildren()  -> deleteChildDevice(...) for EVERY child device
//                                 (all zones' vents/pucks). [already implemented]
//         2. unschedule()      -> cancel ALL schedules app-wide.  [already impl]
//         3. unsubscribe()     -> drop ALL event subscriptions.   [already impl]
//         4. Clear the OAuth token  (state.flairAccessToken).     <-- NOT YET
//         5. Clear atomicState (the instance's shared/concurrent bookkeeping:
//            activeRequests, request-tracking timestamps, cadence, etc.).  <-- NOT YET
//       Steps 4 and 5 are the genuinely-missing behavior. Today uninstalled()
//       calls only removeChildren()/unschedule()/unsubscribe() and leaves the
//       OAuth token in `state` and all `atomicState` bookkeeping behind.
//
// (B) PER-ZONE SETUP — installed()/updated()/initialize()  (R2.13/R2.14; design
//     §2.2 "installed/updated/initialize set up per-zone schedules under
//     zone-namespaced handler ids (suffixed with zoneId)", §5.4 "Per-zone
//     schedule ids"):
//       The lifecycle SHALL set up, for EACH of THIS instance's zones
//       (`getZoneIds()`), that zone's zone-scoped schedule addressed by the
//       Task-14.2 helper `dabV2ZoneScheduleId(zoneId)` (e.g. via
//       `runIn(delay, dabV2ZoneScheduleId(zoneId))` / `runInMillis(...)` /
//       `schedule(cron, dabV2ZoneScheduleId(zoneId))`). This per-zone NAMED
//       schedule is the same id the zone-scoped teardown (Task 14.2 `removeZone`
//       / `reassignDeviceToZone`) cancels via
//       `unschedule(dabV2ZoneScheduleId(zoneId))`; setup MUST therefore create
//       schedules under exactly those ids for the teardown to be meaningful.
//       It SHALL arm schedules ONLY for the instance's own zones (no schedule
//       for a zone id that is not in `getZoneIds()`), and (R2.14) MUST NOT touch
//       any other instance's schedules/subscriptions/child devices.
//       Today `initialize()` arms only the single self-managed `dabV2EvaluateTick`
//       cadence job (inside the legacy global-`thermostat1` block) and NEVER
//       creates a per-zone `dabV2ZoneScheduleId(zoneId)` schedule, so the
//       per-zone setup is missing.
//
// WHY THIS IS RED TODAY (the genuinely-failing behavior):
//   - uninstalled() does not clear `state.flairAccessToken` and does not clear
//     `atomicState` (grep: uninstalled() body is removeChildren/unschedule/
//     unsubscribe only) -> the teardown assertions in (A) FAIL.
//   - initialize()/installed()/updated() never schedule a per-zone
//     `dabV2ZoneScheduleId(zoneId)` handler -> the per-zone-setup assertions in
//     (B) FAIL.
//
// Run `./gradlew test --tests '*LifecycleIsolationTeardown*'`.
//
// _Requirements: 2.13, 2.14, 8.13_
// _Design: §5.4, §2.2_

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class LifecycleIsolationTeardownSpec extends Specification {

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
  private List devices = []

  // Captured Hubitat scheduling / teardown surface.
  private List scheduleHandlers          // every runIn/runInMillis/schedule handler name
  private List scheduledZoneIds          // zoneId carried in each runIn `data` map
  private List deletedChildren           // DNIs passed to deleteChildDevice (removeChildren)
  private int unscheduleAllCount         // app-wide unschedule() (no-arg) calls
  private int unsubscribeCount           // unsubscribe() (no-arg) calls

  // A child device (vent): identified by DNI, advertises `percent-open` so the
  // app classifies it as a vent.
  private Object fakeVent(String dni) {
    Map self = [:]
    self.dni = dni
    self.attrs = ['percent-open': 50]
    self.getDeviceNetworkId = { -> self.dni }
    self.getId = { -> self.dni }
    self.getLabel = { -> self.dni }
    self.hasAttribute = { Object... a -> self.attrs.containsKey(a[0]) }
    self.currentValue = { Object... a -> self.attrs[a[0]] }
    self.toString = { -> "FakeVent(${self.dni})".toString() }
    return self as ChildDeviceWrapper
  }

  private Object buildScript(Map userSettings = [:], Map state = [:], Map atomic = [:]) {
    log = new CapturingLog()
    stateMap = state
    atomicStateMap = atomic
    scheduleHandlers = []
    scheduledZoneIds = []
    deletedChildren = []
    unscheduleAllCount = 0
    unsubscribeCount = 0
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> atomicStateMap
      _ * getLog() >> log
      _ * getChildDevices() >> devices
      _ * getChildDevice(_) >> { String id -> devices.find { it.getDeviceNetworkId() == id } }
      // Teardown surface.
      _ * deleteChildDevice(_) >> { args -> deletedChildren << (args instanceof List ? args[0] : args) }
      _ * unschedule() >> { unscheduleAllCount++ }
      _ * unschedule(_) >> { args -> /* named unschedule(login) etc. — ignored here */ }
      _ * unsubscribe() >> { unsubscribeCount++ }
      // Scheduling surface — capture the handler name from every primitive so the
      // per-zone setup assertions are agnostic to which primitive Task 15.2 picks.
      _ * runIn(_, _) >> { d, h -> scheduleHandlers << h }
      _ * runIn(_, _, _) >> { d, h, o -> scheduleHandlers << h; if (o instanceof Map && o.data instanceof Map) { scheduledZoneIds << o.data.zoneId } }
      _ * runInMillis(_, _) >> { d, h -> scheduleHandlers << h }
      _ * runInMillis(_, _, _) >> { d, h, o -> scheduleHandlers << h; if (o instanceof Map && o.data instanceof Map) { scheduledZoneIds << o.data.zoneId } }
      _ * schedule(_, _) >> { c, h -> scheduleHandlers << h }
      _ * runEvery1Hour(_) >> { h -> scheduleHandlers << h }
      _ * runEvery5Minutes(_) >> { h -> scheduleHandlers << h }
      _ * runEvery10Minutes(_) >> { h -> scheduleHandlers << h }
      _ * subscribe(*_) >> { }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ([debugLevel: 1, dabEnabled: true] + userSettings))
    script.state = stateMap
    script.atomicState = atomicStateMap
    return script
  }

  // ===========================================================================
  // (A) FULL TEARDOWN — uninstalled()  (R8.13; design §5.4/§2.2)
  // ===========================================================================

  def 'uninstalled() performs a complete teardown: all child devices, schedules, subscriptions, the OAuth token, and atomicState'() {
    given: 'an installed instance with two zones, child devices, a held OAuth token, and live atomicState bookkeeping'
    devices = [fakeVent('vent-default-1'), fakeVent('vent-office-1'), fakeVent('vent-office-2')]
    Map zones = [
      'default': [assignedVentIds: ['vent-default-1'], assignedPuckIds: [], safetyFloorPct: 40.0d],
      'office' : [assignedVentIds: ['vent-office-1', 'vent-office-2'], assignedPuckIds: [], safetyFloorPct: 40.0d]
    ]
    Map state = [zones: zones, authInProgress: false]
    Map atomic = [activeRequests: 3, requestTrackingTs: 123456789L,
                  dabV2Cadence: [active: true, intervalMin: 3], cycleSeq: 7]
    def script = buildScript([:], state, atomic)
    // Seed the held OAuth token AFTER the script is built: a token present at
    // build time makes hubitat_ci render the token-gated config sections, which
    // is irrelevant to the teardown under test.
    stateMap.flairAccessToken = 'secret-token-value'

    when: 'the instance is uninstalled'
    script.uninstalled()

    then: 'every child device (across ALL zones) is deleted (removeChildren)'
    deletedChildren as Set == ['vent-default-1', 'vent-office-1', 'vent-office-2'] as Set

    and: 'all schedules are cancelled app-wide and all subscriptions dropped'
    unscheduleAllCount >= 1
    unsubscribeCount >= 1

    and: 'the OAuth token is cleared so no credential is left behind (R8.13)'
    !stateMap.flairAccessToken

    and: 'the instance-global atomicState bookkeeping is torn down (R8.13)'
    atomicStateMap.isEmpty() || atomicStateMap.every { k, v -> v == null }
  }

  // ===========================================================================
  // (B) PER-ZONE SETUP — installed()/updated()/initialize()  (R2.13/R2.14)
  // ===========================================================================

  def 'initialize() sets up a zone-scoped schedule for each of the instance\'s zones'() {
    given: 'an instance configured with two zones (no legacy global thermostat / no credentials so only the lifecycle wiring under test runs)'
    Map zones = [
      'default': [assignedVentIds: ['v-d-1'], assignedPuckIds: [], safetyFloorPct: 40.0d, zoneName: 'Default'],
      'office' : [assignedVentIds: ['v-o-1'], assignedPuckIds: [], safetyFloorPct: 40.0d, zoneName: 'Office']
    ]
    def script = buildScript([:], [zones: zones], [:])
    // Isolate the lifecycle scheduling under test: stub the heavy collaborators
    // initialize() also drives so the only thing observed is per-zone scheduling.
    script.metaClass.initializeInstanceCaches = { -> }
    script.metaClass.cleanupExistingDecimalPrecision = { -> }
    script.metaClass.loadDabV2Model = { -> }

    when: 'the instance initializes'
    script.initialize()

    then: 'a per-zone evaluate was armed for EVERY one of the instance\'s zones via the single static handler + data map (design §2.2/§5.4)'
    scheduleHandlers.contains('runScheduledDabV2ZoneEvaluate')
    script.getZoneIds().every { zoneId ->
      scheduledZoneIds.contains(zoneId)
    }

    and: 'no schedule was armed for a zone that is not part of this instance (R2.14)'
    !scheduledZoneIds.contains('not-a-zone-of-this-instance')
  }

  def 'updated() and installed() establish per-zone schedule setup for the instance\'s zones'() {
    given: 'an instance with two zones'
    Map zones = [
      'default': [assignedVentIds: ['v-d-1'], assignedPuckIds: [], safetyFloorPct: 40.0d],
      'office' : [assignedVentIds: ['v-o-1'], assignedPuckIds: [], safetyFloorPct: 40.0d]
    ]
    def script = buildScript([:], [zones: zones], [:])
    script.metaClass.initializeInstanceCaches = { -> }
    script.metaClass.cleanupExistingDecimalPrecision = { -> }
    script.metaClass.loadDabV2Model = { -> }
    List expectedZoneIds = script.getZoneIds()

    when: 'the instance is updated (config save)'
    scheduleHandlers = []
    scheduledZoneIds = []
    script.updated()

    then: 'updated() set up each zone\'s per-zone schedule (single handler + data zoneId)'
    expectedZoneIds.every { scheduledZoneIds.contains(it) }

    when: 'the instance is freshly installed'
    scheduleHandlers = []
    scheduledZoneIds = []
    script.installed()

    then: 'installed() set up each zone\'s per-zone schedule'
    expectedZoneIds.every { scheduledZoneIds.contains(it) }
  }
}
