
// DAB v2 persistence wiring + legacy-state migration on load — fake-backed tests
// (Task 9.7; R12.1, R12.3, R13.5).
//
// App-orchestration (non-pure) persistence behavior exercised against
// lightweight fakes of the Hubitat surface (state, atomicState, child devices).
// The pure encode/decode/migrate/bound math lives in Dabv2ModelIo (task 7) and
// is unit/property-tested separately; these tests verify only the SEAM the app
// owns: reading/writing `state` and triggering migration on load.
//
//   1. Save then load is a faithful round-trip through `state`: the in-memory
//      learned model is encoded (+ bounded) to `state` via Dabv2ModelIo and
//      decoded back with the learned room/vent state intact (R12.1).
//   2. A legacy (pre-v2) payload present in `state` under the model key is
//      migrated on load with NO data loss — v1 per-room rates seed the v2 room
//      baselines — and the migrated v2 payload is persisted back (so the next
//      load is a straight-through decode) (R12.3).
//   3. New metric/counter fields are back-filled with documented defaults on
//      load, whether the source is v2, legacy, or empty (R13.5).
//   4. On a fresh upgrade with no model key in `state`, the legacy efficiency
//      data held in child-device attributes is migrated on load (R12.3), and
//      `initialize()` triggers the load/migration as part of the app lifecycle.
//
// Run `./gradlew test --tests '*Dabv2Persistence*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import spock.lang.Shared

class Dabv2PersistenceTest extends Specification {

  @Shared Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final String APP_FILE = Dabv2AppHarness.combinedAppText()
  private static final String MODEL_KEY = 'dabv2LearnedModel'
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

