
// Property 24 — Zone-keyed wrap-as-`zones['default']` migration with no data loss
// (Task 10.1, strict-TDD RED; R8.27, R8.28; design §4.1, §4.3, §5.5, Property 24).
//
// This is an APP-ORCHESTRATION property: the pure `mio*` seam already migrates
// the learned model losslessly (covered by Property 15), but the v0.236 schema
// bump that WRAPS today's single-thermostat config as the `'default'` zone does
// NOT exist yet (that is task 10.2). On an unfixed app there is no
// `state.zones['default']`, no `migrateToZoneModel()` entry point, and the
// learned model still lives flat under `state['dabv2LearnedModel']`. This spec
// therefore FAILS until the zone-keyed model + idempotent wrap-as-default
// migration are implemented.
//
// Property 24 (design §7, verbatim intent):
//   For any legacy/pre-v2 persisted payload (or device-attribute export),
//   `mioMigrate` → `mioEncode`/`mioDecode` preserves all learned curves/counters
//   (new fields back-filled with defaults). The Option A schema bump additionally
//   wraps the existing single-thermostat config as `zones['default']` —
//   relocating the current learned model under
//   `state.zones['default']['dabv2LearnedModel']` and moving the existing
//   thermostat/assignments/floor into the default zone — with no curves/counters
//   lost. Migrating twice is a no-op (idempotent): an already-wrapped shape is
//   detected and left unchanged.
//
// Contract exercised (the seam task 10.2 must satisfy):
//   - `migrateToZoneModel()` wraps the flat install into `state.zones['default']`.
//   - `state.zones['default']['dabv2LearnedModel']` holds the relocated, schema-v2
//     learned model preserving every room baseline / vent entry / counter that
//     `mioMigrate(legacy)` yields (no data loss).
//   - `state.zones['default']` carries the relocated config snapshot:
//     `thermostat`, `assignedVentIds`, `safetyFloorPct`, `additionalStandardVents`.
//   - Re-running `migrateToZoneModel()` is a no-op (idempotent) — the wrapped
//     `state.zones` JSON is byte-for-byte stable across a second migration.
//
// Run `./gradlew test --tests '*ZoneMigrationNoDataLoss*'` (or '*Property*').

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import spock.lang.Shared

class ZoneMigrationNoDataLossPropertySpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its pure `mio*`
  // helpers and `@Field` constants are callable off-device for the oracle.
  @Shared Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final String APP_FILE = Dabv2AppHarness.combinedAppText()
  private static final String MODEL_KEY = 'dabv2LearnedModel'
  private static final String DEFAULT_ZONE = 'default'
  private static final double EPS = 1e-9d
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

  private Object fakeVent(String dni) {
    Map self = [:]
    self.dni = dni
    self.attrs = ['percent-open': 50]
    self.getDeviceNetworkId = { -> self.dni }
    self.getId = { -> self.dni }
    self.hasAttribute = { Object... a -> self.attrs.containsKey(a[0]) }
    self.currentValue = { Object... a -> self.attrs[a[0]] }
    self.toString = { -> "FakeVent(${self.dni})".toString() }
    return self as ChildDeviceWrapper
  }

  private Object buildScript(Map userSettings = [:], Map state = [:], Map atomic = [:]) {
    log = new CapturingLog()
    stateMap = state
    atomicStateMap = atomic
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> atomicStateMap
      _ * getLog() >> log
      _ * getChildDevices() >> devices
      _ * getChildDevice(_) >> { String id -> devices.find { it.getDeviceNetworkId() == id } }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ([debugLevel: 1, dabEnabled: true] + userSettings))
    script.state = stateMap
    script.atomicState = atomicStateMap
    return script
  }

  def 'Feature: hubitat-flair-vents-dab-v2, Property 24: Migration with no data loss'() {
    expect: 'wrap-as-default migration preserves the model + config and is idempotent for every payload'
    checkWrapAsDefaultMigration(i)

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }

  private boolean checkWrapAsDefaultMigration(int i) {
    PropertyGen g = PropertyGen.forIteration(i)

    // ---- Arrange: a random legacy/pre-v2 single-thermostat install ----------
    Map legacy = randomLegacyPayload(g)
    int floorPct = g.nextInt(10, 70)
    int convVents = g.nextInt(0, 5)
    String thermostatId = 'thermo-' + g.nextInt(0, 99)
    int ventCount = g.nextInt(1, 4)
    devices = (0..<ventCount).collect { fakeVent('vent-' + it) }
    List<String> expectedVentIds = devices.collect { it.getDeviceNetworkId() }.sort()

    def script = buildScript(
      [thermostat1: thermostatId,
       safetyFloorPct: floorPct,
       thermostat1AdditionalStandardVents: convVents],
      [(MODEL_KEY): legacy])

    // The pure oracle: what the lossless model migration must preserve.
    Map expectedModel = lib.mioMigrate(legacy)

    // ---- Act: wrap the flat install as zones['default'] ---------------------
    script.migrateToZoneModel()

    // ---- Assert: state.zones['default'] exists and wraps the install --------
    assert stateMap.zones instanceof Map
    Map zones = (Map) stateMap.zones
    assert zones.containsKey(DEFAULT_ZONE)
    Map defaultZone = (Map) zones.get(DEFAULT_ZONE)
    assert defaultZone != null

    // (1) Learned model relocated under the default zone, schema-v2 stamped.
    assert defaultZone.get(MODEL_KEY) instanceof Map
    Map relocated = (Map) defaultZone.get(MODEL_KEY)
    assert (relocated.get('v') as int) == lib.MIO_SCHEMA_VERSION

    // (2) No data loss: every migrated room baseline / vent entry / counter is
    //     preserved in the relocated payload (decode == oracle).
    Map relocatedModel = lib.mioDecode(relocated)
    assertNoModelDataLoss(expectedModel, relocatedModel)

    // (3) Config relocated into the default zone (thermostat/assignments/floor).
    assert defaultZone.get('thermostat') == thermostatId
    assert defaultZone.get('safetyFloorPct') == floorPct
    assert defaultZone.get('additionalStandardVents') == convVents
    assert (defaultZone.get('assignedVentIds') as List).sort() == expectedVentIds

    // ---- Assert: migrating twice is a no-op (idempotent) --------------------
    String afterFirst = lib.mioToJson((Map) stateMap.zones)
    script.migrateToZoneModel()
    String afterSecond = lib.mioToJson((Map) stateMap.zones)
    assert afterFirst == afterSecond

    return true
  }

  /** Every migrated room baseline, vent key and counter survives the relocation. */
  private void assertNoModelDataLoss(Map expected, Map actual) {
    // Counters back-filled and preserved.
    assert actual.counters != null
    ((Map) expected.counters).each { k, v -> assert ((Map) actual.counters).get(k) == v }

    // Room efficiency baselines preserved (no curves dropped).
    ((Map) expected.roomEff).each { roomId, rm ->
      assert ((Map) actual.roomEff).containsKey(roomId)
      Map ar = (Map) ((Map) actual.roomEff).get(roomId)
      assertBaseline(((Map) rm).cooling, ar.cooling)
      assertBaseline(((Map) rm).heating, ar.heating)
    }

    // Per-vent effectiveness entries preserved.
    ((Map) expected.ventEff).each { ventId, ve ->
      assert ((Map) actual.ventEff).containsKey(ventId)
    }
  }

  private void assertBaseline(Object expMode, Object actMode) {
    Object eb = ((Map) expMode)?.baseline
    Object ab = ((Map) actMode)?.baseline
    if (eb == null) {
      assert ab == null
    } else {
      assert ab != null
      assert Math.abs((ab as double) - (eb as double)) <= EPS
    }
  }

  /** A random legacy/pre-v2 export wrapper (single-rate per-room learned state). */
  private Map randomLegacyPayload(PropertyGen g) {
    int rooms = g.nextInt(1, 3)
    List recs = []
    for (int r = 0; r < rooms; r++) {
      recs << [roomId: 'room-' + r, roomName: 'Room ' + r, ventId: 'vent-' + r,
               coolingRate: g.nextDouble(0.01d, 1.2d),
               heatingRate: g.nextDouble(0.01d, 1.2d)]
    }
    return [
      exportMetadata: [version: '0.235', exportDate: '2025-01-01T00:00:00Z'],
      efficiencyData: [
        globalRates: [maxCoolingRate: 1.4d, maxHeatingRate: 1.2d],
        roomEfficiencies: recs
      ]
    ]
  }
}
