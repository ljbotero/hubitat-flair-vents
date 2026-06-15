
// Add-Zone UI Tests (R2 multi-zone — config-page zone creation)
//
// The v0236 per-zone config UI (task 11.2) renders one section per EXISTING zone
// (`getZoneIds()`), but shipped with no control to introduce a NEW zone id. Since
// `getZoneIds()` falls back to the single implicit 'default' zone when
// `state.zones` is empty, a user could only ever see/configure one zone.
//
// These specs pin the missing add-zone seam:
//   - `generateZoneId(String name)`
//       -> a unique, slugged zone id derived from a display name; '-2','-3',...
//          suffixing dedupes against the currently configured zone ids. No
//          wall-clock/RNG, so the same inputs are reproducible.
//   - `addZoneFromUi()`
//       -> reads `settings.newZoneName`, creates `state.zones[zoneId]` (via
//          ensureZone), snapshots the display name, and re-arms per-zone
//          schedules. A blank name is a no-op. When no explicit zones exist yet,
//          the implicit 'default' zone is materialized first so adding zone #2
//          never drops the migrated single-thermostat slice.
//
// Run `./gradlew test --tests '*ZoneAddFromUi*'`.
//
// _Requirements: 2.6, 2.12_
// _Design: §4.1, §4.5, §R2_

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class ZoneAddFromUiSpec extends Specification {

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
  private Map settingsMap = [:]

  private Object fakeVent(String dni) {
    Map self = [:]
    self.dni = dni
    self.attrs = ['percent-open': 50]
    self.getDeviceNetworkId = { -> self.dni }
    self.getId = { -> self.dni }
    self.getLabel = { -> self.dni }
    self.hasAttribute = { Object... a -> self.attrs.containsKey(a[0]) }
    self.currentValue = { Object... a -> self.attrs[a[0]] }
    return self as ChildDeviceWrapper
  }

  private Object buildScript(Map userSettings = [:], Map state = [:], Map atomic = [:]) {
    log = new CapturingLog()
    stateMap = state
    atomicStateMap = atomic
    settingsMap = ([debugLevel: 1, dabEnabled: true] + userSettings)
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> atomicStateMap
      _ * getLog() >> log
      _ * getChildDevices() >> devices
      _ * getChildDevice(_) >> { String id -> devices.find { it.getDeviceNetworkId() == id } }
      _ * removeSetting(_) >> { String key -> settingsMap.remove(key) }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': settingsMap)
    script.state = stateMap
    script.atomicState = atomicStateMap
    // Isolate from scheduling side effects; scheduling itself is covered by the
    // lifecycle-isolation specs.
    script.metaClass.scheduleDabV2ZoneEvaluations = { -> }
    return script
  }

  def 'generateZoneId slugs the display name to a safe, lowercase id'() {
    setup:
    def script = buildScript([:], [zones: ['default': [assignedVentIds: [], assignedPuckIds: []]]])

    expect:
    script.generateZoneId('Upstairs Bedrooms') == 'upstairs-bedrooms'
    script.generateZoneId('  Main / Living!! ') == 'main-living'
    script.generateZoneId('') == 'zone'
  }

  def 'generateZoneId dedupes against existing zone ids with a numeric suffix'() {
    setup: 'an instance that already has an "upstairs" zone'
    def script = buildScript([:], [zones: [
      'default': [assignedVentIds: [], assignedPuckIds: []],
      'upstairs': [assignedVentIds: [], assignedPuckIds: []]
    ]])

    expect: 'a second "Upstairs" gets a -2 suffix; a third gets -3'
    script.generateZoneId('Upstairs') == 'upstairs-2'
  }

  def 'addZoneFromUi creates a new zone that appears in getZoneIds()'() {
    setup: 'a migrated single-thermostat install (one explicit default zone)'
    def script = buildScript([newZoneName: 'Upstairs'],
      [zones: ['default': [assignedVentIds: ['vent-1'], assignedPuckIds: []]]])

    when: 'the user adds a zone from the UI'
    script.addZoneFromUi()

    then: 'the new zone exists in state and is listed by getZoneIds()'
    ((Map) stateMap.zones).containsKey('upstairs')
    (script.getZoneIds() as Set) == (['default', 'upstairs'] as Set)

    and: 'the new zone carries the supplied display name and the empty assigned sets'
    ((Map) stateMap.zones).upstairs.zoneName == 'Upstairs'
    ((Map) stateMap.zones).upstairs.assignedVentIds == []

    and: 'the pre-existing default zone slice is untouched'
    ((Map) stateMap.zones).default.assignedVentIds == ['vent-1']
  }

  def 'addZoneFromUi on a fresh install materializes the implicit default so it is not lost'() {
    setup: 'a fresh install with no explicit zones (state.zones empty)'
    def script = buildScript([newZoneName: 'Garage'], [zones: [:]])

    when: 'the user adds the first explicit zone'
    script.addZoneFromUi()

    then: 'both the preserved implicit default and the new zone are present'
    (script.getZoneIds() as Set) == (['default', 'garage'] as Set)
  }

  def 'addZoneFromUi with a blank name is a no-op'() {
    setup:
    def script = buildScript([newZoneName: '   '],
      [zones: ['default': [assignedVentIds: [], assignedPuckIds: []]]])

    when:
    script.addZoneFromUi()

    then: 'no new zone is created'
    (script.getZoneIds() as Set) == (['default'] as Set)
  }
}