  // A fake Flair smart vent carrying the legacy per-room learned-rate attributes
  // that the existing backup/restore export reads.
  private Object fakeVent(String dni, String roomId, String roomName,
      Object coolingRate, Object heatingRate) {
    Map self = [:]
    self.dni = dni
    self.attrs = ['percent-open': 50, 'room-id': roomId, 'room-name': roomName,
                  'room-cooling-rate': coolingRate, 'room-heating-rate': heatingRate]
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
      'userSettingValues': ([debugLevel: 1, dabEnabled: true, safetyFloorPct: 40] + userSettings))
    script.state = stateMap
    script.atomicState = atomicStateMap
    return script
  }

  // ---------------------------------------------------------------------------
  // 1. Save -> load round-trip through `state` (R12.1)
  // ---------------------------------------------------------------------------

  def "saveDabV2Model encodes a schema-v2 payload to state that loadDabV2Model decodes back intact"() {
    setup:
    def script = buildScript()
    def model = lib.mioNewModel()
    def rm = lib.lrnNewRoomModel()
    rm.cooling.baseline = 0.0173d
    rm.cooling.n = 7
    rm.heating.baseline = 0.0211d
    rm.heating.n = 5
    model.roomEff.put('living', rm)

    when: 'the model is persisted, then loaded back from state'
    script.saveDabV2Model(model)

    then: 'state now holds a stamped schema-v2 payload'
    stateMap[MODEL_KEY] instanceof Map
    (stateMap[MODEL_KEY].v as int) == lib.MIO_SCHEMA_VERSION

    when:
    def loaded = script.loadDabV2Model()

    then: 'the learned room state survives the round-trip'
    loaded.roomEff.containsKey('living')
    Math.abs((loaded.roomEff.get('living').cooling.baseline as double) - 0.0173d) <= 1e-9d
    loaded.roomEff.get('living').cooling.n == 7
    Math.abs((loaded.roomEff.get('living').heating.baseline as double) - 0.0211d) <= 1e-9d
  }

  // ---------------------------------------------------------------------------
  // 2. Legacy payload in state migrated on load with no data loss (R12.3)
  // ---------------------------------------------------------------------------

  def "a legacy (pre-v2) payload in state is migrated on load with no data loss and persisted as v2"() {
    setup: 'a legacy single-rate efficiency wrapper stored under the model key (no v:2)'
    Map legacy = [
      efficiencyData: [
        globalRates     : [maxCoolingRate: 1.2, maxHeatingRate: 1.4],
        roomEfficiencies: [
          [roomId: 'r1', roomName: 'Bedroom', ventId: 'v1',
           coolingRate: 0.018, heatingRate: 0.022],
          [roomId: 'r2', roomName: 'Office', ventId: 'v2',
           coolingRate: 0.009, heatingRate: 0.0]
        ]
      ]
    ]
    def script = buildScript([:], [(MODEL_KEY): legacy])

    when:
    def loaded = script.loadDabV2Model()

    then: 'the legacy per-room rates seed the v2 room baselines (no data loss)'
    loaded.roomEff.containsKey('r1')
    loaded.roomEff.containsKey('r2')
    Math.abs((loaded.roomEff.get('r1').cooling.baseline as double) - 0.018d) <= 1e-9d
    Math.abs((loaded.roomEff.get('r1').heating.baseline as double) - 0.022d) <= 1e-9d
    Math.abs((loaded.roomEff.get('r2').cooling.baseline as double) - 0.009d) <= 1e-9d

    and: 'a per-vent effectiveness model is seeded for each migrated vent'
    loaded.ventEff.containsKey('v1')
    loaded.ventEff.containsKey('v2')

    and: 'the migrated model is persisted back as a stamped schema-v2 payload'
    stateMap[MODEL_KEY] instanceof Map
    (stateMap[MODEL_KEY].v as int) == lib.MIO_SCHEMA_VERSION
  }

  def "after migrating a legacy payload, the next load is an idempotent straight-through decode"() {
    setup:
    Map legacy = [roomEfficiencies: [
        [roomId: 'r1', roomName: 'Bedroom', ventId: 'v1', coolingRate: 0.018, heatingRate: 0.022]]]
    def script = buildScript([:], [(MODEL_KEY): legacy])

    when: 'load once (migrates + persists v2), capture the persisted payload, then load again'
    script.loadDabV2Model()
    Object afterFirst = lib.mioToJson(stateMap[MODEL_KEY] as Map)
    def second = script.loadDabV2Model()
    Object afterSecond = lib.mioToJson(stateMap[MODEL_KEY] as Map)

    then: 'the persisted v2 payload is stable across loads (idempotent migration)'
    afterFirst == afterSecond
    Math.abs((second.roomEff.get('r1').cooling.baseline as double) - 0.018d) <= 1e-9d
  }

  // ---------------------------------------------------------------------------
  // 3. New metric/counter fields back-filled with documented defaults (R13.5)
  // ---------------------------------------------------------------------------

  def "loadDabV2Model back-fills counter defaults regardless of source payload"() {
    expect: 'an empty/fresh install yields the documented counter defaults'
    def fresh = buildScript().loadDabV2Model()
    fresh.counters != null
    fresh.counters.recalc24h == 0
    fresh.counters.hold24h == 0
    fresh.counters.containsKey('windowStartMs')
    fresh.metrics != null

    and: 'a legacy payload missing counters also gets them back-filled'
    Map legacy = [roomEfficiencies: [
        [roomId: 'r1', ventId: 'v1', coolingRate: 0.018, heatingRate: 0.022]]]
    def migrated = buildScript([:], [(MODEL_KEY): legacy]).loadDabV2Model()
    migrated.counters != null
    migrated.counters.recalc24h == 0
    migrated.counters.hold24h == 0
  }

  // ---------------------------------------------------------------------------
  // 4. Legacy device-attribute migration on load + lifecycle trigger (R12.3)
  // ---------------------------------------------------------------------------

  def "on a fresh upgrade with no model key, legacy device-attribute efficiency data is migrated on load"() {
    setup: 'two vents carrying legacy learned rates, and no v2 payload in state'
    devices = [
      fakeVent('v1', 'r1', 'Bedroom', 0.018, 0.022),
      fakeVent('v2', 'r2', 'Office', 0.009, 0.011)
    ]
    def script = buildScript([:], [:], [maxCoolingRate: 1.2, maxHeatingRate: 1.4])

    when:
    def loaded = script.loadDabV2Model()

    then: 'the device-attribute rates seed the v2 room baselines'
    loaded.roomEff.containsKey('r1')
    loaded.roomEff.containsKey('r2')
    Math.abs((loaded.roomEff.get('r1').cooling.baseline as double) - 0.018d) <= 1e-9d
    Math.abs((loaded.roomEff.get('r2').heating.baseline as double) - 0.011d) <= 1e-9d

    and: 'the migration is persisted as schema-v2'
    (stateMap[MODEL_KEY].v as int) == lib.MIO_SCHEMA_VERSION
  }

  def "initialize triggers the learned-model load/migration as part of the app lifecycle"() {
    setup: 'a legacy payload present and a minimal install (no thermostat configured)'
    Map legacy = [roomEfficiencies: [
        [roomId: 'r1', roomName: 'Bedroom', ventId: 'v1', coolingRate: 0.018, heatingRate: 0.022]]]
    def script = buildScript([:], [(MODEL_KEY): legacy])

    when:
    script.initialize()

    then: 'the legacy payload was migrated and persisted as schema-v2 during initialize'
    stateMap[MODEL_KEY] instanceof Map
    (stateMap[MODEL_KEY].v as int) == lib.MIO_SCHEMA_VERSION
  }
}
