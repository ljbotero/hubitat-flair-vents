/* groovylint-disable MethodName */

// DAB v2 diagnostics wiring + shipped drivers (forum #392).
//
// A user enabled "Create diagnostic devices" and no devices ever appeared. Two
// independent gaps, both pinned here so they cannot regress:
//
//   1. publishDabV2Diagnostics was fully implemented and unit-tested
//      (dabv2-observability-tests) but NEVER CALLED from any evaluate path, so
//      the surface was dead code in production.
//   2. The driver types the app instantiates ('Flair Vents Room Diagnostics'
//      and 'Flair Vents Zone Summary') existed only as name constants. No
//      driver source shipped in src/ or in the bundle, so on a hub every
//      addChildDevice would have failed even once the publish path was wired.
//
// Pinned contract:
//   * Both evaluate paths (runDabV2BalanceEvaluate for the flat single-zone
//     install, evaluateDabV2ZoneById for zoned installs) publish diagnostics
//     after dispatch (source-level pin, version-surfacing style).
//   * Both driver sources exist, declare the exact definition names and the
//     bot.flair namespace the app constants reference.
//   * Every attribute key the app can emit - per-room
//     (gatherDabV2RoomDiagnostics, driven through the REAL sandbox) and summary
//     (gatherDabV2SystemSummary with full comparison metrics) - is declared as
//     an attribute in the corresponding driver source.
//   * The bundle packager ships both drivers.
//
// Review follow-ups on the original wiring (also pinned here):
//   * The recurring cadence tick routes through the ZONED evaluate whenever
//     explicit zones exist (the per-zone runIn jobs are one-shot lifecycle
//     kicks, so the tick is the only recurring driver; the flat path would
//     evaluate every zone's rooms against thermostat1 and the flat model).
//   * Disabling the surface (toggle off, DAB off, or leaving the balance
//     strategy) removes the diagnostic children and mirrored state instead of
//     leaving them stale until uninstall.
//
// Run `./gradlew test --tests '*DiagnosticsWiring*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class Dabv2DiagnosticsWiringTest extends Specification {

  private static final String APP_PATH = 'src/hubitat-flair-vents-app.groovy'
  private static final String ROOM_DRIVER_PATH = 'src/hubitat-flair-vents-room-diagnostics-driver.groovy'
  private static final String SUMMARY_DRIVER_PATH = 'src/hubitat-flair-vents-zone-summary-driver.groovy'
  private static final String BUNDLER_PATH = 'tools/build_bundle.py'

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

  // ===========================================================================
  // 1. The publish call is wired into BOTH evaluate paths
  // ===========================================================================

  private static String methodBody(String source, String signature, String nextMarker) {
    int start = source.indexOf(signature)
    assert start >= 0 : "method not found: ${signature}"
    int end = source.indexOf(nextMarker, start)
    assert end > start : "boundary not found after ${signature}: ${nextMarker}"
    return source.substring(start, end)
  }

  def 'the flat single-zone evaluate publishes diagnostics'() {
    given:
    String app = new File(APP_PATH).text

    expect:
    methodBody(app, 'def runDabV2BalanceEvaluate()', 'def runDabV2ZonedEvaluate()')
        .contains('publishDabV2Diagnostics(')
  }

  def 'the zoned evaluate publishes diagnostics from the zone model'() {
    given:
    String app = new File(APP_PATH).text

    expect:
    methodBody(app, 'private void evaluateDabV2ZoneById(', 'private Map buildZoneVentsByRoomId(')
        .contains('publishDabV2Diagnostics(')
  }

  // ===========================================================================
  // 2. The driver sources exist with the exact names the app instantiates
  // ===========================================================================

  def 'both diagnostic driver sources exist and match the app name constants'() {
    given:
    String room = new File(ROOM_DRIVER_PATH).text
    String summary = new File(SUMMARY_DRIVER_PATH).text

    expect: 'definition names match DABV2_DIAG_ROOM_DRIVER / DABV2_DIAG_SUMMARY_DRIVER'
    room.contains("definition(name: 'Flair Vents Room Diagnostics', namespace: 'bot.flair'")
    summary.contains("definition(name: 'Flair Vents Zone Summary', namespace: 'bot.flair'")
  }

  // ===========================================================================
  // 3. Every emitted attribute is declared by its driver
  // ===========================================================================

  def 'the room driver declares every per-room key the app can emit'() {
    given: 'the real per-room diagnostics for a fully-populated fixture'
    def script = buildScript()
    def lib = script
    def model = lib.mioNewModel()
    Map rm = lib.lrnNewRoomModel()
    rm.cooling.baseline = 0.0173d
    rm.heating.baseline = 0.0211d
    model.roomEff.put('living', rm)
    def ve = [cooling: (Object) null, heating: (Object) null]
    def cool = lib.mioNewVentMode(); cool.leak = 0.12d; cool.knee = 75; cool.curve = lib.lrnSeedLinear(0.12d)
    def heat = lib.mioNewVentMode(); heat.leak = 0.12d; heat.knee = 75; heat.curve = lib.lrnSeedLinear(0.12d)
    ve.cooling = cool; ve.heating = heat
    model.ventEff.put('v-living', ve)
    Map zoneResult = [balancing: true, action: 'cooling', mode: 'cooling',
                      targets: ['living': 80.0d], airflowLimited: (['living'] as Set),
                      floorRequiredRooms: ([] as Set), predictedSpreadC: 1.4d]
    List roomData = [[roomId: 'living', tempC: 25.0, active: true, coolingRate: 0.1, ventIds: ['v-living']]]

    when:
    Map diag = script.gatherDabV2RoomDiagnostics(zoneResult, roomData, model, 22.0)
    Set emitted = (diag['living'] as Map).keySet()

    then: 'the fixture exercises the full attribute surface'
    emitted.containsAll(['active', 'temperature', 'signedErrorC', 'proposedOpenPct', 'airflowLimited',
                         'coolingEfficiency', 'heatingEfficiency', 'ventLeak', 'ventKnee'])

    and: 'each emitted key is a declared driver attribute'
    String driver = new File(ROOM_DRIVER_PATH).text
    emitted.every { driver.contains("attribute '${it}'") }
  }

  def 'the summary driver declares every zone-summary key the app can emit'() {
    given: 'a summary with the full strategy comparison metrics present'
    def script = buildScript()
    Map zoneResult = [balancing: true, action: 'cooling', mode: 'cooling',
                      targets: [:], airflowLimited: ([] as Set),
                      floorRequiredRooms: ([] as Set), predictedSpreadC: 0.6d]
    Map metrics = [(script.getDabV2ControlStrategy()): [avgSpreadC: 0.8d, maxSpreadC: 1.9d,
                                                        avgAdjustments: 3.2d, avgMovement: 11.0d,
                                                        avgErrorC: 0.4d]]

    when:
    Map sum = script.gatherDabV2SystemSummary(zoneResult, [:], [recalc24h: 2, hold24h: 5], metrics)

    then: 'the fixture exercises the full attribute surface'
    sum.keySet().containsAll(['spreadC', 'maxErrorC', 'status', 'recalc24h', 'hold24h', 'strategy',
                              'avgSpreadC', 'maxSpreadC', 'avgAdjustments', 'avgMovement', 'avgErrorC'])

    and: 'each emitted key is a declared driver attribute'
    String driver = new File(SUMMARY_DRIVER_PATH).text
    sum.keySet().every { driver.contains("attribute '${it}'") }
  }

  // ===========================================================================
  // 4. The bundle ships both drivers
  // ===========================================================================

  def 'the bundle packager includes both diagnostic drivers'() {
    given:
    String bundler = new File(BUNDLER_PATH).text

    expect:
    bundler.contains('bot.flair.FlairVentsRoomDiagnostics.groovy')
    bundler.contains(ROOM_DRIVER_PATH)
    bundler.contains('bot.flair.FlairVentsZoneSummary.groovy')
    bundler.contains(SUMMARY_DRIVER_PATH)
  }

  // ===========================================================================
  // 5. The recurring tick evaluates per zone when explicit zones exist
  // ===========================================================================

  def 'the balance tick routes through the ZONED evaluate when explicit zones exist'() {
    given: 'a balance install with two configured zones'
    def script = buildScript([controlStrategy: 'balance'])
    script.state.zones = [default: [zoneName: 'default'], upstairs: [zoneName: 'Upstairs']]
    Map calls = [zoned: 0, flat: 0, legacy: 0]
    script.metaClass.runDabV2ZonedEvaluate = { -> calls.zoned++ }
    script.metaClass.runDabV2BalanceEvaluate = { -> calls.flat++ }
    script.metaClass.evaluateRebalancingVents = { -> calls.legacy++ }

    when:
    script.selectAndRunDabV2Evaluate()

    then: 'each zone evaluates with its own thermostat and model slice'
    calls.zoned == 1
    calls.flat == 0
    calls.legacy == 0
  }

  def 'the balance tick keeps the flat path for a default-only or pre-zone install'() {
    given:
    def script = buildScript([controlStrategy: 'balance'])
    script.state.zones = zonesState
    Map calls = [zoned: 0, flat: 0]
    script.metaClass.runDabV2ZonedEvaluate = { -> calls.zoned++ }
    script.metaClass.runDabV2BalanceEvaluate = { -> calls.flat++ }

    when:
    script.selectAndRunDabV2Evaluate()

    then: 'the flat single-zone path (and its model storage) is unchanged'
    calls.flat == 1
    calls.zoned == 0

    where:
    zonesState << [null, [default: [zoneName: 'default']]]
  }

  // ===========================================================================
  // 6. Disabling the surface removes devices and mirrored state
  // ===========================================================================

  def 'turning diagnostics off removes the diagnostic children and mirrored state, leaving other children alone'() {
    given: 'diag children and a vent child exist from an earlier enabled period'
    def script = buildDeviceScript([dabEnabled: true, controlStrategy: 'balance',
                                    dabV2DiagnosticsEnabled: false])
    seedChild('dabv2-diag-room-living')
    seedChild('dabv2-diag-room-office')
    seedChild('dabv2-diag-summary')
    seedChild('vent-1')
    script.state.dabV2Diagnostics = [summary: [status: 'hold'], rooms: [:]]

    when:
    script.dabV2CleanupDiagnosticsIfDisabled()

    then: 'every diagnostic child is gone, the vent child is untouched'
    !devices.containsKey('dabv2-diag-room-living')
    !devices.containsKey('dabv2-diag-room-office')
    !devices.containsKey('dabv2-diag-summary')
    devices.containsKey('vent-1')

    and: 'the mirrored state is cleared'
    script.state.dabV2Diagnostics == null
  }

  def 'leaving the balance strategy also removes the diagnostic surface'() {
    given: 'the toggle is still on but the strategy moved to legacy (which never publishes)'
    def script = buildDeviceScript([dabEnabled: true, controlStrategy: 'dab',
                                    dabV2DiagnosticsEnabled: true])
    seedChild('dabv2-diag-room-living')
    seedChild('dabv2-diag-summary')

    when:
    script.dabV2CleanupDiagnosticsIfDisabled()

    then:
    devices.isEmpty()
  }

  def 'an ACTIVE diagnostics surface is left untouched by the reconciler'() {
    given:
    def script = buildDeviceScript([dabEnabled: true, controlStrategy: 'balance',
                                    dabV2DiagnosticsEnabled: true])
    seedChild('dabv2-diag-room-living')
    seedChild('dabv2-diag-summary')

    when:
    script.dabV2CleanupDiagnosticsIfDisabled()

    then:
    devices.containsKey('dabv2-diag-room-living')
    devices.containsKey('dabv2-diag-summary')
  }

  def 'initialize() reconciles the diagnostics surface on every settings save'() {
    given:
    String app = new File(APP_PATH).text

    expect:
    methodBody(app, 'def initialize()', 'private openAllVents(')
        .contains('dabV2CleanupDiagnosticsIfDisabled()')
  }

  // ===========================================================================
  // Harnesses
  // ===========================================================================

  private Object buildScript(Map userSettings = [:]) {
    final log = new CapturingLog()
    Map stateMap = [:]
    Map atomicStateMap = [:]
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> atomicStateMap
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': (['debugLevel': 1] + userSettings))
    script.state = stateMap
    script.atomicState = atomicStateMap
    return script
  }

  // Device-capable harness for the cleanup tests (getChildDevices/deleteChildDevice).
  Map devices
  private List deviceList

  private void seedChild(String dni) {
    Map self = [:]
    self.dni = dni
    self.getDeviceNetworkId = { -> self.dni }
    self.getLabel = { -> self.dni }
    def child = self as me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
    devices[dni] = child
    deviceList << child
  }

  private Object buildDeviceScript(Map userSettings) {
    final log = new CapturingLog()
    Map stateMap = [:]
    devices = [:]
    deviceList = []
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> [:]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      _ * getChildDevices() >> { new ArrayList(deviceList) }
      _ * getChildDevice(_) >> { String id -> devices[id] }
      _ * deleteChildDevice(_) >> { String dni ->
        devices.remove(dni)
        deviceList.removeAll { it.getDeviceNetworkId() == dni }
        return null
      }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': (['debugLevel': 1] + userSettings))
    script.state = stateMap
    script.atomicState = [:]
    return script
  }
}
