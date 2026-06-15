
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Example-based specs for R1 Puck 2 discovery tolerance (task 2.1).
 *
 * Pins the concrete wiring the property spec asserts in aggregate:
 *   - a representative Puck 2 payload on the `structures/{id}/pucks`
 *     (`handleDeviceList`) path with a BLANK `name` is onboarded via the
 *     ID-derived fallback (R1.1, R1.22-cross-ref R7.32);
 *   - an unknown device `type` is logged at a diagnostic level and skipped,
 *     while recognized devices in the same payload still onboard
 *     (R1.22, R1.23);
 *   - an included-block-only Puck 2 (`rooms?include=pucks`) is onboarded
 *     (R1.14).
 *
 * Validates: Requirements 1.1, 1.14, 1.15, 1.16, 1.18, 1.22, 1.23.
 *
 * STRICT TDD: the blank-name and unknown-type-diagnostic-log expectations FAIL
 * against the current `handleDeviceList`, which has no blank-name fallback and
 * emits no per-device diagnostic for an unrecognized `type`. Tasks 2.2/2.4 make
 * them green.
 */
class Puck2DiscoveryOnboardingSpec extends Specification {

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
  def script

  def setup() {
    log = new CapturingLog()
    children = [:]
    addCounts = [:]
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
    script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.atomicState = [activeRequests: 50]
    script.metaClass.processVentTraits = { device, details -> }
  }

  def 'a blank-name Puck 2 on the structures/{id}/pucks path is onboarded via the ID-derived fallback'() {
    given: 'a Puck 2 (type:"pucks", hardware-version-name:"ep_puck2") with a blank name'
    def resp = okResponse([data: [
      [id: 'puck2-blank', type: 'pucks',
       attributes: ['name': '', 'hardware-version-name': 'ep_puck2']],
    ]])

    when: 'the structures/{id}/pucks handler processes it'
    script.handleDeviceList(resp, [deviceType: 'pucks'])

    then: 'the Puck 2 is onboarded exactly once keyed on its network ID'
    children.containsKey('puck2-blank')
    addCounts['puck2-blank'] == 1
  }

  def 'an unknown device type is logged at diagnostic level and skipped while recognized devices onboard'() {
    given: 'a payload mixing a recognized vent with an unknown-type device'
    def resp = okResponse([data: [
      [id: 'vent-ok', type: 'vents', attributes: [name: 'Living Room Vent']],
      [id: 'gizmo-1', type: 'gizmos', attributes: [name: 'Mystery Gizmo']],
    ]])

    when: 'discovery processes the payload'
    script.handleDeviceList(resp, [deviceType: 'vents'])

    then: 'the recognized vent onboards'
    children.containsKey('vent-ok')

    and: 'the unknown-type device is skipped (never onboarded)'
    !children.containsKey('gizmo-1')

    and: 'the unrecognized payload is logged at a diagnostic level (R1.22)'
    log.records.any { it[1]?.toString()?.contains('gizmos') }
  }

  def 'an included-block-only Puck 2 (rooms?include=pucks) is onboarded'() {
    given: 'a Puck 2 reachable only via the included block of rooms?include=pucks'
    def resp = okResponse([included: [
      [id: 'puck2-included', type: 'pucks',
       attributes: ['name': 'Bedroom Puck', 'hardware-version-name': 'ep_puck2']],
    ]])

    when: 'the rooms-with-pucks handler processes it'
    script.handleRoomsWithPucks(resp, null)

    then: 'the included-only Puck 2 is onboarded exactly once'
    children.containsKey('puck2-included')
    addCounts['puck2-included'] == 1
  }

  private static Map okResponse(Map json) {
    return [
      hasProperty: { String p -> false },
      getStatus  : { -> 200 },
      getJson    : { -> json },
    ]
  }
}
