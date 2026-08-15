
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Regression specs for GitHub issue #7 ("New Beta Failing on Discovery").
 *
 * Pins the three code-level fixes:
 *
 *   1. Per-item isolation in `handleDeviceList`: one device whose trait
 *      processing throws (on-hub this was the BigDecimal `.round()` NPE at the
 *      battery derivation) must not abort onboarding of the remaining devices
 *      in the payload. Before the fix, discovery was pinned at exactly one
 *      vent no matter how often Discover was clicked.
 *
 *   2. Hub-safe battery derivation (`deriveBatteryPercent`): double math +
 *      Math.round instead of `.round()` on a BigDecimal, which does not
 *      dispatch on the hub's Groovy runtime (it resolved to the JDK's
 *      BigDecimal.round(MathContext) with an implicit null argument and threw
 *      the raw "java.lang.NullPointerException: null ... line 5150, method
 *      handleDeviceList").
 *
 *   3. Exactly-once throttle-slot accounting: every REGISTERED async callback
 *      (`handleRoomGetWithCache`, `handleDeviceGetWithCache`,
 *      `handlePuckGetWithCache`, `handlePuckReadingGetWithCache`,
 *      `noOpHandler`) releases exactly one slot on every outcome, while the
 *      never-registered legacy handlers (`handleRoomGet`, `handleDeviceGet`,
 *      `handlePuckGet`, `handlePuckReadingGet`) — which are only invoked
 *      synthetically on cache hits and WithCache delegation — never touch the
 *      counter. Before the fix, routine device polling leaked 2-3 slots per
 *      poll until the counter wedged at 8/8 ("CRITICAL: Active request counter
 *      is stuck at 8/8") and all API traffic, including discovery, starved.
 */
class Issue7DiscoverySlotReleaseSpec extends Specification {

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
  private Map children
  private Map addCounts
  private List events
  private Object script

  def setup() {
    log = new CapturingLog()
    children = [:]
    addCounts = [:]
    events = []
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 5]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      // Events on ChildDeviceWrapper proxies dispatch to the real executor-API
      // sendEvent (not the per-instance metaClass override), so capture here.
      _ * sendEvent(*_) >> { List args ->
        def ev = args[1]
        events << [name: ev?.name, value: ev?.value]
      }
      _ * getChildDevice(_) >> { String id -> children[id] }
      _ * addChildDevice(*_) >> { args ->
        String id = args[2] as String
        addCounts[id] = (addCounts[id] ?: 0) + 1
        def child = [getDeviceNetworkId: { -> id }, getId: { -> id }] as ChildDeviceWrapper
        children[id] = child
        return child
      }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.atomicState = [activeRequests: 5]
  }

  // ---------------------------------------------------------------------
  // Fix 1 — per-item isolation in handleDeviceList
  // ---------------------------------------------------------------------

  def 'a vent whose trait processing throws does not abort onboarding of the remaining vents'() {
    given: 'trait processing that always throws (stand-in for the on-hub battery NPE)'
    script.metaClass.processVentTraits = { device, details ->
      throw new IllegalStateException('trait processing failed')
    }
    def resp = okResponse([data: [
      [id: 'vent-1', type: 'vents', attributes: [name: 'Living Room Vent']],
      [id: 'vent-2', type: 'vents', attributes: [name: 'Bedroom Vent']],
    ]])

    when: 'discovery processes the payload'
    script.handleDeviceList(resp, [deviceType: 'vents'])

    then: 'both vents are onboarded despite the per-vent failure'
    children.containsKey('vent-1')
    children.containsKey('vent-2')

    and: 'the failure is surfaced per device, not swallowed silently'
    log.records.any { it[1]?.toString()?.contains('Error onboarding discovered device') }
  }

  // ---------------------------------------------------------------------
  // Fix 2 — hub-safe battery derivation
  // ---------------------------------------------------------------------

  def 'the real vent battery derivation onboards every vent and emits integer battery events'() {
    given: 'two vents carrying voltage on the two supported reading paths'
    def resp = okResponse([data: [
      [id: 'vent-1', type: 'vents', attributes: [name: 'Living Room Vent', 'voltage': 3.1]],
      [id: 'vent-2', type: 'vents', attributes: [name: 'Bedroom Vent', 'system-voltage': 3.6]],
    ]])

    when: 'discovery processes the payload through the REAL processVentTraits'
    script.handleDeviceList(resp, [deviceType: 'vents'])

    then: 'both vents onboard (the derivation itself no longer throws)'
    children.containsKey('vent-1')
    children.containsKey('vent-2')

    and: 'battery is derived as an integer percent on the (v-2.0)/1.6*100 map'
    events.any { it.name == 'battery' && it.value == 69 }   // 3.1 V -> 68.75 -> 69
    events.any { it.name == 'battery' && it.value == 100 }  // 3.6 V -> 100
    events.findAll { it.name == 'battery' }.every { it.value.getClass() == Integer }
  }

  def 'deriveBatteryPercent rounds to the nearest integer and clamps to 0..100'() {
    expect:
    script.deriveBatteryPercent(voltage) == percent

    where:
    voltage | percent
    1.5     | 0     // below the 2.0 V floor clamps to 0
    2.0     | 0
    3.1     | 69    // 68.75 rounds to nearest
    3.6     | 100
    4.2     | 100   // above the 3.6 V ceiling clamps to 100
  }

  // ---------------------------------------------------------------------
  // Fix 3 — registered callbacks release exactly one slot per invocation
  // ---------------------------------------------------------------------

  def 'handleRoomGetWithCache releases its throttle slot on a successful response'() {
    when:
    script.handleRoomGetWithCache(okResponse([:]), [device: fakeDevice('d1')])

    then:
    script.atomicState.activeRequests == 4
  }

  def 'handleRoomGetWithCache releases its throttle slot on an error response'() {
    when:
    script.handleRoomGetWithCache(errorResponse(500), [device: fakeDevice('d1')])

    then:
    script.atomicState.activeRequests == 4
  }

  def 'handleDeviceGetWithCache releases one slot per invocation on both outcomes'() {
    when:
    script.handleDeviceGetWithCache(okResponse(null), [device: fakeDevice('d1')])
    script.handleDeviceGetWithCache(errorResponse(500), [device: fakeDevice('d1')])

    then:
    script.atomicState.activeRequests == 3
  }

  def 'handlePuckGetWithCache releases exactly one slot when delegating to handlePuckGet'() {
    when: 'a successful puck response flows through the WithCache callback and its delegate'
    script.handlePuckGetWithCache(okResponse([data: [attributes: [:]]]), [device: fakeDevice('p1')])

    then: 'exactly one slot is released (the delegate no longer double-releases)'
    script.atomicState.activeRequests == 4
  }

  def 'handlePuckReadingGetWithCache releases exactly one slot when delegating'() {
    when:
    script.handlePuckReadingGetWithCache(okResponse([data: [attributes: [:]]]), [device: fakeDevice('p1')])

    then:
    script.atomicState.activeRequests == 4
  }

  def 'noOpHandler releases the slot held by a fire-and-forget PATCH'() {
    when:
    script.noOpHandler(null, null)

    then:
    script.atomicState.activeRequests == 4
  }

  // ---------------------------------------------------------------------
  // Fix 3 (inverse) — synthetic invocations must not touch the counter
  // ---------------------------------------------------------------------

  def 'cache-hit synthetic invocations of the legacy handlers leave the counter untouched'() {
    when: 'the never-registered handlers are invoked directly with fake responses'
    script.handlePuckGet(okResponse([data: [attributes: [:]]]), [device: fakeDevice('p1')])
    script.handlePuckReadingGet(okResponse([data: [attributes: [:]]]), [device: fakeDevice('p1')])
    script.handleDeviceGet(okResponse(null), [device: fakeDevice('d1')])
    script.handleRoomGet(okResponse([:]), [device: fakeDevice('d1')])

    then: 'no slots are spuriously released'
    script.atomicState.activeRequests == 5
  }

  // ---- helpers -------------------------------------------------------------

  private static Map okResponse(json) {
    return [
      hasProperty: { String p -> false },
      getStatus  : { -> 200 },
      getJson    : { -> json },
    ]
  }

  private static Map errorResponse(int status) {
    return [
      hasProperty    : { String p -> true },
      hasError       : { -> true },
      getStatus      : { -> status },
      getErrorMessage: { -> "HTTP ${status}".toString() },
      getJson        : { -> null },
    ]
  }

  private static Map fakeDevice(String id) {
    return [
      getId             : { -> id },
      getDeviceNetworkId: { -> id },
      getLabel          : { -> "Device ${id}".toString() },
      currentValue      : { String attr -> null },
      hasAttribute      : { String attr -> false },
    ]
  }

}
