
// R6 circulation safety-floor property test (Task 8.1; R6.10/R6.11).
//
// Implements Correctness Property 27 from design.md (§R6, "Correctness
// Properties") EXACTLY — one property per feature method, tagged with the exact
// property heading:
//
//   Property 27: Circulation safety floor  (R6.10/R6.11)
//     For any circulation percentage and any active/inactive room combination,
//     commanded combined airflow is never below the floor.
//     Validates: Requirements 6.10, 6.11, 6.14.
//
// STRICT TDD (Task 8.1): this spec encodes the REQUIRED end-to-end behavior that
// Task 8.2 ("Wire dabv2DetectCirculation/dabv2CirculationTargets; dispatch
// through sfApply ...") must satisfy. It drives the REAL per-zone evaluate seam
// `evaluateDabV2Zone` under the hubitat_ci sandbox with the thermostat in the
// fan-only operating state (`dabv2DetectCirculation('fan only', _) == true`,
// R6.1) and circulation ENABLED.
//
//   RED until Task 8.2 (the genuinely-failing assertion):
//     Circulation is NOT yet wired. Today a fan-only operating state resolves to
//     DABV2_ACTION_IDLE and `evaluateDabV2Zone` returns the rested idle result
//     (`combinedOpenPct == 0`, no targets) — it does NOT open vents to the
//     circulation % nor route them through the Safety_Floor choke point. With
//     every room rested (currentOpen 0) and no prior cycle, the idle path emits
//     combined airflow 0, which is below any clamped floor (>= 20%), so the
//     floor assertion FAILS. Task 8.2 must detect circulation, build the
//     circulation targets (`dabv2CirculationTargets`), and route them through
//     `sfApply` so the floor wins (R6.10/R6.11) — turning this GREEN.
//
// The class name contains "Property" so `--tests '*Property*'` selects it; the
// `where:` block drives PropertyGen.ITERATIONS reproducible scenarios, each one
// a randomized circulation % over a randomized active/inactive room mix.
//
// Run `./gradlew test --tests '*CirculationSafetyFloor*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class CirculationSafetyFloorPropertySpec extends Specification {

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

  private Object buildScript(Map userSettings) {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': userSettings)
    script.atomicState = [:]
    return script
  }

  def 'Feature: hubitat-flair-vents-dab-v2, Property 27: circulation safety floor — for any circulation percentage and any active/inactive room combination, commanded combined airflow is never below the floor'() {
    given: 'a fan-only (circulation) zone with circulation enabled and a randomized active/inactive room mix'
    def g = PropertyGen.forIteration(i)

    // The two knobs the property quantifies over: the circulation percentage and
    // the active/inactive room combination.
    int circulationPct = g.nextInt(10, 100)
    boolean closeInactive = g.nextBoolean()
    double floor = (double) g.pick([20.0d, 35.0d, 40.0d, 55.0d, 70.0d, 90.0d])
    double setpointC = g.nextDouble(20.0d, 24.0d)

    int nActive = g.nextInt(1, 4)
    int nInactive = g.nextInt(0, 3)

    List rooms = []
    (0..<nActive).each { int k ->
      String roomId = "a${k}".toString()
      // Rested apertures (currentOpen 0) so today's idle path reports combined
      // airflow 0 — making the missing-wiring failure deterministic. Temps sit
      // near the setpoint (satisfied): circulation must open vents regardless.
      rooms << [roomId: roomId, tempC: setpointC + g.nextDouble(-0.2d, 0.2d),
                active: true, currentOpen: 0.0d, coolingRate: 0.1, heatingRate: 0.1,
                ventIds: ["${roomId}#v0".toString()]]
    }
    (0..<nInactive).each { int k ->
      String roomId = "i${k}".toString()
      rooms << [roomId: roomId, tempC: setpointC + g.nextDouble(-0.2d, 0.2d),
                active: false, currentOpen: 0.0d, coolingRate: 0.1, heatingRate: 0.1,
                ventIds: ["${roomId}#v0".toString()]]
    }

    def script = buildScript([
        debugLevel                  : 1,
        safetyFloorPct              : floor,
        circulationEnabled          : true,
        circulationOpenPct          : circulationPct,
        thermostat1CloseInactiveRooms: closeInactive])
    double clampedFloor = script.sfClampSafetyFloor(floor)

    when: 'the zone is evaluated in the fan-only (circulation) operating state with no prior cycle'
    def result = script.evaluateDabV2Zone(rooms, setpointC, 'fan only', null, [hour: 12], null)

    then: 'RED until 8.2: commanded combined airflow is at or above the (clamped) safety floor (R6.10/R6.11)'
    (result.combinedOpenPct as double) >= clampedFloor - EPS

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }
}
