
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Example-based specs for R1 Puck 2 attribute mapping + temperature fallback
 * chain (task 2.3). These pin the wiring that task 2.4 implements:
 *
 *   - a representative Puck 2 reading maps temperature
 *     (`current-temperature-c` / `room-temperature-c`), humidity,
 *     motion/occupancy (where exposed), `rssi`, firmware
 *     (`firmware-version-s`), and `voltage` / `system-voltage` -> `voltage`
 *     plus derived `battery` (R1.4-R1.10);
 *   - partial payloads missing RSSI / firmware still onboard and process the
 *     attributes that ARE present, without aborting (R1.11, R1.12);
 *   - the temperature fallback chain resolves Puck field -> Flair room API ->
 *     defer (R1.19, R1.20, R1.21);
 *   - V1 attributes are unchanged and a V1 reading is never conflated with a
 *     V2 reading in one room group (R1.17, R1.25).
 *
 * Validates: Requirements 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 1.11, 1.12,
 *            1.17, 1.19, 1.20, 1.21, 1.25.
 *
 * STRICT TDD: these FAIL against the current handlers. `handlePuckGet` never
 * emits a canonical `rssi`, `firmware-version-s`, `motion`, or `voltage` event
 * (it sends `current-rssi` and only a derived `battery`); `handlePuckReadingGet`
 * never emits `rssi`, `firmware-version-s`, or `motion`; and `getRoomTemp`
 * returns 0 (commanding on missing data) instead of deferring. Task 2.4 makes
 * them green.
 */
class Puck2AttributeMappingSpec extends Specification {

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
  List events
  def script

  def setup() {
    log = new CapturingLog()
    events = []
    script = buildScript(['debugLevel': 1])
    // Capture every emitted device event (with its target device) instead of a
    // real sendEvent, so we can assert per-attribute and per-device mapping.
    script.metaClass.sendEvent = { device, Map ev ->
      events << [dev: device, name: ev.name, value: ev.value, unit: ev.unit]
    }
  }

  // ---- R1.4-R1.10: full Puck 2 resource reading (handlePuckGet) ------------

  def 'a representative Puck 2 resource reading maps every driver attribute'() {
    given: 'a Puck 2 pucks-resource payload exposing the full attribute set'
    def device = fakeDevice('puck2-1')
    def resp = okResponse([data: [
      id: 'puck2-1', type: 'pucks',
      attributes: [
        'current-temperature-c' : 21.5,
        'current-humidity'      : 44,
        'current-rssi'          : -61,
        'firmware-version-s'    : '1.6.0-puck2',
        'voltage'               : 3.1,
        'occupied'              : true,
        'hardware-version-name' : 'ep_puck2',
      ],
    ]])

    when: 'the pucks-resource handler processes it'
    script.handlePuckGet(resp, [device: device])

    then: 'temperature, humidity and battery map as they do for V1 (parity)'
    eventNamed('temperature') != null                       // R1.4
    eventNamed('humidity') != null                          // R1.5
    eventNamed('battery') != null                           // R1.10

    and: 'RSSI maps onto the canonical rssi attribute (not just current-rssi)'
    eventNamed('rssi')?.value == -61                        // R1.7

    and: 'firmware maps onto the firmware-version-s attribute'
    eventNamed('firmware-version-s')?.value == '1.6.0-puck2' // R1.8

    and: 'the puck-resource voltage maps onto the voltage attribute'
    eventNamed('voltage')?.value == 3.1                     // R1.9

    and: 'occupancy/motion maps onto the motion attribute (where exposed)'
    eventNamed('motion') != null                            // R1.6
  }

  // ---- R1.4-R1.10: full Puck 2 sensor reading (handlePuckReadingGet) -------

  def 'a representative Puck 2 sensor reading maps every driver attribute'() {
    given: 'a Puck 2 sensor-reading payload exposing the full attribute set'
    def device = fakeDevice('puck2-2')
    def resp = okResponse([data: [
      id: 'puck2-2', type: 'pucks',
      attributes: [
        'room-temperature-c' : 20.0,
        'humidity'           : 47,
        'system-voltage'     : 3.0,
        'current-rssi'       : -58,
        'firmware-version-s' : '1.6.0-puck2',
        'occupied'           : true,
      ],
    ]])

    when: 'the sensor-reading handler processes it'
    script.handlePuckReadingGet(resp, [device: device])

    then: 'temperature, humidity, voltage and battery map (existing reading parity)'
    eventNamed('temperature') != null                       // R1.4 / R1.19
    eventNamed('humidity') != null                          // R1.5
    eventNamed('voltage')?.value == 3.0                     // R1.9
    eventNamed('battery') != null                           // R1.10

    and: 'RSSI maps onto the canonical rssi attribute'
    eventNamed('rssi')?.value == -58                        // R1.7

    and: 'firmware maps onto the firmware-version-s attribute'
    eventNamed('firmware-version-s')?.value == '1.6.0-puck2' // R1.8

    and: 'occupancy/motion maps onto the motion attribute (where exposed)'
    eventNamed('motion') != null                            // R1.6
  }

