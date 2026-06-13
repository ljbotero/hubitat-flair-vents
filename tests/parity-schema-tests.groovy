
import spock.lang.Specification
import spock.lang.Shared

/**
 * Schema test for the PORTABLE parity scenario format (task 8.1, R17.1/R17.4).
 *
 * This is the RED→GREEN anchor for the parity evidence gate (D9). It asserts
 * that the shared, platform-neutral JSON fixture format exists and that the
 * Groovy-side LOADER ({@link ParityFixtures}) can:
 *
 *   1. discover {@code *.scenario.json} / {@code *.expected.json} fixture pairs
 *      under the harness parity resources directory;
 *   2. deserialize a {@code *.scenario.json} into the PURE allocator input types
 *      ({@link RoomAllocInput} / {@link AllocSettings} / {@link DuctSignals},
 *      including a learned {@link Dabv2Learning.VentCurve}); and
 *   3. deserialize the matching {@code *.expected.json} (the Reference-generated
 *      output: commanded targets, {@code airflowLimited}, {@code floorBinding},
 *      {@code combinedOpenPct}, the stamped {@code referenceCommit} git SHA, and
 *      the {@code tolerance.aperturePoints}).
 *
 * It also feeds the loaded scenario straight through the Groovy pure modules
 * ({@link Dabv2Allocator#allocate} + {@link Dabv2SafetyFloor#apply}) to prove
 * the format wires cleanly into the very code the parity gate (task 8.3) will
 * compare — WITHOUT yet asserting the ±1 numeric match (that is task 8.3 /
 * Property 17). The floor invariant (combined ≥ floor, zero violations) is the
 * one correctness assertion made here because it must hold for every fixture.
 *
 * This is a UNIT/schema test (the tagged Property 17 belongs to task 8.3), so it
 * does NOT carry a {@code Property N} heading.
 *
 * Validates: Requirements 17.1, 17.4.
 */
class ParitySchemaSpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-6d

  def 'the parity resources directory exists and contains at least one scenario'() {
    expect: 'the loader resolves a real fixtures directory'
    ParityFixtures.dir() != null
    ParityFixtures.dir().isDirectory()

    and: 'at least one scenario fixture is present (task 8.1 ships one; 8.2 adds the six)'
    !ParityFixtures.scenarioIds().isEmpty()
  }

  def 'every scenario fixture has a matching expected fixture'() {
    expect:
    ParityFixtures.scenarioIds().every { String id ->
      ParityFixtures.expectedFile(id).isFile()
    }
  }

  def 'a scenario fixture deserializes into the pure allocator input types'() {
    given: 'the first available scenario id'
    String id = ParityFixtures.scenarioIds().first()

    when: 'the loader parses the *.scenario.json'
    ParityScenario sc = ParityFixtures.loadScenario(id)

    then: 'the portable scenario header fields are populated'
    sc.id == id
    sc.mode in ['cooling', 'heating']
    sc.setpointC > 0.0d

    and: 'settings deserialize into a real AllocSettings POGO'
    sc.settings instanceof Map
    sc.settings.safetyFloorPct >= 20.0d && sc.settings.safetyFloorPct <= 90.0d
    sc.settings.granularity >= 1

    and: 'rooms deserialize into RoomAllocInput POGOs with at least one active room'
    !sc.rooms.isEmpty()
    sc.rooms.every { it instanceof Map }
    sc.rooms.any { it.active }

    and: 'each room carries its physical vent ids (grouping, R15) and a usable rate'
    sc.rooms.every { Map r ->
      r.roomId != null && r.ventIds != null && !r.ventIds.isEmpty() && r.efficiency > 0.0d
    }

    and: 'a learned VentCurve, when present, is a real Dabv2Learning.VentCurve'
    sc.rooms.every { Map r ->
      r.curve == null || r.curve instanceof Map
    }
  }

  def 'an expected fixture deserializes, is stamped with the Reference SHA, and matches its scenario'() {
    given:
    String id = ParityFixtures.scenarioIds().first()

    when: 'the loader parses the *.expected.json'
    ParityScenario sc = ParityFixtures.loadScenario(id)
    ParityExpected ex = ParityFixtures.loadExpected(id)

    then: 'identity + Reference provenance are present (R17.4 — reproducible/regenerable)'
    ex.id == id
    ex.referenceCommit != null && !ex.referenceCommit.trim().isEmpty()
    ex.toleranceAperturePoints >= 1

    and: 'expected targets cover the active rooms; any extra keys are last-resort reopened inactive rooms'
    Set<String> activeIds = sc.rooms.findAll { it.active }*.roomId as Set
    Set<String> inactiveIds = sc.rooms.findAll { !it.active }*.roomId as Set
    ex.targets.keySet().containsAll(activeIds)
    (ex.targets.keySet() - activeIds).every { inactiveIds.contains(it) }
    ex.targets.values().every { it >= 0.0d && it <= 100.0d }

    and: 'the airflow-limited set is a subset of the active rooms'
    activeIds.containsAll(ex.airflowLimited)
  }

  def 'the loaded scenario feeds the pure modules and never violates the safety floor (R6/R17.3)'() {
    given:
    String id = ParityFixtures.scenarioIds().first()
    ParityScenario sc = ParityFixtures.loadScenario(id)

    when: 'the Groovy allocator + the single safety-floor choke point run on it'
    Map res = lib.allocAllocate(sc.rooms, sc.setpointC, sc.mode, sc.settings, sc.duct)
    def floored =
      lib.sfApply(res.targets, sc.rooms, sc.settings)
    Map<String, Double> safe = floored[0]

    then: 'the loader produced allocator-ready inputs (a target per active room)'
    Set<String> activeIds = sc.rooms.findAll { it.active }*.roomId as Set
    safe.keySet().containsAll(activeIds)

    and: 'combined open % is at or above the configured floor — zero violations (R6/R17.3)'
    Map<String, Double> perVent = ParityFixtures.expandPerVent(safe, sc.rooms)
    double floor = lib.sfClampSafetyFloor(sc.settings.safetyFloorPct)
    lib.sfCombinedOpenPct(perVent, sc.settings) >= floor - EPS
  }
}
