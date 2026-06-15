
// Zone Assignment Uniqueness + Unassigned-Device Inertness Tests
//
// Task 11.1 (STRICT-TDD RED): the per-zone device-assignment model must (a) let
// each zone choose from ALL of the SID's vents/pucks, (b) constrain every device
// to at most one zone (assigning to a zone removes it from any other zone's set),
// and (c) make a device assigned to NO zone inert -- excluded from all balancing /
// combined-airflow and never commanded.
//
// These specs are EXPECTED TO FAIL until task 11.2 implements the per-zone config
// UI sections, the device->zone assignment, the uniqueness rule, and the
// unassigned-device exclusion. Task 10.2 already created the zone-keyed model
// (`state.zones[zoneId]` with `assignedVentIds`/`assignedPuckIds`) and
// `migrateToZoneModel()`; what does NOT exist yet is the assignment seam below.
//
// Contract exercised (the seam task 11.2 must satisfy -- IMPLEMENT TO THESE NAMES):
//   - `getZoneSelectableDeviceIds()`
//       -> every vent and every puck DNI in the instance/SID, as the selectable
//          candidate set offered to each zone (R2.1, R2.2). Zone-independent:
//          every zone may choose from the full set.
//   - `assignDeviceToZone(String deviceId, String zoneId)`
//       -> adds the device to `state.zones[zoneId]`'s assigned set (assignedVentIds
//          for a vent, assignedPuckIds for a puck) AND removes it from every OTHER
//          zone's assigned sets, so the device belongs to at most one zone (R2.3).
//   - `getUnassignedDeviceIds()`
//       -> the vents/pucks that exist in the SID but are in no zone's assigned set;
//          these are excluded from all balancing and from every zone's
//          combined-airflow computation (R2.20).
//   - `getZoneCommandableVentIds(String zoneId)`
//       -> the vents a zone will actually command (its own assigned vents that
//          still exist). A device in no zone appears in NO zone's commandable set,
//          so it is never commanded and is left at its last position (R2.4, R2.5,
//          R2.21). Used here as the testable proxy for "never commanded".
//
// Built on the existing zone-migration / app-orchestration harness setup
// (HubitatAppSandbox over the shipped combined app+library text).
//
// Run `./gradlew test --tests '*ZoneAssignmentUniqueness*'`.
//
// _Requirements: 2.1, 2.2, 2.3, 2.20, 2.21_
// _Design: §4.5, §R2 (R2.1-R2.5, R2.20, R2.21)_

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class ZoneAssignmentUniquenessSpec extends Specification {

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

  // A puck child device: identified by DNI, NO `percent-open` (advertises temperature).
  private Object fakePuck(String dni) {
    Map self = [:]
    self.dni = dni
    self.attrs = ['temperature': 71.0]
    self.getDeviceNetworkId = { -> self.dni }
    self.getId = { -> self.dni }
    self.hasAttribute = { Object... a -> self.attrs.containsKey(a[0]) }
    self.currentValue = { Object... a -> self.attrs[a[0]] }
    self.toString = { -> "FakePuck(${self.dni})".toString() }
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

  // Which zones currently list `deviceId` in any assigned set (vents or pucks).
  private List zonesContaining(Map zones, String deviceId) {
    List result = []
    (zones ?: [:]).each { zoneId, zone ->
      List vents = (zone?.assignedVentIds ?: []) as List
      List pucks = (zone?.assignedPuckIds ?: []) as List
      if (vents.contains(deviceId) || pucks.contains(deviceId)) {
        result << zoneId
      }
    }
    return result
  }

  def 'R2.1/R2.2: every vent and every puck in the SID is selectable for a zone'() {
    setup: 'a SID with three vents and two pucks discovered as child devices'
    devices = [fakeVent('vent-1'), fakeVent('vent-2'), fakeVent('vent-3'),
               fakePuck('puck-1'), fakePuck('puck-2')]
    def script = buildScript([:], [zones: ['default': [assignedVentIds: [], assignedPuckIds: []]]])

    when: 'a zone opens its device assignment selector'
    def selectable = script.getZoneSelectableDeviceIds()

    then: 'every vent and every puck in the SID is offered as an individually selectable candidate'
    (selectable as Set) == (['vent-1', 'vent-2', 'vent-3', 'puck-1', 'puck-2'] as Set)
  }

  def 'R2.3: assigning a device to a zone removes it from any other zone (membership in at most one zone)'() {
    setup: 'vent-1 and vent-2 belong to zone A; vent-3 belongs to zone B'
    devices = [fakeVent('vent-1'), fakeVent('vent-2'), fakeVent('vent-3')]
    Map zones = [
      'A': [assignedVentIds: ['vent-1', 'vent-2'], assignedPuckIds: []],
      'B': [assignedVentIds: ['vent-3'], assignedPuckIds: []]
    ]
    def script = buildScript([:], [zones: zones])

    when: 'vent-1 is reassigned to zone B'
    script.assignDeviceToZone('vent-1', 'B')

    then: 'zone B now includes vent-1'
    (((Map) stateMap.zones).B.assignedVentIds as List).contains('vent-1')

    and: 'zone A no longer includes vent-1 (assigning to B removed it from A)'
    !(((Map) stateMap.zones).A.assignedVentIds as List).contains('vent-1')

    and: 'vent-1 belongs to exactly one zone'
    zonesContaining((Map) stateMap.zones, 'vent-1') == ['B']
  }

  def 'R2.20/R2.21: a device assigned to no zone is inert (excluded from balancing and never commanded)'() {
    setup: 'vent-1 -> zone A, vent-2 -> zone B, vent-3 left unassigned'
    devices = [fakeVent('vent-1'), fakeVent('vent-2'), fakeVent('vent-3')]
    Map zones = [
      'A': [assignedVentIds: ['vent-1'], assignedPuckIds: []],
      'B': [assignedVentIds: ['vent-2'], assignedPuckIds: []]
    ]
    def script = buildScript([:], [zones: zones])

    expect: 'the unassigned vent is reported as belonging to no zone'
    (script.getUnassignedDeviceIds() as List).contains('vent-3')

    and: 'the unassigned vent is in NO zone\'s commandable set, so it is never commanded'
    !(script.getZoneCommandableVentIds('A') as List).contains('vent-3')
    !(script.getZoneCommandableVentIds('B') as List).contains('vent-3')

    and: 'assigned vents remain commandable only by their own zone'
    (script.getZoneCommandableVentIds('A') as List).contains('vent-1')
    (script.getZoneCommandableVentIds('B') as List).contains('vent-2')
  }
}
