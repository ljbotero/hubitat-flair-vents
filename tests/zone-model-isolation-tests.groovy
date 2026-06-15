
// Per-Zone Learned-Model Isolation Tests
//
// Task 12.1 (STRICT-TDD RED): under Option A one App_Instance hosts N zones
// under `state.zones[zoneId]`, and each zone owns its OWN learned efficiency
// model stored at `state.zones[zoneId][DABV2_MODEL_STATE_KEY]` (design §5.3,
// §4.2/§4.4). The DAB v2 evaluate loop must iterate the zones and read/write
// ONLY each zone's own model entry: no zone reads or writes another zone's
// `state.zones[zoneId][DABV2_MODEL_STATE_KEY]`, and a device that is not in a
// zone's assigned set is neither commanded nor counted by that zone
// (R2.4, R2.5, R2.7, R2.8).
//
// These specs are EXPECTED TO FAIL until task 12.2 implements the zone-keyed
// persistence seam and the zone-iterating evaluate loop. Task 10.2 created the
// zone-keyed model (`state.zones[zoneId]`, `migrateToZoneModel()`) and task 11.2
// added the device->zone assignment seam (`getZoneCommandableVentIds`); what
// does NOT exist yet is per-zone model load/save keyed by `zoneId` and the
// zone-iterating evaluate entry point. The shipped `loadDabV2Model()` /
// `saveDabV2Model(Map model)` still operate on the FLAT `state[DABV2_MODEL_STATE_KEY]`,
// not on a per-zone slice.
//
// Contract exercised (the seam task 12.2 must satisfy -- IMPLEMENT TO THESE NAMES):
//   - `loadDabV2Model(String zoneId)`
//       -> loads (and migrates-on-load with no data loss) the learned model from
//          `state.zones[zoneId][DABV2_MODEL_STATE_KEY]` ONLY. Reads no other
//          zone's slice (R2.8).
//   - `saveDabV2Model(String zoneId, Map model)`
//       -> encodes + size-bounds `model` and writes it to
//          `state.zones[zoneId][DABV2_MODEL_STATE_KEY]` ONLY. Writes no other
//          zone's slice (R2.8). Mirrors the bounding/encoding contract of the
//          existing flat `saveDabV2Model(Map)`.
//   - `runDabV2ZonedEvaluate()`  (the zone-iterating evaluate entry point)
//       -> iterates `state.zones[zoneId]`, builds plain-Map inputs per zone from
//          ONLY that zone's commandable vents (`getZoneCommandableVentIds`),
//          runs one independent DAB control loop per zone, and persists each
//          zone's model via `saveDabV2Model(zoneId, ...)` (R2.7). A device not in
//          a zone's assigned set is neither commanded nor counted by that zone
//          (R2.4, R2.5) -- `getZoneCommandableVentIds` is reused as the testable
//          proxy for "never commanded / never counted".
//
// Built on the existing zone / persistence app-orchestration harness setup
// (HubitatAppSandbox over the shipped combined app+library text).
//
// Run `./gradlew test --tests '*ZoneModelIsolation*'`.
//
// _Requirements: 2.4, 2.5, 2.7, 2.8_
// _Design: §5.3, §4.2, §4.4_

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import spock.lang.Shared

class ZoneModelIsolationSpec extends Specification {

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

