/* groovylint-disable MethodName */

// Empty-body PATCH responses (forum #396).
//
// Flair's cloud occasionally answers a vent/room PATCH with a 2xx response that
// carries NO JSON body. Hubitat's AsyncResponse.getJson() then throws
//   java.lang.IllegalArgumentException: No json exists for response
// which aborted handleVentPatch midway: the local percent-open/level update for
// the commanded target never ran, so the device state silently drifted from the
// command until the next poll.
//
// Pinned contract (safeGetJson):
//   * handleVentPatch tolerates an empty-body response: no exception, the trait
//     extraction is skipped, and the LOCAL target update still applies (the next
//     poll reconciles from the API).
//   * handleRoomPatch tolerates the same: no exception, no event.
//   * Responses WITH a JSON body keep the exact pre-change behavior.
//
// Run `./gradlew test --tests '*PatchEmptyBody*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class PatchEmptyBodyResponseTest extends Specification {

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
  List events   // captured sendEvent(device, map) pairs at the executor boundary
  def script

  def setup() {
    log = new CapturingLog()
    events = []
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [:]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      _ * now() >> 1_000_000L
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.atomicState = [:]
    // Capture events at the script boundary (puck2-attribute-mapping pattern):
    // both the traitExtract sendEvent and the safeSendEvent target update.
    script.metaClass.sendEvent = { dev, Map ev -> events << [dev, ev] }
    script.metaClass.safeSendEvent = { dev, Map ev -> events << [dev, ev] }
  }

  // A plain-map fake device: property-style access to its closures stays truthy
  // (handleVentPatch checks `data.device?.getDeviceNetworkId`).
  private static Map fakeVent(String id) {
    Map self = [:]
    self.getDeviceNetworkId = { -> id }
    self.getLabel = { -> "Vent ${id}".toString() }
    self.currentValue = { Object... a -> null }
    self.hasAttribute = { Object... a -> true }
    return self
  }

  // A 2xx response whose body is EMPTY: getJson() throws exactly like Hubitat's
  // AsyncResponse does (forum #396).
  private static Map emptyBodyResponse() {
    return [
      hasProperty: { String p -> false },
      getStatus  : { -> 200 },
      getJson    : { -> throw new IllegalArgumentException('No json exists for response') },
    ]
  }

  private static Map jsonResponse(Map json) {
    return [
      hasProperty: { String p -> false },
      getStatus  : { -> 200 },
      getJson    : { -> json },
    ]
  }

  def 'an empty-body vent PATCH response still applies the local target update (no exception)'() {
    given:
    def vent = fakeVent('v1')

    when:
    script.handleVentPatch(emptyBodyResponse(), [device: vent, targetOpen: 55])

    then: 'no IllegalArgumentException escapes and the commanded target is applied locally'
    noExceptionThrown()
    events.find { it[1].name == 'percent-open' && it[1].value == 55 } != null
    events.find { it[1].name == 'level' && it[1].value == 55 } != null
  }

  def 'a vent PATCH response WITH a body keeps the pre-change behavior (traits extracted, target applied)'() {
    given:
    def vent = fakeVent('v1')
    def resp = jsonResponse([data: [attributes: ['percent-open': 42]]])

    when:
    script.handleVentPatch(resp, [device: vent, targetOpen: 42])

    then: 'the API-reported percent-open is extracted and the target applied'
    events.any { it[1].name == 'percent-open' && (it[1].value as Integer) == 42 }
    events.any { it[1].name == 'level' && (it[1].value as Integer) == 42 }
  }

  def 'an empty-body room PATCH response is tolerated (no exception, no event)'() {
    given:
    def vent = fakeVent('v1')

    when:
    script.handleRoomPatch(emptyBodyResponse(), [device: vent])

    then:
    noExceptionThrown()
    events.isEmpty()
  }

  def 'a room PATCH response WITH a body still extracts room-active'() {
    given:
    def vent = fakeVent('v1')
    def resp = jsonResponse([data: [attributes: ['active': true]]])

    when:
    script.handleRoomPatch(resp, [device: vent])

    then:
    events.any { it[1].name == 'room-active' }
  }
}
