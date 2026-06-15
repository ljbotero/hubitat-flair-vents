
// R2(e) per-zone safety-floor isolation property test (Task 13.1; R2.10/R2.11/R8.3).
//
// Implements Correctness Property 18 from design.md (§5.2, §7 "Correctness
// Properties") EXACTLY — one property per feature method, tagged with the exact
// property heading:
//
//   Property 18: Per-zone safety-floor isolation within one instance
//     (R2.10/R2.11/R8.3)
//     For any set of zones in one App_Instance, each with any assignedVentIds
//     and airflow state in state.zones[zoneId], each zone's commanded combined
//     airflow is >= that zone's own floor; a starved zone reaches its own floor
//     on its own vents regardless of any other zone's airflow. Isolation is by
//     zone-keyed data (sfApply runs per zone over only that zone's slice), with
//     no shared airflow accumulator across zones.
//     Validates: Requirements 2.10, 2.11, 8.3.
//
// ---------------------------------------------------------------------------
// STRICT TDD (Task 13.1 — RED). This spec encodes the REQUIRED behavior that
// Task 13.2 ("Run sfApply per zone over only that zone's assignedVentIds and
// safetyFloorPct read from state.zones[zoneId]; ensure no cross-zone airflow
// accumulator exists") must satisfy.
//
// Contract exercised (the seam Task 13.2 must implement — IMPLEMENT TO THIS NAME):
//
//   - `getDabV2ZoneConfig(String zoneId)`
//       -> returns the DAB v2 evaluate config Map for the addressed zone,
//          IDENTICAL to `getDabV2Config()` EXCEPT `safetyFloorPct` is read from
//          `state.zones[zoneId].safetyFloorPct` (clamped on the same
//          SAFETY_FLOOR_MIN/MAX/DEFAULT band as the global getter), so each
//          zone's safety floor is its OWN, not the instance-global
//          `settings.safetyFloorPct`. Reading no other zone's slice (R2.10).
//          The zone-iterating evaluate loop (`evaluateDabV2ZoneById`, task 12.2)
//          must pass THIS per-zone config into `evaluateDabV2Zone(...)` as the
//          `config` argument so the floor enforced for a zone is the zone's own.
//
// WHY THIS IS RED TODAY (the genuinely-failing behavior):
//   `evaluateDabV2ZoneById` currently calls `evaluateDabV2Zone(roomData,
//   setpointC, action, null, ...)` with a NULL config, so `evaluateDabV2Zone`
//   falls back to `getDabV2Config()` -> `getDabV2SafetyFloorPct()` ->
//   the instance-global `settings.safetyFloorPct`. The per-zone
//   `state.zones[zoneId].safetyFloorPct` is NEVER read, and the seam that would
//   read it (`getDabV2ZoneConfig`) does NOT exist yet. With a deliberately LOW
//   instance-global floor and HIGHER, DISTINCT per-zone floors, a zone driven
//   off the global floor settles below its own floor — and the missing seam
//   makes the call site itself fail — so the per-zone-floor assertion FAILS
//   until Task 13.2 introduces `getDabV2ZoneConfig` and threads it through the
//   zone evaluate loop.
//
// The class name contains "Property" so `--tests '*Property*'` selects it; the
// `where:` block drives PropertyGen.ITERATIONS reproducible scenarios, each one
// a randomized set of 2-4 zones with distinct per-zone floors and starved
// (rested, conditioning-needed) rooms.
//
// Run `./gradlew test --tests '*PerZoneSafetyFloorIsolation*'`.
//
// _Requirements: 2.10, 2.11, 8.3_
// _Design: §5.2, Property 18_

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class PerZoneSafetyFloorIsolationPropertySpec extends Specification {

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

  private static final double EPS = 1e-6d

  // A deliberately LOW instance-global floor: if a zone were (incorrectly)
  // floored on the global value instead of its own, its combined airflow would
  // settle below its own (higher) per-zone floor.
  private static final double GLOBAL_FLOOR = 20.0d

  private Object buildScript(Map userSettings, Map state) {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> state
      _ * getAtomicState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ([debugLevel: 1, dabEnabled: true,
                             safetyFloorPct: GLOBAL_FLOOR] + userSettings))
    script.state = state
    script.atomicState = [:]
    return script
  }

  // A starved zone: 1-3 ACTIVE rooms that all NEED conditioning (warm rooms in
  // cooling => signedErrorC > 0) and are fully rested (currentOpen 0). Left to
  // comfort alone the allocator would leave combined airflow well below the
  // floor, so the per-zone Safety_Floor must raise this zone's OWN vents to
  // reach this zone's OWN floor.
  private List starvedRooms(PropertyGen g, String zoneId, double setpointC) {
    int nRooms = g.nextInt(1, 3)
    List rooms = []
    (0..<nRooms).each { int k ->
      String roomId = "${zoneId}-r${k}".toString()
      rooms << [roomId: roomId, tempC: setpointC + g.nextDouble(1.5d, 4.0d),
                active: true, currentOpen: 0.0d, coolingRate: 0.1, heatingRate: 0.1,
                ventIds: ["${roomId}#v0".toString()]]
    }
    return rooms
  }

  def 'Feature: hubitat-flair-vents-dab-v2, Property 18: Per-zone safety-floor isolation within one instance'() {
    given: 'one App_Instance hosting 2-4 zones, each with its OWN distinct, higher-than-global safety floor'
    def g = PropertyGen.forIteration(i)
    double setpointC = g.nextDouble(20.0d, 24.0d)

    int nZones = g.nextInt(2, 4)
    // Distinct in-band per-zone floors, all ABOVE the low instance-global floor
    // (55, 65, 75, 85 — all within the SAFETY_FLOOR_MIN..MAX 20..90 band).
    List zoneIds = (0..<nZones).collect { "z${it}".toString() }
    Map zoneFloors = [:]
    zoneIds.eachWithIndex { String zid, int idx -> zoneFloors[zid] = 55.0d + (10.0d * idx) }

    // Each zone owns its slice in state.zones[zoneId], carrying its own floor and
    // its own (disjoint) rooms/vents.
    Map zones = [:]
    Map roomsByZone = [:]
    zoneIds.each { String zid ->
      List rooms = starvedRooms(g, zid, setpointC)
      roomsByZone[zid] = rooms
      zones[zid] = [safetyFloorPct: zoneFloors[zid],
                    assignedVentIds: rooms.collect { it.ventIds[0] },
                    assignedPuckIds: []]
    }

    def script = buildScript([:], [zones: zones])

    when: 'each zone is evaluated (cooling) through its OWN per-zone config'
    Map combinedByZone = [:]
    zoneIds.each { String zid ->
      def cfg = script.getDabV2ZoneConfig(zid)
      def result = script.evaluateDabV2Zone(roomsByZone[zid], setpointC, 'cooling', cfg, [hour: 12], null)
      combinedByZone[zid] = result.combinedOpenPct as double
    }

    then: "every zone's commanded combined airflow is at or above THAT zone's own (clamped) floor (R2.10/R2.11)"
    zoneIds.every { String zid ->
      double ownFloor = script.sfClampSafetyFloor(zoneFloors[zid])
      (combinedByZone[zid] as double) >= ownFloor - EPS
    }

    and: 'a starved zone reaches its own floor regardless of any other zone — no shared airflow accumulator (R8.3)'
    // Mutate every OTHER zone's stored floor to the band extremes, re-evaluate
    // zone z0 through its own config, and confirm z0's commanded airflow is
    // unchanged: z0 sees ONLY its own slice, so no cross-zone accumulator can
    // shift its result.
    zoneIds.drop(1).each { String other -> ((Map) zones[other]).safetyFloorPct = 90.0d }
    double z0Before = combinedByZone[zoneIds[0]] as double
    def z0Cfg = script.getDabV2ZoneConfig(zoneIds[0])
    def z0Result = script.evaluateDabV2Zone(roomsByZone[zoneIds[0]], setpointC, 'cooling', z0Cfg, [hour: 12], null)
    Math.abs((z0Result.combinedOpenPct as double) - z0Before) <= EPS

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }
}