  // A vent child device: identified by DNI, advertises `percent-open`.
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
      'userSettingValues': ([debugLevel: 1, dabEnabled: true, safetyFloorPct: 40] + userSettings))
    script.state = stateMap
    script.atomicState = atomicStateMap
    return script
  }

  // A learned model carrying one named room with distinct cooling/heating curves,
  // so two zones' models are individually identifiable.
  private Map modelWithRoom(String roomId, double coolBaseline, int coolN, double heatBaseline) {
    def model = lib.mioNewModel()
    def rm = lib.lrnNewRoomModel()
    rm.cooling.baseline = coolBaseline
    rm.cooling.n = coolN
    rm.heating.baseline = heatBaseline
    model.roomEff.put(roomId, rm)
    return model
  }

  // The compact schema-v2 encoded payload the save seam writes (matches the
  // existing flat `saveDabV2Model` encode/bound contract), used to pre-seed a
  // zone's slice without going through the method under test.
  private Map encoded(Map model) {
    return lib.mioBound(model).encoded as Map
  }

  // ---------------------------------------------------------------------------
  // 1. Per-zone save -> load round-trip writes/reads ONLY that zone's slice (R2.8)
  // ---------------------------------------------------------------------------

  def 'R2.8: saveDabV2Model(zoneId, model) persists under state.zones[zoneId] and loadDabV2Model(zoneId) round-trips it'() {
    setup: 'two configured zones, each with an (empty) model slot'
    def script = buildScript([:], [zones: ['A': [:], 'B': [:]]])
    def modelA = modelWithRoom('living', 0.0173d, 7, 0.0211d)

    when: 'zone A persists its learned model via the per-zone save seam'
    script.saveDabV2Model('A', modelA)

    then: 'state.zones.A holds a stamped schema-v2 payload (zone B untouched)'
    ((Map) stateMap.zones).A[MODEL_KEY] instanceof Map
    (((Map) stateMap.zones).A[MODEL_KEY].v as int) == lib.MIO_SCHEMA_VERSION
    !(((Map) stateMap.zones).B?.get(MODEL_KEY))

    when: 'zone A loads its model back'
    def loadedA = script.loadDabV2Model('A')

    then: "zone A's learned room state survives the round-trip"
    loadedA.roomEff.containsKey('living')
    Math.abs((loadedA.roomEff.get('living').cooling.baseline as double) - 0.0173d) <= 1e-9d
    loadedA.roomEff.get('living').cooling.n == 7
  }

  // ---------------------------------------------------------------------------
  // 2. Writing one zone's model never writes another zone's slice (R2.8)
  // ---------------------------------------------------------------------------

  def "R2.8: persisting zone A's model leaves zone B's state.zones[zoneId] slice byte-for-byte unchanged"() {
    setup: "zone B already holds its own learned model; capture its slice"
    def modelB = modelWithRoom('office', 0.0090d, 3, 0.0110d)
    def script = buildScript([:], [zones: ['A': [:], 'B': [(MODEL_KEY): encoded(modelB)]]])
    Object bBefore = lib.mioToJson(((Map) stateMap.zones).B[MODEL_KEY] as Map)

    when: "zone A persists a DIFFERENT learned model"
    def modelA = modelWithRoom('living', 0.0173d, 7, 0.0211d)
    script.saveDabV2Model('A', modelA)

    then: "zone B's model slice is untouched (no cross-zone write)"
    lib.mioToJson(((Map) stateMap.zones).B[MODEL_KEY] as Map) == bBefore

    and: "zone A now has its own distinct model slice"
    ((Map) stateMap.zones).A[MODEL_KEY] instanceof Map
    ((Map) stateMap.zones).A[MODEL_KEY] != ((Map) stateMap.zones).B[MODEL_KEY]
  }

  // ---------------------------------------------------------------------------
  // 3. loadDabV2Model(zoneId) reads ONLY its own zone (no cross-zone read) (R2.8)
  // ---------------------------------------------------------------------------

  def 'R2.8: loadDabV2Model(zoneId) returns only its own zone model and never another zone\'s rooms'() {
    setup: "zone A learned 'living'; zone B learned 'office'"
    def modelA = modelWithRoom('living', 0.0173d, 7, 0.0211d)
    def modelB = modelWithRoom('office', 0.0090d, 3, 0.0110d)
    def script = buildScript([:],
      [zones: ['A': [(MODEL_KEY): encoded(modelA)], 'B': [(MODEL_KEY): encoded(modelB)]]])

    when:
    def loadedA = script.loadDabV2Model('A')
    def loadedB = script.loadDabV2Model('B')

    then: "zone A sees only its own room, never zone B's"
    loadedA.roomEff.containsKey('living')
    !loadedA.roomEff.containsKey('office')

    and: "zone B sees only its own room, never zone A's"
    loadedB.roomEff.containsKey('office')
    !loadedB.roomEff.containsKey('living')
  }

  // ---------------------------------------------------------------------------
  // 4. A device not in a zone's assigned set is neither commanded nor counted (R2.4/R2.5/R2.7)
  // ---------------------------------------------------------------------------

  def 'R2.4/R2.5/R2.7: a device outside a zone\'s assigned set is neither commanded nor counted by that zone'() {
    setup: 'vent-1 -> zone A, vent-2 -> zone B, vent-3 unassigned'
    devices = [fakeVent('vent-1'), fakeVent('vent-2'), fakeVent('vent-3')]
    Map zones = [
      'A': [assignedVentIds: ['vent-1'], assignedPuckIds: []],
      'B': [assignedVentIds: ['vent-2'], assignedPuckIds: []]
    ]
    def script = buildScript([:], [zones: zones])

    expect: "zone A commands/counts only its own assigned vent (foreign + unassigned vents excluded)"
    (script.getZoneCommandableVentIds('A') as List) == ['vent-1']
    !(script.getZoneCommandableVentIds('A') as List).contains('vent-2')
    !(script.getZoneCommandableVentIds('A') as List).contains('vent-3')

    and: "zone B commands/counts only its own assigned vent"
    (script.getZoneCommandableVentIds('B') as List) == ['vent-2']
    !(script.getZoneCommandableVentIds('B') as List).contains('vent-3')

    and: "the zone-iterating evaluate entry point exists (one independent loop per zone, R2.7)"
    script.metaClass.methods.find { it.name == 'runDabV2ZonedEvaluate' } != null
  }
}
