
// R6 no-learning-while-circulating property test (Task 8.1; R6.14).
//
// Implements Correctness Property 28 from design.md (§R6, "Correctness
// Properties") EXACTLY — one property per feature method, tagged with the exact
// property heading:
//
//   Property 28: No learning while circulating  (R6.14)
//     No efficiency sample is recorded while the zone is in circulation mode.
//     Validates: Requirements 6.14.
//
// STRICT TDD (Task 8.1): this spec encodes the REQUIRED behavior that Task 8.2
// ("treat circulation as DABV2_ACTION_IDLE — record no efficiency sample") must
// satisfy. It drives the REAL learning recorder `finalizeRoomStates` under the
// hubitat_ci sandbox for a fan-only (circulation) cycle and asserts that NO
// learned-rate sample (`room-cooling-rate` / `room-heating-rate`) is emitted.
//
//   RED until Task 8.2 (the genuinely-failing assertion):
//     `finalizeRoomStates` has no circulation gate today. A fan-only cycle
//     (`hvacMode == 'fan only'`) still satisfies every recording precondition,
//     and because the mode is not COOLING it is treated as heating, so the
//     recorder emits a `room-heating-rate` sample for the room — learning from a
//     non-conditioning circulation cycle. Task 8.2 must treat circulation as
//     DABV2_ACTION_IDLE (a non-conditioning action) and record NO efficiency
//     sample, turning this GREEN. (Detection is grounded in the existing pure
//     helper: `dabv2DetectCirculation('fan only', _) == true`, R6.1.)
//
// The fake child vent mirrors the proven CycleFinalizeRaceTest harness: an
// attribute-backed ChildDeviceWrapper whose sendEvent calls are captured so the
// presence/absence of a learned-rate sample is directly observable.
//
// The class name contains "Property" so `--tests '*Property*'` selects it; the
// `where:` block drives PropertyGen.ITERATIONS reproducible scenarios, each a
// randomized circulation cycle (apertures, temps, circulation %, run length).
//
// Run `./gradlew test --tests '*CirculationNoLearning*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class CirculationNoLearningPropertySpec extends Specification {

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

  def 'Feature: hubitat-flair-vents-dab-v2, Property 28: no learning while circulating — no efficiency sample is recorded while the zone is in circulation mode'() {
    given: 'a fan-only (circulation) cycle whose run length and room state would otherwise produce a learned-rate sample'
    def g = PropertyGen.forIteration(i)

    int circulationPct = g.nextInt(10, 100)
    // A vent open >= 30% guarantees the recorder would emit a (non-negative)
    // learned rate today regardless of the temperature delta, so the RED state
    // is deterministic across iterations.
    int percentOpen = g.nextInt(30, 100)
    double startTemp = g.nextDouble(20.0d, 28.0d)
    double currentTemp = startTemp + g.nextDouble(-3.0d, 3.0d)
    long runMinutes = (long) g.nextInt(5, 45)

    // Captured learned-rate events + an attribute-backed fake vent (mirrors the
    // CycleFinalizeRaceTest harness).
    List ventEvents = []
    Map ventAttrs = [
      'room-name'                  : 'Room 1',
      'room-active'                : 'true',
      'percent-open'               : percentOpen,
      'room-current-temperature-c' : (currentTemp as BigDecimal),
      'room-starting-temperature-c': (startTemp as BigDecimal),
      'room-cooling-rate'          : 0.2,
      'room-heating-rate'          : 0.2,
    ]
    ChildDeviceWrapper vent = [
      getId       : { -> 'vent-1' },
      currentValue: { Object... a -> ventAttrs[a[0]] },
      sendEvent   : { Object e -> ventAttrs[(e.name)] = e.value; ventEvents << e },
      getLabel    : { -> 'Vent 1' },
      toString    : { -> 'FakeVent(vent-1)' },
    ] as ChildDeviceWrapper
    def ventsById = ['vent-1': vent]

    final log = new CapturingLog()
    Map atomic = [:]
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> atomic
      _ * getLog() >> log
      _ * getChildDevice(_) >> { String id -> ventsById[id] }
      _ * sendEvent(_, _) >> { device, Map ev -> device.sendEvent(ev) }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': [debugLevel: 1, circulationEnabled: true,
                            circulationOpenPct: circulationPct])
    script.atomicState = atomic

    // A circulation cycle: the canonical fan-only operating state (R6.1). All
    // recording preconditions are met (started/finished stamps, ventIdsByRoomId,
    // a run >= MIN_MINUTES_TO_SETPOINT) so the ONLY thing that should suppress a
    // learned-rate sample is the circulation gate Task 8.2 adds.
    long startedCycle = 1_000_000L
    long startedRunning = startedCycle
    long finishedRunning = startedCycle + (runMinutes * 60L * 1000L)
    Map data = [
      ventIdsByRoomId: ['room1': ['vent-1']],
      startedCycle   : startedCycle,
      startedRunning : startedRunning,
      finishedRunning: finishedRunning,
      hvacMode       : 'fan only',
      circulation    : true,
    ]

    when: 'the learning recorder finalizes the circulation cycle'
    script.finalizeRoomStates(data)

    then: 'RED until 8.2: no learned cooling/heating rate sample is recorded while circulating (R6.14)'
    ventEvents.findAll { it.name == 'room-cooling-rate' || it.name == 'room-heating-rate' }.isEmpty()

    where:
    i << (0..<PropertyGen.ITERATIONS)
  }
}
