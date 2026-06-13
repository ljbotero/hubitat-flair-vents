
import spock.lang.Specification

/**
 * Coverage guard for the parity evidence gate (task 8.2, R17.6).
 *
 * R17.6 mandates that the parity fixture set include — at minimum — the
 * comfort-critical cases the Reference was designed to handle, and that parity
 * SHALL NOT be considered passing unless these scenarios are covered. This spec
 * is that guard: it asserts the required scenario ids are PRESENT as committed
 * {@code *.scenario.json} fixtures (each with a Reference-generated
 * {@code *.expected.json} partner). The build fails here if any required
 * scenario is missing — independent of the ±1 numeric comparison, which is
 * task 8.3 / Property 17.
 *
 * The six required comfort-critical cases (R17.6 a–f):
 * <ul>
 *   <li>(a) {@code overcool-drag-cooling} — an efficient room overcooling past
 *       setpoint while a low-effectiveness room lags (the average-drag failure mode);</li>
 *   <li>(b) {@code airflow-limited-crosscoupling} — ≥1 airflow-limited room
 *       triggering cross-coupling;</li>
 *   <li>(c) {@code all-satisfied-running} — all active rooms satisfied while the
 *       HVAC is still running (the floor holds the minimum);</li>
 *   <li>(d) {@code single-active-room} — exactly one active room;</li>
 *   <li>(e) {@code mid-cycle-mode-change} — a mid-cycle conditioning-mode change
 *       (cooling↔heating), paired with its post-flip {@code -heating} fixture;</li>
 *   <li>(f) {@code multi-vent-group} — a room served by multiple smart vents (grouping, R15).</li>
 * </ul>
 *
 * Heating mirrors are committed for every case with a symmetric counterpart, so
 * the symmetric direction is covered too (the cooling/heating math is mirror
 * symmetric about the setpoint).
 *
 * Validates: Requirements 17.6.
 */
class ParityCoverageSpec extends Specification {

  /** The six required comfort-critical scenario ids (R17.6 a–f). */
  static final List<String> REQUIRED_IDS = [
    'overcool-drag-cooling',
    'airflow-limited-crosscoupling',
    'all-satisfied-running',
    'single-active-room',
    'mid-cycle-mode-change',
    'multi-vent-group',
  ].asImmutable()

  /** Heating mirrors committed where the cooling case is symmetric. */
  static final List<String> HEATING_MIRROR_IDS = [
    'overcool-drag-heating',
    'airflow-limited-crosscoupling-heating',
    'all-satisfied-heating',
    'single-active-room-heating',
    'mid-cycle-mode-change-heating',
    'multi-vent-group-heating',
  ].asImmutable()

  def 'every required comfort-critical scenario id is present (R17.6 — build fails if any missing)'() {
    given: 'the committed scenario fixture ids'
    Set<String> present = ParityFixtures.scenarioIds() as Set

    expect: 'all six required comfort-critical cases are covered'
    REQUIRED_IDS.every { String id ->
      assert present.contains(id), "missing required parity scenario fixture: ${id}.scenario.json"
      true
    }
  }

  def 'every heating mirror scenario id is present (symmetric cases, R17.6)'() {
    given:
    Set<String> present = ParityFixtures.scenarioIds() as Set

    expect:
    HEATING_MIRROR_IDS.every { String id ->
      assert present.contains(id), "missing heating-mirror parity scenario fixture: ${id}.scenario.json"
      true
    }
  }

  def 'every required scenario has a Reference-generated expected partner stamped with a SHA'() {
    expect: 'a *.expected.json exists next to every required (and mirror) scenario, with provenance'
    (REQUIRED_IDS + HEATING_MIRROR_IDS).every { String id ->
      assert ParityFixtures.expectedFile(id).isFile(), "missing expected fixture: ${id}.expected.json"
      ParityExpected ex = ParityFixtures.loadExpected(id)
      assert ex.referenceCommit != null && !ex.referenceCommit.trim().isEmpty() &&
        ex.referenceCommit != 'unknown', "expected fixture ${id} is not stamped with a Reference SHA"
      true
    }
  }

  def 'the required scenario set actually exercises its comfort-critical behavior'() {
    expect: 'each required case demonstrably triggers the behavior it is meant to cover'
    // (b) airflow-limited-crosscoupling: at least one airflow-limited room, crosscoupling on.
    ParityScenario cc = ParityFixtures.loadScenario('airflow-limited-crosscoupling')
    cc.settings.crosscoupling
    !ParityFixtures.loadExpected('airflow-limited-crosscoupling').airflowLimited.isEmpty()

    and: '(c) all-satisfied-running: every active room is satisfied, so the floor must bind'
    ParityScenario allSat = ParityFixtures.loadScenario('all-satisfied-running')
    allSat.rooms.findAll { it.active }.every { it.signedErrorC <= 0.0d }
    ParityFixtures.loadExpected('all-satisfied-running').floorBinding

    and: '(d) single-active-room: exactly one active room'
    ParityFixtures.loadScenario('single-active-room').rooms.count { it.active } == 1

    and: '(f) multi-vent-group: at least one active room is served by >1 smart vent'
    ParityFixtures.loadScenario('multi-vent-group').rooms.any { it.active && it.ventIds.size() > 1 }
  }
}
