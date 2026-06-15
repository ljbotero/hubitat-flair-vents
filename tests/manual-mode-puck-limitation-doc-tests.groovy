
// Manual-mode Puck-control limitation documentation tests (R7.7)
//
// Strict-TDD failing specs for spec flair-vents-v0236 task 19.1.
//
// Verified by assumption A8 (Flair support KB + live API check): while the
// structure is held in DAB-managed Manual mode (`patchStructureData([mode:
// 'manual'])`), Flair disables ALL automation and there is NO API toggle to
// retain or re-enable local Puck dial setpoint control. R7.39's conditional
// ("WHERE the API exposes a way…") is therefore resolved FALSE by A8.
//
// Consequently the integration MUST:
//   * clearly surface the limitation — that DAB-managed Manual mode disables
//     the local Puck dial — in the App UI and/or documentation (R7.38, R7.40);
//   * NOT expose a non-functional enable option that implies the capability is
//     available (R7.41).
//
// These specs inspect the running Groovy source and the README (the same files
// shipped on-device / with the package), mirroring the source-inspection
// convention used by version-surfacing-tests.groovy. They are RED until task
// 19.2 documents the limitation.
//
// Requirements: 7.38, 7.41 (19.1) — 7.38, 7.39, 7.40, 7.41 (19.2)
// Design: §R7.7

import spock.lang.Specification

class ManualModePuckLimitationDocTest extends Specification {

  private static final File APP_FILE = new File('src/hubitat-flair-vents-app.groovy')
  private static final File README_FILE = new File('README.md')

  // The documented limitation must mention Manual mode AND the local Puck dial
  // setpoint control being disabled/unavailable. Accept either "dial" or
  // "set point"/"setpoint" phrasing so task 19.2 is free to choose wording.
  private static boolean documentsManualModeLimitation(String text) {
    String lower = text.toLowerCase()
    boolean mentionsManualMode = lower.contains('manual mode')
    boolean mentionsPuck = lower.contains('puck')
    boolean mentionsLocalControl =
      lower.contains('dial') || lower.contains('set point') || lower.contains('setpoint')
    return mentionsManualMode && mentionsPuck && mentionsLocalControl
  }

  // A non-functional "enable local Puck control" toggle would be an `input`
  // whose name references retaining/enabling local Puck setpoint/dial control.
  // R7.41 forbids registering such a control because the capability is not
  // available via the Flair API.
  private static boolean registersPuckControlToggle(String src) {
    // Match input declarations whose setting name pairs a Puck reference with a
    // control/enable/retain/dial/setpoint concept.
    def matcher = src =~ /input\s+name:\s*['"]([^'"]+)['"]/
    while (matcher.find()) {
      String name = matcher.group(1).toLowerCase()
      if (name.contains('puck') &&
          (name.contains('control') || name.contains('enable') ||
           name.contains('retain') || name.contains('dial') ||
           name.contains('setpoint') || name.contains('manual'))) {
        return true
      }
    }
    return false
  }

  // R7.40 — the App SHALL clearly surface the limitation in the App UI or
  // documentation. Assert the limitation is documented in the app UI source.
  def "App UI source documents the DAB-managed Manual-mode Puck-dial limitation"() {
    given:
    def appText = APP_FILE.text

    expect:
    documentsManualModeLimitation(appText)
  }

  // R7.40 — also surface the limitation in the user-facing README so it is
  // discoverable outside the app page.
  def "README documents the DAB-managed Manual-mode Puck-dial limitation"() {
    given:
    def readmeText = README_FILE.text

    expect:
    documentsManualModeLimitation(readmeText)
  }

  // R7.41 — the App SHALL NOT expose a non-functional enable option that
  // implies retaining local Puck control is available.
  def "App registers no non-functional local-Puck-control enable setting"() {
    given:
    def appText = APP_FILE.text

    expect:
    !registersPuckControlToggle(appText)
  }
}
