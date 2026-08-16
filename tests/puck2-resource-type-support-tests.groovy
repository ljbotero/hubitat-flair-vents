/* groovylint-disable MethodName */

// Puck 2 as a separate JSON:API resource type (`puck2s`) — forum #387.
//
// Ground truth from the official Flair API docs (flair.co/api Postman
// collection): Puck 2 devices are a SEPARATE resource type with their own
// endpoint family — GET /api/puck2s, /api/puck2s/{id},
// /api/puck2s/{id}/current-reading — and the Pucks endpoint explicitly notes
// they are NEVER included in /api/pucks responses. Rooms carry them in their
// own `puck2s` relationship list (HomeAlone's #387 payload showed
// relationships.puck2s.data populated while relationships.pucks.data was
// empty). 0.238 and earlier only ever queried the v1 `pucks` family, so a
// Puck 2 could never be discovered no matter how healthy the transport was.
//
// Pinned contract (v1 support MUST remain byte-for-byte intact):
//   * discover() queries BOTH families: structures/{id}/pucks + /api/pucks
//     (v1, unchanged) and structures/{id}/puck2s + /api/puck2s (Puck 2).
//   * handleDeviceList / handleAllPucks / handleRoomsWithPucks (included AND
//     relationships blocks) onboard `puck2s` items onto the same 'Flair pucks'
//     driver, recording apiType='puck2s' on the child; v1 items keep
//     apiType='pucks'.
//   * getDeviceData routes polling by the recorded apiType: puck2s children
//     poll /api/puck2s/{id}, /api/puck2s/{id}/current-reading, and
//     /api/puck2s/{id}/room; children with NO apiType (created before this
//     change) default to the v1 /api/pucks/... paths.
//   * puck2-sensor-readings attributes map onto the same canonical driver
//     attributes: room-temperature-c -> temperature (F), humidity,
//     sub-ghz-rssi -> rssi, numeric firmware-version -> firmware-version-s.
//   * A USB-powered Puck 2 (power-source 'USB', voltage 0.0) emits NO
//     voltage/battery events; battery-powered devices keep the derivation.
//
// Run `./gradlew test --tests '*Puck2ResourceType*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class Puck2ResourceTypeSupportTest extends Specification {

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
  Map dataValues     // deviceId -> [key: value] recorded via updateDataValue
  Map stateMap
  Map atomicStateMap
  List httpGets      // captured asynchttpGet: [callback, params, data]
  List events        // captured sendEvent(device, map) at the executor boundary
  def script

  def setup() {
    log = new CapturingLog()
    children = [:]
    addCounts = [:]
    dataValues = [:]
    stateMap = [:]
    atomicStateMap = [activeRequests: 0]
    httpGets = []
    events = []
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> { stateMap }
      _ * getAtomicState() >> { atomicStateMap }
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      _ * now() >> 1_000_000L
      _ * getChildDevice(_) >> { String id -> children[id] }
      _ * addChildDevice(*_) >> { args ->
        String id = args[2]?.toString()
        addCounts[id] = (addCounts[id] ?: 0) + 1
        children[id] = fakeChild(id)
        return children[id]
      }
      _ * asynchttpGet(_, _, _) >> { String cb, Map params, cbData -> httpGets << [cb, params, cbData] }
      _ * sendEvent(*_) >> { args -> events << [args[0], args[1]] }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
  }

  // A fake puck child that records data values and reports no vent attributes.
  private ChildDeviceWrapper fakeChild(String id) {
    Map dv = dataValues[id] = (dataValues[id] ?: [:])
    return [getDeviceNetworkId: { -> id }, getId: { -> id },
            updateDataValue   : { String k, String v -> dv[k] = v },
            getDataValue      : { String k -> dv[k] },
            hasAttribute      : { String a -> false },
            currentValue      : { Object... a -> null }] as ChildDeviceWrapper
  }

  private static Map okResponse(Map json) {
    return [
      hasProperty: { String p -> false },
      getStatus  : { -> 200 },
      getJson    : { -> json },
    ]
  }

  // ===========================================================================
  // Discovery fan-out queries BOTH puck generations
  // ===========================================================================

  def 'discover() queries the v1 pucks endpoints AND the puck2s endpoints'() {
    given: 'a resolved structure id'
    script.metaClass.getStructureId = { -> 'st1' }

    when:
    script.discover()

    then: 'six GETs go out: vents, both puck families per structure, rooms, and both account-wide puck lists'
    List uris = httpGets.collect { it[1].uri }
    uris.any { it.endsWith('/api/structures/st1/vents') }
    uris.any { it.endsWith('/api/structures/st1/pucks') }
    uris.any { it.endsWith('/api/structures/st1/puck2s') }
    uris.any { it.contains('/api/structures/st1/rooms') }
    uris.any { it.endsWith('/api/pucks') }
    uris.any { it.endsWith('/api/puck2s') }

    and: 'the puck2s requests carry their resource type for the shared handlers'
    httpGets.find { it[1].uri.endsWith('/api/structures/st1/puck2s') }[2].deviceType == 'puck2s'
    httpGets.find { it[1].uri.endsWith('/api/puck2s') }[2].deviceType == 'puck2s'
  }

  // ===========================================================================
  // Onboarding records the API resource family on the child
  // ===========================================================================

  def 'handleDeviceList onboards a puck2s payload with apiType puck2s'() {
    given: 'the official docs example shape for GET /api/puck2s'
    def resp = okResponse([data: [
      [id: 'p2-1', type: 'puck2s',
       attributes: [name: 'Kitchen-ae4b', 'hardware-version-name': 'ep_puck2']],
    ]])

    when:
    script.handleDeviceList(resp, [deviceType: 'puck2s'])

    then:
    children.containsKey('p2-1')
    dataValues['p2-1'].apiType == 'puck2s'
  }

  def 'a mixed structure keeps v1 pucks on apiType pucks while puck2s onboard alongside'() {
    when: 'both generations arrive through their own endpoint responses'
    script.handleDeviceList(okResponse([data: [
      [id: 'p1-1', type: 'pucks', attributes: [name: 'Old Faithful']],
    ]]), [deviceType: 'pucks'])
    script.handleDeviceList(okResponse([data: [
      [id: 'p2-1', type: 'puck2s', attributes: [name: 'Kitchen-ae4b']],
    ]]), [deviceType: 'puck2s'])

    then: 'both onboard, each recording its own resource family'
    dataValues['p1-1'].apiType == 'pucks'
    dataValues['p2-1'].apiType == 'puck2s'
  }

  def 'handleAllPucks onboards /api/puck2s items as puck2s and defaults to v1 pucks without a deviceType'() {
    when:
    script.handleAllPucks(okResponse([data: [[id: 'p2-2', attributes: [name: 'Bedroom P2']]]]),
        [deviceType: 'puck2s'])
    script.handleAllPucks(okResponse([data: [[id: 'p1-2', attributes: [name: 'Bedroom P1']]]]), null)

    then:
    dataValues['p2-2'].apiType == 'puck2s'
    dataValues['p1-2'].apiType == 'pucks'
  }

  def 'a room whose only puck signal is a puck2s relationship reference is onboarded (forum #387 payload shape)'() {
    given: 'HomeAlone: relationships.pucks.data empty, relationships.puck2s.data populated'
    def resp = okResponse([data: [
      [id: 'room-1', attributes: [name: 'Den'],
       relationships: [
         pucks : [data: []],
         puck2s: [data: [[id: 'e7c0a9ae-0ddf-5526-d727-da00e3b32a31', type: 'puck2s']]],
       ]],
    ]])

    when:
    script.handleRoomsWithPucks(resp, null)

    then:
    children.containsKey('e7c0a9ae-0ddf-5526-d727-da00e3b32a31')
    dataValues['e7c0a9ae-0ddf-5526-d727-da00e3b32a31'].apiType == 'puck2s'

    when: 'v1 relationship references arrive (regression)'
    script.handleRoomsWithPucks(okResponse([data: [
      [id: 'room-2', attributes: [name: 'Attic'],
       relationships: [pucks: [data: [[id: 'p1-3', type: 'pucks']]]]],
    ]]), null)

    then: 'they still onboard with the v1 family'
    dataValues['p1-3'].apiType == 'pucks'
  }

  def 'an included-block puck2s (rooms?include) is onboarded with apiType puck2s'() {
    when:
    script.handleRoomsWithPucks(okResponse([included: [
      [id: 'p2-4', type: 'puck2s', attributes: [name: 'Loft Puck 2']],
    ]]), null)

    then:
    children.containsKey('p2-4')
    dataValues['p2-4'].apiType == 'puck2s'
  }

  // ===========================================================================
  // Polling routes by the recorded resource family
  // ===========================================================================

  def 'a puck2s child polls the /api/puck2s endpoint family'() {
    given: 'a child recorded as puck2s'
    children['p2-5'] = fakeChild('p2-5')
    dataValues['p2-5'].apiType = 'puck2s'

    when:
    script.getDeviceData(children['p2-5'])

    then: 'resource, current-reading, and room are all fetched from the puck2s family'
    List uris = httpGets.collect { it[1].uri }
    uris.any { it.endsWith('/api/puck2s/p2-5') }
    uris.any { it.endsWith('/api/puck2s/p2-5/current-reading') }
    uris.any { it.endsWith('/api/puck2s/p2-5/room') }
    !uris.any { it.contains('/api/pucks/') }
  }

  def 'a puck child with NO recorded apiType keeps the exact v1 paths (pre-change devices)'() {
    given: 'a child created before apiType existed'
    children['p1-4'] = fakeChild('p1-4')

    when:
    script.getDeviceData(children['p1-4'])

    then:
    List uris = httpGets.collect { it[1].uri }
    uris.any { it.endsWith('/api/pucks/p1-4') }
    uris.any { it.endsWith('/api/pucks/p1-4/current-reading') }
    uris.any { it.endsWith('/api/pucks/p1-4/room') }
    !uris.any { it.contains('/api/puck2s/') }
  }

  // ===========================================================================
  // puck2-sensor-readings attribute mapping (official docs example)
  // ===========================================================================

  def 'a puck2-sensor-readings payload maps temperature, humidity, rssi, and firmware onto the canonical attributes'() {
    given: 'the docs example reading for GET /api/puck2s/{id}/current-reading'
    def child = fakeChild('p2-6')
    def reading = [data: [
      type      : 'puck2-sensor-readings',
      id        : 'bb17e42f',
      attributes: ['room-temperature-c': 22.57, 'humidity': 36.0,
                   'firmware-version': 51, 'mode': 'Sensor', 'power-source': 'USB',
                   'created-at': '2026-05-22T17:00:03.121520+00:00',
                   'sub-ghz-rssi': -50, 'connected-gateway-name': 'Bridge-2ddd'],
    ]]

    when:
    script.handlePuckReadingGet(okResponse(reading), [device: child])

    then: 'temperature converts C -> F'
    def temp = events.find { it[1].name == 'temperature' }
    (temp[1].value as double) > 72.6d && (temp[1].value as double) < 72.7d

    and: 'humidity passes through'
    events.find { it[1].name == 'humidity' }[1].value == 36.0

    and: 'sub-ghz-rssi maps onto the canonical rssi attribute'
    events.find { it[1].name == 'rssi' }[1].value == -50

    and: 'the numeric Puck 2 firmware-version surfaces as firmware-version-s'
    events.find { it[1].name == 'firmware-version-s' }[1].value == '51'

    and: 'no battery is derived (no system-voltage in puck2 readings)'
    events.find { it[1].name == 'battery' } == null
  }

  def 'v1 reading attributes still map identically (regression)'() {
    given:
    def child = fakeChild('p1-5')
    def reading = [data: [attributes: ['room-temperature-c': 20.0, 'humidity': 45,
                                       'current-rssi': -61, 'firmware-version-s': '1.6.0']]]

    when:
    script.handlePuckReadingGet(okResponse(reading), [device: child])

    then:
    (events.find { it[1].name == 'temperature' }[1].value as double) == 68.0d
    events.find { it[1].name == 'rssi' }[1].value == -61
    events.find { it[1].name == 'firmware-version-s' }[1].value == '1.6.0'
  }

  // ===========================================================================
  // USB power guard on the puck resource path
  // ===========================================================================

  def 'a USB-powered Puck 2 resource emits no voltage or battery events'() {
    given: 'the docs example: power-source USB, voltage 0.0'
    def child = fakeChild('p2-7')
    def resource = [data: [id: 'p2-7', type: 'puck2s',
      attributes: [name: 'Kitchen-ae4b', 'power-source': 'USB', 'voltage': 0.0,
                   'current-temperature-c': 22.57, 'current-humidity': 36.0]]]

    when:
    script.handlePuckGet(okResponse(resource), [device: child])

    then:
    events.find { it[1].name == 'voltage' } == null
    events.find { it[1].name == 'battery' } == null

    and: 'temperature and humidity still map'
    events.find { it[1].name == 'temperature' } != null
    events.find { it[1].name == 'humidity' } != null
  }

  def 'a battery-powered puck keeps the voltage -> battery derivation (regression)'() {
    given:
    def child = fakeChild('p1-6')
    def resource = [data: [id: 'p1-6', type: 'pucks',
      attributes: [name: 'Old Faithful', 'voltage': 2.8, 'current-temperature-c': 21.0]]]

    when:
    script.handlePuckGet(okResponse(resource), [device: child])

    then:
    events.find { it[1].name == 'voltage' }[1].value == 2.8
    events.find { it[1].name == 'battery' } != null
  }
}