  // ---- R1.11 / R1.12: partial payloads tolerated ---------------------------

  def 'a partial Puck 2 reading missing RSSI and firmware still maps present attributes without aborting'() {
    given: 'a Puck 2 payload with temperature and voltage but no RSSI or firmware'
    def device = fakeDevice('puck2-partial')
    def resp = okResponse([data: [
      id: 'puck2-partial', type: 'pucks',
      attributes: [
        'current-temperature-c' : 19.0,
        'voltage'               : 2.9,
      ],
    ]])

    when: 'the handler processes the partial payload'
    script.handlePuckGet(resp, [device: device])

    then: 'processing does not abort on the absent optional fields'
    noExceptionThrown()                                     // R1.11 / R1.12

    and: 'the present attributes still map'
    eventNamed('temperature') != null
    eventNamed('battery') != null
    eventNamed('voltage')?.value == 2.9                     // R1.9

    and: 'no rssi or firmware event is fabricated for the missing fields'
    eventNamed('rssi') == null
    eventNamed('firmware-version-s') == null
  }

  // ---- R1.17 / R1.25: V1 and V2 readings are never conflated ---------------

  def 'V1 and V2 pucks in one room group keep their readings separate'() {
    given: 'a V1 puck and a Puck 2 in the same room group, each with its own reading'
    def v1 = fakeDevice('puck-v1')
    def v2 = fakeDevice('puck2-v2')

    when: 'each device receives its own sensor reading'
    script.handlePuckReadingGet(okResponse([data: [type: 'pucks',
      attributes: ['room-temperature-c': 20.0, 'current-rssi': -50]]]), [device: v1])
    script.handlePuckReadingGet(okResponse([data: [type: 'pucks',
      attributes: ['room-temperature-c': 24.0, 'current-rssi': -70]]]), [device: v2])

    then: 'each device keeps its own temperature (V1 reading not conflated with V2)'
    eventFor(v1, 'temperature')?.value != null
    eventFor(v2, 'temperature')?.value != null
    eventFor(v1, 'temperature')?.value != eventFor(v2, 'temperature')?.value // R1.17 / R1.25

    and: 'each device maps its own rssi value with no cross-device bleed'
    eventFor(v1, 'rssi')?.value == -50                      // R1.7 + R1.17
    eventFor(v2, 'rssi')?.value == -70
  }

  // ---- R1.19 / R1.20 / R1.21: temperature fallback chain -------------------

  def 'the temperature resolution prefers the Puck source first'() {
    given: 'a vent whose room has a Puck-as-thermostat source reporting temperature'
    def s = buildScript(['debugLevel': 1, 'thermostat1TempUnit': '1'])
    s.'thermostatpk2' = [
      currentValue: { String a -> a == 'temperature' ? 21.0 : null },
      getLabel    : { -> 'Bedroom Puck 2' },
    ]
    def vent = fakeVent('pk2', ['room-name': 'Bedroom', 'room-current-temperature-c': 18.0])

    expect: 'the Puck source temperature wins over the room API value (R1.19)'
    s.getRoomTemp(vent) == 21.0
  }

  def 'the temperature resolution falls back to the Flair room API when no Puck source exists'() {
    given: 'a vent with no Puck source but a room API temperature'
    def s = buildScript(['debugLevel': 1])
    def vent = fakeVent('pk3', ['room-name': 'Office', 'room-current-temperature-c': 20.0])

    expect: 'the room API temperature is used (R1.20)'
    s.getRoomTemp(vent) == 20.0
  }

  def 'the temperature resolution defers when neither a Puck source nor the room API yields a value'() {
    given: 'a vent with no Puck source and no room API temperature'
    def s = buildScript(['debugLevel': 1])
    def vent = fakeVent('pk4', ['room-name': 'Garage', 'room-current-temperature-c': null])

    expect: 'resolution defers (null sentinel) rather than commanding on missing data (R1.21)'
    s.getRoomTemp(vent) == null
  }

  // ---- helpers -------------------------------------------------------------

  private def buildScript(Map userSettings) {
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 50]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def s = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': userSettings)
    s.atomicState = [activeRequests: 50]
    s.metaClass.isValidResponse = { resp -> true }
    s.metaClass.decrementActiveRequests = { -> }
    return s
  }

  private Map eventNamed(String name) { events.find { it.name == name } }

  private Map eventFor(device, String name) {
    events.find { it.dev?.is(device) && it.name == name }
  }

  private static Object fakeDevice(String dni) {
    return [
      getDeviceNetworkId: { -> dni },
      getId             : { -> dni },
      getLabel          : { -> "Device ${dni}" },
    ]
  }

  private static Object fakeVent(String ventId, Map attrs) {
    return [
      getId       : { -> ventId },
      currentValue: { String a -> attrs[a] },
      getLabel    : { -> "Vent ${ventId}" },
    ]
  }

  private static Map okResponse(Map json) {
    return [
      hasProperty: { String p -> false },
      getStatus  : { -> 200 },
      getJson    : { -> json },
    ]
  }
}
