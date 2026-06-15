
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Property-based spec for R1 onboarding idempotency / discovery tolerance (task 2.1).
 *
 * Implements design.md Correctness Property 21 EXACTLY (one property per feature
 * method, tagged with the exact heading):
 *
 *   Property 21: Onboarding idempotency (R1.16 / R1.18)
 *   "Repeated discovery over any payload (mixed V1/V2, cross-endpoint duplicates,
 *    blank names) yields an invariant child-device count keyed on network ID;
 *    never more than one child per network ID."
 *
 * The property is exercised across the three real discovery handlers that fan
 * out to different Flair endpoints and converge on the same network ID:
 *   - handleDeviceList     (structures/{id}/pucks + vents)
 *   - handleAllPucks       (pucks)
 *   - handleRoomsWithPucks (rooms?include=pucks, `included` block)
 *
 * Validates: Requirements 1.1, 1.14, 1.15, 1.16, 1.18, 1.22, 1.23.
 *
 * STRICT TDD: this spec is written to FAIL against the current code. The
 * `structures/{id}/pucks` handler (`handleDeviceList`) builds the device label
 * from `it?.attributes?.name` with NO blank-name fallback (unlike the other two
 * puck handlers), so `makeRealDevice` drops any recognized device shipped with a
 * blank/default name. The "invariant child-device count keyed on network ID"
 * therefore does not equal the set of recognized network IDs until task 2.2
 * adds the shared `"Vent-${id}"`/`"Puck-${id}"` fallback. The class name
 * contains "Property" so `--tests '*Property*'` selects it; the `where:` block
 * drives >= PropertyGen.ITERATIONS (100) reproducible randomized payloads.
 */
class OnboardingIdempotencyPropertySpec extends Specification {

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

  def 'Feature: hubitat-flair-vents-dab-v2, Property 21: Onboarding idempotency'() {
    given: 'a randomized mixed V1/V2 payload with cross-endpoint duplicates and blank names'
    def g = PropertyGen.forIteration(i)
    List devices = makePayload(g, i)
    Set<String> expectedNetworkIds = devices.findAll { it.recognized }*.id as Set

    and: 'a sandboxed app whose child-device registry is keyed on network ID'
    Map children = [:]
    Map addCounts = [:].withDefault { 0 }
    def script = buildDiscoveryScript(children, addCounts)

    when: 'discovery runs across all three endpoints, then RE-RUNS (idempotency)'
    runDiscovery(script, devices)
    int afterFirstRun = children.size()
    runDiscovery(script, devices)
    int afterSecondRun = children.size()

    then: 'every recognized device onboards exactly once (count keyed on network ID)'
    children.keySet() == expectedNetworkIds

    and: 'never more than one child per network ID'
    addCounts.every { String id, int n -> n <= 1 }

    and: 'unrecognized devices are skipped (never onboarded)'
    devices.findAll { !it.recognized }.every { !children.containsKey(it.id) }

    and: 'the child-device count is invariant across repeated discovery'
    afterFirstRun == expectedNetworkIds.size()
    afterSecondRun == afterFirstRun

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  // ---------------------------------------------------------------------------
  // Payload generation: a mix of V1 pucks, V2 pucks, vents, and unknown-type
  // devices; some with blank/whitespace/null names. Index 0 is ALWAYS a
  // recognized device routed only through handleDeviceList with a blank name so
  // the blank-name drop is exercised on every iteration.
  // ---------------------------------------------------------------------------
  private List makePayload(PropertyGen g, int iter) {
    List blanks = [null, '', '   ', '\t']
    List devices = []

    // Forced blank-name recognized device on the structures/{id}/pucks path.
    devices << [
      id        : "dev-${iter}-0".toString(),
      apiType   : g.nextBoolean() ? 'vents' : 'pucks',
      name      : g.pick(blanks),
      recognized: true,
      hwVersion : 'ep_puck2'
    ]

    int extra = g.nextInt(0, 6)
    // NB: iterate exactly `extra` times starting at k=1. A prior `(1..extra)`
    // range is a Groovy reverse-range [1,0] when extra==0, which spuriously
    // emitted a k=0 device colliding with the forced index-0 network ID (and
    // could tag the same id as both recognized and unrecognized). `times`
    // avoids that gotcha and keeps ids unique: dev-${iter}-1..dev-${iter}-extra.
    extra.times { int idx ->
      int k = idx + 1
      def roll = g.nextInt(0, 9)
      String apiType
      boolean recognized
      String hw = null
      if (roll <= 2)      { apiType = 'vents'; recognized = true }
      else if (roll <= 4) { apiType = 'pucks'; recognized = true; hw = 'ep_puck' }
      else if (roll <= 6) { apiType = 'pucks'; recognized = true; hw = 'ep_puck2' }
      else                { apiType = g.pick(['thermostats', 'gizmos', 'bridges']); recognized = false }

      String name = g.nextBoolean() ? g.pick(blanks) : "Device ${iter}-${k}".toString()
      devices << [
        id        : "dev-${iter}-${k}".toString(),
        apiType   : apiType,
        name      : name,
        recognized: recognized,
        hwVersion : hw
      ]
    }
    return devices
  }

  /**
   * Drive all three discovery handlers with the same logical device set,
   * deliberately presenting pucks through MULTIPLE endpoints (cross-endpoint
   * duplicates) to exercise consolidation onto one network ID.
   */
  private void runDiscovery(script, List devices) {
    def listData = devices.collect {
      [id: it.id, type: it.apiType, attributes: [name: it.name]]
    }
    script.handleDeviceList(okResponse([data: listData]), [deviceType: 'vents'])

    def pucks = devices.findAll { it.apiType == 'pucks' }
    script.handleAllPucks(okResponse([data:
      pucks.collect { [id: it.id, attributes: [name: it.name]] }]), null)

    script.handleRoomsWithPucks(okResponse([included:
      pucks.collect { [id: it.id, type: 'pucks', attributes: [name: it.name]] }]), null)
  }

  private Object buildDiscoveryScript(Map children, Map addCounts) {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 50]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      _ * getChildDevice(_) >> { String id -> children[id] }
      _ * addChildDevice(*_) >> { args ->
        String id = args[2]?.toString()
        addCounts[id] = (addCounts[id] ?: 0) + 1
        def child = [getDeviceNetworkId: { -> id }, getId: { -> id }] as ChildDeviceWrapper
        children[id] = child
        return child
      }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.atomicState = [activeRequests: 50]
    // Isolate discovery from vent-trait processing collaborators.
    script.metaClass.processVentTraits = { device, details -> }
    return script
  }

  // Map-coerced HTTP response proxy. isValidResponse() short-circuits on
  // hasProperty('hasError') == false, so this is treated as a valid response.
  private static Map okResponse(Map json) {
    return [
      hasProperty: { String p -> false },
      getStatus  : { -> 200 },
      getJson    : { -> json },
    ]
  }
}
