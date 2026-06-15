
// R7.3 DAB reset / recalibrate specs (Task 16.1; R7.13–R7.21).
//
// STRICT TDD (Task 16.1 — RED). These specs encode the REQUIRED behavior that
// Task 16.2 ("Implement resetDabLearning(scope)") must satisfy. They are written
// to FAIL against today's app (the `resetDabLearning` action does not yet exist)
// and to pass only once Task 16.2 is implemented.
//
// ===========================================================================
// CONTRACT — what Task 16.2 MUST implement (IMPLEMENT TO THIS BEHAVIOR)
// ===========================================================================
//
// New user-initiated action:
//   resetDabLearning(String scope, Boolean confirmed = null)
//
// Scope (D5 = per-Zone with an all-Zones option; design §7.3):
//   - scope == a zoneId       -> clear ONLY that zone's learned model entry
//                                `state.zones[zoneId][DABV2_MODEL_STATE_KEY]`
//                                (R7.14 single-Zone, R7.15).
//   - scope == 'all-zones'    -> iterate `getZoneIds()` and clear EACH zone's
//                                learned model entry (R7.14 all-Zones, R7.15).
//
// Confirmation (R7.18 / R7.20):
//   - Clears ONLY when explicitly confirmed. `confirmed == true` confirms;
//     when `confirmed` is null the action falls back to the UI confirmation
//     flag `settings.dabResetConfirm`. Without confirmation NOTHING is cleared
//     (R7.20 "SHALL NOT clear learned data without an explicit user action").
//
// Completion surfacing (R7.19):
//   - A confirmed reset surfaces a completion confirmation in
//     `state.dabResetStatus` (a "✓ ..." message).
//
// Relearning (R7.16):
//   - After a reset, the zone's persisted learned model loads back EMPTY (a
//     fresh model), so DAB begins relearning on subsequent HVAC cycles — even
//     for the 'default' zone whose loader would otherwise re-seed from the
//     legacy device-attribute efficiency data.
//
// Backup/restore preserved (R7.21):
//   - The existing `exportEfficiencyData` / `mioExportModel` / `mioImportModel`
//     backup/restore path is untouched by a reset (device-attribute efficiency
//     export still works; the export/import seam still exists).
//
// Built on the existing zone / persistence app-orchestration harness
// (HubitatAppSandbox over the shipped combined app+library text).
//
// Run `./gradlew test --tests '*DabResetLearning*'`.
//
// _Requirements: 7.13, 7.14, 7.15, 7.16, 7.17, 7.18, 7.19, 7.20, 7.21_
// _Design: §R7.3_

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import spock.lang.Shared

class DabResetLearningSpec extends Specification {

  @Shared Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final String APP_FILE = Dabv2AppHarness.combinedAppText()
  private static final String MODEL_KEY = 'dabv2LearnedModel'
  private static final String SCOPE_ALL = 'all-zones'
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

