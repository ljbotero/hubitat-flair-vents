
import spock.lang.Specification
import spock.lang.Shared
import spock.lang.Unroll

/**
 * Unit tests for the PURE Safety_Floor combined-open math (task 5.1):
 * {@code Dabv2SafetyFloor.combinedOpenPct} and the configured-floor
 * validation {@code Dabv2SafetyFloor.clampSafetyFloor}.
 *
 * Mirrors the Reference `hvac_vent_optimizer/balance.py`
 * (combined_open_pct / _clamp_safety_floor) EXACTLY:
 *
 *   combined = ( Sum targets_v
 *                + conventionalVents * conventionalOpenPct
 *                + inactiveOpenPctSum )
 *              / ( nSmart + conventionalVents + nInactiveOpen )
 *
 *   - each key in {@code targets} counts as exactly ONE airflow device, so
 *     multi-vent rooms are expanded to one entry per physical vent and every
 *     vent is counted individually (R15.4);
 *   - conventional (always-open) vents contribute
 *     {@code conventionalVents * conventionalOpenPct} over
 *     {@code conventionalVents} devices, defaulting to 100% open (R6.1, R6.6);
 *   - inactive vents are counted ONLY while actually held open
 *     ({@code inactiveOpenPctSum > 0}); closed dampers add neither numerator
 *     nor device count (R6.7);
 *   - the numerator uses COMMANDED aperture ONLY — a 0% command contributes 0,
 *     never its leak fraction, so leakage can never relax the floor (R6.9);
 *   - no devices at all returns 0.0 (degenerate guard, never divide-by-zero);
 *   - the configured floor is validated to the safe band [20, 90] and replaced
 *     with the documented default of 40 on out-of-range / NaN / Infinity /
 *     null / non-numeric input (R6.8).
 *
 * PURE-core contract (R18.2 / R18.7): NO Hubitat APIs, time, randomness, state.
 *
 * Validates: Requirements 6.1, 6.6, 6.7, 6.8, 6.9, 15.4.
 */
class SafetyFloorCombinedUnitSpec extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its top-level
  // methods and @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final double EPS = 1e-6d

  private Map settings(Map overrides = [:]) {
    return lib.dabv2NewAllocSettings(overrides)
  }

  // ---- combinedOpenPct: smart vents counted individually (R15.4) -----------

  def 'plain average over smart vents when no conventional or inactive'() {
    expect: '(100 + 0 + 50) / 3 smart devices'
    Math.abs(lib.sfCombinedOpenPct(
      ['a': 100.0d, 'b': 0.0d, 'c': 50.0d], settings()) - (150.0d / 3.0d)) <= EPS
  }

  def 'each physical vent counts as one device (R15.4)'() {
    expect: 'a two-vent room expands to two keys at the shared commanded %'
    Math.abs(lib.sfCombinedOpenPct(
      ['room1#v1': 30.0d, 'room1#v2': 30.0d], settings()) - 30.0d) <= EPS
  }

  // ---- conventional vents (R6.1 default 100, R6.6 configured) --------------

  def 'conventional vents counted at configured open percent (R6.6)'() {
    given: '4 conventional vents held at 50% open'
    Map s = settings(conventionalVents: 4, conventionalOpenPct: 50.0d)

    expect: '(60 + 4*50) / (1 smart + 4 conventional) = 260 / 5 = 52'
    Math.abs(lib.sfCombinedOpenPct(['a': 60.0d], s) - 52.0d) <= EPS
  }

  def 'conventional vents default to 100 percent open (R6.1)'() {
    given: 'one conventional vent, default conventionalOpenPct'
    Map s = settings(conventionalVents: 1)

    expect: 'default open % is 100, so (0 + 100) / 2 = 50'
    s.conventionalOpenPct == 100.0d
    Math.abs(lib.sfCombinedOpenPct(['a': 0.0d], s) - 50.0d) <= EPS
  }

  def 'no smart vents but conventional only'() {
    given:
    Map s = settings(conventionalVents: 4, conventionalOpenPct: 50.0d)

    expect: '200 / 4 = 50'
    Math.abs(lib.sfCombinedOpenPct([:], s) - 50.0d) <= EPS
  }

  // ---- currently-open inactive vents (R6.7) --------------------------------

  def 'currently-open inactive vents counted in combined flow (R6.7)'() {
    given: '2 inactive vents held open summing to 100%'
    Map s = settings(inactiveCount: 2, inactiveOpenPctSum: 100.0d)

    expect: '(40 + 100) / (1 smart + 2 inactive) = 140 / 3'
    Math.abs(lib.sfCombinedOpenPct(['a': 40.0d], s) - (140.0d / 3.0d)) <= EPS
  }

  def 'closed inactive vents (sum 0) add neither numerator nor device count'() {
    given: 'inactive vents configured but none held open'
    Map s = settings(inactiveCount: 2, inactiveOpenPctSum: 0.0d)

    expect: 'only the single smart vent counts: 40 / 1 = 40'
    Math.abs(lib.sfCombinedOpenPct(['a': 40.0d], s) - 40.0d) <= EPS
  }

  // ---- commanded aperture only — leak never relaxes the floor (R6.9) -------

  def 'a 0 percent command contributes 0, not its leak fraction (R6.9)'() {
    expect: 'all-closed commands average to 0 even though vents physically leak'
    lib.sfCombinedOpenPct(['a': 0.0d, 'b': 0.0d], settings()) == 0.0d
  }

  // ---- degenerate guard ----------------------------------------------------

  def 'no devices at all returns 0.0 (never divide-by-zero)'() {
    expect:
    lib.sfCombinedOpenPct([:], settings()) == 0.0d
  }

  // ---- full combined example (smart + conventional + inactive) -------------

  def 'combined over smart, conventional and open-inactive devices'() {
    given:
    Map s = settings(
      conventionalVents: 4, conventionalOpenPct: 50.0d,
      inactiveCount: 1, inactiveOpenPctSum: 80.0d)

    expect: '(100 + 0 + 50 + 4*50 + 80) / (3 + 4 + 1) = 430 / 8 = 53.75'
    Math.abs(lib.sfCombinedOpenPct(
      ['a': 100.0d, 'b': 0.0d, 'c': 50.0d], s) - 53.75d) <= EPS
  }

  // ---- clampSafetyFloor: safe band [20, 90], default 40 (R6.8) -------------

  @Unroll
  def 'clampSafetyFloor keeps in-range value #value'() {
    expect:
    Math.abs(lib.sfClampSafetyFloor(value) - value) <= EPS

    where:
    value << [20.0d, 21.0d, 40.0d, 65.0d, 89.0d, 90.0d]
  }

  @Unroll
  def 'clampSafetyFloor rejects out-of-band value #value -> 40 default'() {
    expect:
    lib.sfClampSafetyFloor(value) == 40.0d

    where:
    value << [0.0d, 10.0d, 19.999d, 90.0001d, 95.0d, 100.0d, -5.0d]
  }

  @Unroll
  def 'clampSafetyFloor rejects invalid input #label -> 40 default'() {
    expect:
    lib.sfClampSafetyFloor(value) == 40.0d

    where:
    label          | value
    'null'         | null
    'NaN'          | Double.NaN
    '+Infinity'    | Double.POSITIVE_INFINITY
    '-Infinity'    | Double.NEGATIVE_INFINITY
    'non-numeric'  | 'forty'
  }

  def 'clampSafetyFloor boundary values 20 and 90 are inclusive'() {
    expect:
    lib.sfClampSafetyFloor(20.0d) == 20.0d
    lib.sfClampSafetyFloor(90.0d) == 90.0d
  }
}
