
// Anti-chatter cooldown tests (Task 2.3, F-03 / DC-2).
//
// Defect class 2 (anti-chatter wrongly applied to safety-opened vents),
// docs/review-findings.md F-03: the legacy code has NO anti-chatter mechanism
// (only a degenerate skip-if-equal in patchVentDevice). When anti-chatter is
// introduced it MUST distinguish two kinds of move:
//
//   * "must open to MEET the floor" -> applied immediately, bypassing the
//     cooldown / position deadband (a needed safety open is never suppressed).
//   * "padding ABOVE the floor" (an ordinary balancing move) -> honors the
//     minimum-adjustment interval (cooldown) and the position deadband.
//
// These tests pin that distinguishing seam (`shouldApplyVentMove`) and the pure
// helper that classifies which vents the floor forced open
// (`computeFloorRequiredVentIds`). The gate is pure: time is passed in (nowMs /
// lastMoveMs), never read from the platform, so it matches the future pure
// `shouldApply` gate (task 6.8) and runs identically off-device.
//
// Requirements: 2.3, 2.4, 10.4, 20.4
//
// Run `./gradlew test --tests '*AntiChatterCooldown*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class AntiChatterCooldownTest extends Specification {

  private static final String APP_FILE = Dabv2AppHarness.combinedAppText()
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice
          ]
  private static final AbstractMap USER_SETTINGS = ['debugLevel': 1]

  private Object buildScript() {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    return sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS)
  }

  // --- The safety-open exemption: a move required to reach the floor is immediate ---

  def "a must-open-to-meet-floor move is applied immediately even within the cooldown window"() {
    setup:
    def script = buildScript()

    expect:
    // Vent must open from 10% to 40% to satisfy the floor. A move issued only
    // 1s after the last move (well inside any cooldown) must STILL be applied,
    // because suppressing a needed safety open is exactly the DC-2 bug.
    script.shouldApplyVentMove(
      currentOpen: 10.0,
      proposedOpen: 40.0,
      mustOpenToMeetFloor: true,
      nowMs: 1000L,
      lastMoveMs: 0L,
      cooldownMs: 180000L,
      minPercent: 5.0) == true
  }

  def "a must-open-to-meet-floor move bypasses the position deadband"() {
    setup:
    def script = buildScript()

    expect:
    // Even a sub-deadband raise (2%) required to reach the floor is applied.
    script.shouldApplyVentMove(
      currentOpen: 38.0,
      proposedOpen: 40.0,
      mustOpenToMeetFloor: true,
      nowMs: 1000L,
      lastMoveMs: 0L,
      cooldownMs: 180000L,
      minPercent: 5.0) == true
  }

  // --- Ordinary "padding above floor" moves honor cooldown + deadband ---

  def "an ordinary move above the floor is suppressed while inside the cooldown window"() {
    setup:
    def script = buildScript()

    expect:
    // Not floor-required; last move was 1s ago, cooldown is 3 min -> suppress.
    script.shouldApplyVentMove(
      currentOpen: 60.0,
      proposedOpen: 80.0,
      mustOpenToMeetFloor: false,
      nowMs: 1000L,
      lastMoveMs: 0L,
      cooldownMs: 180000L,
      minPercent: 5.0) == false
  }

  def "an ordinary move above the floor is applied once the cooldown has elapsed"() {
    setup:
    def script = buildScript()

    expect:
    // Cooldown (3 min) has fully elapsed since the last move -> apply.
    script.shouldApplyVentMove(
      currentOpen: 60.0,
      proposedOpen: 80.0,
      mustOpenToMeetFloor: false,
      nowMs: 200000L,
      lastMoveMs: 0L,
      cooldownMs: 180000L,
      minPercent: 5.0) == true
  }

  def "an ordinary move smaller than the position deadband is suppressed even after cooldown"() {
    setup:
    def script = buildScript()

    expect:
    // 3% change is below the 5% position deadband -> suppress (anti-chatter),
    // even though the cooldown has elapsed.
    script.shouldApplyVentMove(
      currentOpen: 60.0,
      proposedOpen: 63.0,
      mustOpenToMeetFloor: false,
      nowMs: 200000L,
      lastMoveMs: 0L,
      cooldownMs: 180000L,
      minPercent: 5.0) == false
  }

  def "an ordinary move with no prior move recorded is applied"() {
    setup:
    def script = buildScript()

    expect:
    // No lastMoveMs -> cooldown cannot apply; deadband still respected.
    script.shouldApplyVentMove(
      currentOpen: 60.0,
      proposedOpen: 80.0,
      mustOpenToMeetFloor: false,
      nowMs: 1000L,
      lastMoveMs: null,
      cooldownMs: 180000L,
      minPercent: 5.0) == true
  }

  def "a no-op move (proposed equals current) is never applied"() {
    setup:
    def script = buildScript()

    expect:
    script.shouldApplyVentMove(
      currentOpen: 40.0,
      proposedOpen: 40.0,
      mustOpenToMeetFloor: true,
      nowMs: 200000L,
      lastMoveMs: 0L,
      cooldownMs: 180000L,
      minPercent: 5.0) == false
  }

  // --- Classifying which vents the floor forced open (the must-open set) ---

  def "computeFloorRequiredVentIds returns only vents the floor raised above the base plan"() {
    setup:
    def script = buildScript()
    // Base (comfort/balance) plan vs. floor-padded plan: only vent 'a' was
    // raised by the floor; 'b' is unchanged and 'c' was lowered (impossible for
    // the floor, but proves we only flag genuine raises).
    def base = ['a': 0.0, 'b': 50.0, 'c': 70.0]
    def padded = ['a': 20.0, 'b': 50.0, 'c': 65.0]

    when:
    def result = script.computeFloorRequiredVentIds(base, padded)

    then:
    result == (['a'] as Set)
  }

  def "computeFloorRequiredVentIds is empty when the floor changed nothing"() {
    setup:
    def script = buildScript()
    def base = ['a': 30.0, 'b': 50.0]
    def padded = ['a': 30.0, 'b': 50.0]

    expect:
    script.computeFloorRequiredVentIds(base, padded) == ([] as Set)
  }
}
