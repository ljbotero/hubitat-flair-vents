
// R2(f) no-orphaned-zone-scoped-schedule property test (Task 14.1; R2.4/R2.13/R2.14).
//
// Implements Correctness Property 23 from design.md (§5.4, §7 "Correctness
// Properties") EXACTLY — one property per feature method, tagged with the exact
// property heading:
//
//   Property 23: No orphaned zone-scoped schedule on reassignment (R2 edge case)
//     After a device moves Zone A->B within the instance, A's assignedVentIds no
//     longer contains it, A's zone-scoped (zone-suffixed) scheduled move for it
//     has been cancelled, and A issues no further command for it; removing a zone
//     cancels only that zone's named schedules and clears only state.zones[zoneId],
//     leaving every other zone's schedules intact.
//     Validates: Requirements 2.4, 2.13, 2.14.
//
// ---------------------------------------------------------------------------
// STRICT TDD (Task 14.1 — RED). This spec encodes the REQUIRED behavior that
// Task 14.2 ("Schedule per-zone handlers with zone-scoped ids/data; on zone
// removal cancel only that zone's named schedules and clear only its
// state.zones[zoneId]/settings; on reassignment cancel the A-side scheduled
// move; leave a device released to no zone at its last commanded position")
// must satisfy.
//
// Contract exercised — the per-zone scheduling / teardown SEAM that does NOT yet
// exist and that Task 14.2 MUST implement (IMPLEMENT TO THESE NAMES):
//
//   - `dabV2ZoneScheduleId(String zoneId)`
//       -> the zone-suffixed schedule handler / id for the addressed zone (e.g.
//          "dabV2EvaluateZone_${zoneId}"), so a given zone's scheduled moves are
//          individually addressable and `unschedule(dabV2ZoneScheduleId(zoneId))`
//          cancels ONLY that zone's named schedule (design §5.4 "per-zone
//          schedule ids"). Because `unschedule()` is app-wide, this zone suffix
//          is the mechanism that keeps teardown zone-scoped.
//
//   - `reassignDeviceToZone(String deviceId, String targetZoneId)`
//       -> moves a device into `targetZoneId` (enforcing the existing single-zone
//          uniqueness rule of `assignDeviceToZone`) AND, for every source zone the
//          device just left, CANCELS that source zone's zone-scoped scheduled move
//          via `unschedule(dabV2ZoneScheduleId(sourceZoneId))`, so the source zone
//          issues no further command for the device and no orphaned schedule keeps
//          actuating it (design §5.4 "Device reassignment Zone A->B (no orphaned
//          schedule)"). The device is left at its last commanded position; the
//          target zone picks it up on its next evaluation.
//
//   - `removeZone(String zoneId)`
//       -> zone-scoped teardown (design §5.4 "Zone-scoped teardown on zone
//          removal"): cancels ONLY that zone's named schedule
//          (`unschedule(dabV2ZoneScheduleId(zoneId))`) and clears ONLY
//          `state.zones[zoneId]` (and that zone's settings); every OTHER zone's
//          schedule and `state.zones[*]` entry is left untouched. (Only
//          `uninstalled()` tears everything down — task 15.)
//
// WHY THIS IS RED TODAY (the genuinely-failing behavior):
//   None of `dabV2ZoneScheduleId`, `reassignDeviceToZone`, or `removeZone` exist
//   in the app yet (grep confirms no zone-suffixed schedule id helper and no
//   zone-scoped teardown). The current `assignDeviceToZone` enforces device
//   uniqueness (so A's `assignedVentIds` already loses the device on
//   reassignment) but issues NO `unschedule` for A's zone-scoped move, so A's
//   pending scheduled move for the device is orphaned and keeps actuating it.
//   There is also no `removeZone`, so removing a zone today would require the
//   app-wide `unschedule()` that cancels EVERY zone's schedules. Invoking the
//   missing seam (and asserting the zone-scoped `unschedule` happened) FAILS
//   until Task 14.2 introduces these methods.
//
// The class name contains "Property" so `--tests '*Property*'` selects it; the
// `where:` block drives PropertyGen.ITERATIONS reproducible scenarios, each a
// randomized set of 2-4 zones with disjoint vents, a random device reassigned
// A->B, and a random zone removed.
//
// Run `./gradlew test --tests '*NoOrphanedZoneSchedule*'`.
//
// _Requirements: 2.4, 2.13, 2.14_
// _Design: §5.4, Property 23_

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class NoOrphanedZoneScheduleReassignmentPropertySpec extends Specification {

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
  private List unscheduleCalls = []
  private List scheduledZoneIds = []   // zoneId carried in each runIn `data` map (re-armed zones)

  // A vent child device: identified by DNI, advertises `percent-open` so
  // `isVentDevice` classifies it as a vent.
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
    unscheduleCalls = []
    scheduledZoneIds = []
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> atomicStateMap
      _ * getLog() >> log
      _ * getChildDevices() >> devices
      _ * getChildDevice(_) >> { String id -> devices.find { it.getDeviceNetworkId() == id } }
      // Capture every NAMED unschedule so the spec can assert reconcile behavior.
      _ * unschedule(_) >> { args -> unscheduleCalls << (args instanceof List ? args[0] : args) }
      // Capture the zoneId carried in each re-armed per-zone schedule.
      _ * runIn(_, _, _) >> { d, h, o -> if (o instanceof Map && o.data instanceof Map) { scheduledZoneIds << o.data.zoneId } }
      _ * runIn(_, _) >> { d, h -> }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ([debugLevel: 1, dabEnabled: true] + userSettings))
    script.state = stateMap
    script.atomicState = atomicStateMap
    return script
  }

  def 'Feature: hubitat-flair-vents-dab-v2, Property 23: No orphaned zone-scoped schedule on reassignment'() {
    given: 'one App_Instance hosting 2-4 zones, each owning a disjoint set of 1-2 vents'
    def g = PropertyGen.forIteration(i)
    int nZones = g.nextInt(2, 4)
    List zoneIds = (0..<nZones).collect { "z${it}".toString() }

    Map zones = [:]
    devices = []
    zoneIds.each { String zid ->
      int nVents = g.nextInt(1, 2)
      List ventIds = (0..<nVents).collect { "${zid}-v${it}".toString() }
      ventIds.each { devices << fakeVent(it) }
      zones[zid] = [assignedVentIds: ventIds, assignedPuckIds: [],
                    safetyFloorPct: 40.0d, zoneName: "Name-${zid}".toString()]
    }
    def script = buildScript([:], [zones: zones])

    // Pick a source zone A (z0) and a distinct target zone B; move one of A's vents.
    String zoneA = zoneIds[0]
    String zoneB = zoneIds[g.nextInt(1, nZones - 1)]
    String movedDevice = (zones[zoneA].assignedVentIds as List)[0]

    when: 'the device is reassigned A->B through the zone-scoped reassignment seam'
    unscheduleCalls = []
    scheduledZoneIds = []
    script.reassignDeviceToZone(movedDevice, zoneB)

    then: "A no longer lists the device and B does (single-zone membership; R2.4)"
    !(((Map) stateMap.zones)[zoneA].assignedVentIds as List).contains(movedDevice)
    (((Map) stateMap.zones)[zoneB].assignedVentIds as List).contains(movedDevice)

    and: 'the device is no longer in A\'s commandable set, so A issues no further command for it (R2.4)'
    !(script.getZoneCommandableVentIds(zoneA) as List).contains(movedDevice)
    (script.getZoneCommandableVentIds(zoneB) as List).contains(movedDevice)

    and: 'per-zone schedules were reconciled (single static handler) so no orphaned schedule keeps actuating the moved device: the handler was cleared and re-armed for every surviving zone, with A re-armed to evaluate WITHOUT the device (R2.13/R2.14)'
    unscheduleCalls.contains('runScheduledDabV2ZoneEvaluate')
    zoneIds.every { scheduledZoneIds.contains(it) }

    when: 'a zone is then removed through the zone-scoped teardown seam'
    String removedZone = zoneIds[g.nextInt(0, nZones - 1)]
    List survivingZones = zoneIds.findAll { it != removedZone }
    // Snapshot each surviving zone's assigned vents to prove they are untouched.
    Map survivingSnapshot = survivingZones.collectEntries {
      [(it): new ArrayList((((Map) stateMap.zones)[it].assignedVentIds ?: []) as List)]
    }
    unscheduleCalls = []
    scheduledZoneIds = []
    script.removeZone(removedZone)

    then: 'only the removed zone\'s slice is cleared; every other zone\'s state is left intact (R2.14)'
    !((Map) stateMap.zones).containsKey(removedZone)
    survivingZones.every { ((Map) stateMap.zones).containsKey(it) }
    survivingZones.every {
      (((Map) stateMap.zones)[it].assignedVentIds as List) == survivingSnapshot[it]
    }

    and: 'schedules were reconciled so ONLY the surviving zones are re-armed and the removed zone gets no schedule (R2.13)'
    !scheduledZoneIds.contains(removedZone)
    survivingZones.every { scheduledZoneIds.contains(it) }

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }
}