  // A vent child device carrying the legacy device-attribute efficiency data the
  // backup/restore export reads (room-cooling-rate / room-heating-rate / room-id).
  private Object fakeVent(String dni, String roomId = null, double cool = 0.0d, double heat = 0.0d) {
    Map self = [:]
    self.dni = dni
    self.attrs = ['percent-open': 50,
                  'room-id': roomId,
                  'room-name': roomId,
                  'room-cooling-rate': cool,
                  'room-heating-rate': heat]
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
      _ * unschedule(_) >> { }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ([debugLevel: 1, dabEnabled: true, safetyFloorPct: 40] + userSettings))
    script.state = stateMap
    script.atomicState = atomicStateMap
    return script
  }

  // A learned model carrying one named room with distinct curves so a zone's
  // model is identifiable.
  private Map modelWithRoom(String roomId, double coolBaseline, int coolN, double heatBaseline) {
    def model = lib.mioNewModel()
    def rm = lib.lrnNewRoomModel()
    rm.cooling.baseline = coolBaseline
    rm.cooling.n = coolN
    rm.heating.baseline = heatBaseline
    model.roomEff.put(roomId, rm)
    return model
  }

  // The compact schema-v2 encoded payload the save seam writes; used to pre-seed
  // a zone's slice without going through the method under test.
  private Map encoded(Map model) {
    return lib.mioBound(model).encoded as Map
  }

  // ---------------------------------------------------------------------------
  // 1. Per-Zone scope clears ONLY the selected zone's learned model (R7.14/R7.15)
  // ---------------------------------------------------------------------------

  def 'per-Zone reset clears only the selected zone learned model, leaving other zones intact'() {
    setup: 'two zones, each with its own seeded learned model'
    def modelA = modelWithRoom('living', 0.0173d, 7, 0.0211d)
    def modelB = modelWithRoom('office', 0.0090d, 3, 0.0110d)
    def script = buildScript([:],
      [zones: ['office': [(MODEL_KEY): encoded(modelB)], 'living': [(MODEL_KEY): encoded(modelA)]]])

    when: 'the user resets just the living zone (explicitly confirmed)'
    script.resetDabLearning('living', true)

    then: "the living zone's learned model is cleared (loads back empty)"
    script.loadDabV2Model('living').roomEff.isEmpty()

    and: "the office zone's learned model is untouched"
    script.loadDabV2Model('office').roomEff.containsKey('office')
  }

  // ---------------------------------------------------------------------------
  // 2. all-Zones scope iterates every zone and clears each (R7.14/R7.15)
  // ---------------------------------------------------------------------------

  def 'all-Zones reset clears every zone learned model'() {
    setup: 'two zones with seeded models'
    def modelA = modelWithRoom('living', 0.0173d, 7, 0.0211d)
    def modelB = modelWithRoom('office', 0.0090d, 3, 0.0110d)
    def script = buildScript([:],
      [zones: ['office': [(MODEL_KEY): encoded(modelB)], 'living': [(MODEL_KEY): encoded(modelA)]]])

    when: 'the user resets all zones (explicitly confirmed)'
    script.resetDabLearning(SCOPE_ALL, true)

    then: 'every zone learned model is cleared'
    script.loadDabV2Model('living').roomEff.isEmpty()
    script.loadDabV2Model('office').roomEff.isEmpty()
  }

  // ---------------------------------------------------------------------------
  // 3. Requires explicit confirmation; never clears without an action (R7.18/R7.20)
  // ---------------------------------------------------------------------------

  def 'reset without explicit confirmation does NOT clear learned data'() {
    setup: 'a zone with a seeded learned model'
    def modelA = modelWithRoom('living', 0.0173d, 7, 0.0211d)
    def script = buildScript([:], [zones: ['living': [(MODEL_KEY): encoded(modelA)]]])

    when: 'a reset is invoked WITHOUT confirmation (confirmed=false)'
    script.resetDabLearning('living', false)

    then: 'the learned model is preserved (nothing cleared)'
    script.loadDabV2Model('living').roomEff.containsKey('living')
  }

  def 'reset with no confirmation flag set (settings-backed) does NOT clear learned data'() {
    setup: 'a zone with a seeded learned model and no dabResetConfirm flag'
    def modelA = modelWithRoom('living', 0.0173d, 7, 0.0211d)
    def script = buildScript([:], [zones: ['living': [(MODEL_KEY): encoded(modelA)]]])

    when: 'a reset is invoked relying on the (unset) UI confirmation flag'
    script.resetDabLearning('living')

    then: 'the learned model is preserved (nothing cleared)'
    script.loadDabV2Model('living').roomEff.containsKey('living')
  }

  // ---------------------------------------------------------------------------
  // 4. Completion surfacing (R7.19)
  // ---------------------------------------------------------------------------

  def 'a completed reset surfaces a completion confirmation to the user'() {
    setup:
    def modelA = modelWithRoom('living', 0.0173d, 7, 0.0211d)
    def script = buildScript([:], [zones: ['living': [(MODEL_KEY): encoded(modelA)]]])

    when:
    script.resetDabLearning('living', true)

    then: 'a user-visible completion status is set'
    stateMap.dabResetStatus instanceof String
    ((String) stateMap.dabResetStatus).startsWith('✓')
  }

  // ---------------------------------------------------------------------------
  // 5. Relearning begins on subsequent cycles — even for the default zone whose
  //    loader would otherwise re-seed from device-attribute efficiency (R7.16)
  // ---------------------------------------------------------------------------

  def 'after reset the default zone learned model loads back empty so relearning starts fresh'() {
    setup: 'the default zone has a seeded model AND device attributes still carry learned rates'
    devices = [fakeVent('vent-1', 'living', 0.5d, 0.6d)]
    def modelA = modelWithRoom('living', 0.0173d, 7, 0.0211d)
    def script = buildScript([:], [zones: ['default': [(MODEL_KEY): encoded(modelA)]]])

    when: 'the default zone is reset'
    script.resetDabLearning('default', true)

    then: "the default zone loads back EMPTY (not re-seeded from device attributes)"
    script.loadDabV2Model('default').roomEff.isEmpty()
  }

  // ---------------------------------------------------------------------------
  // 6. Backup/restore path preserved (R7.21)
  // ---------------------------------------------------------------------------

  def 'reset preserves the existing efficiency backup/restore path'() {
    setup: 'a vent carrying device-attribute efficiency data (the backup source)'
    devices = [fakeVent('vent-1', 'living', 0.5d, 0.6d)]
    def modelA = modelWithRoom('living', 0.0173d, 7, 0.0211d)
    def script = buildScript([:], [zones: ['default': [(MODEL_KEY): encoded(modelA)]]],
      [maxCoolingRate: 1.2d, maxHeatingRate: 1.4d])

    when: 'a reset runs'
    script.resetDabLearning('default', true)

    then: 'the device-attribute backup export is unaffected (still exports the room)'
    def exported = script.exportEfficiencyData()
    exported.roomEfficiencies.find { it.roomId == 'living' } != null

    and: 'the export/import backup seam still exists (restore path preserved)'
    script.metaClass.methods.find { it.name == 'exportEfficiencyData' } != null
    script.metaClass.methods.find { it.name == 'importEfficiencyData' } != null
    lib.metaClass.methods.find { it.name == 'mioExportModel' } != null
    lib.metaClass.methods.find { it.name == 'mioImportModel' } != null
  }
}
