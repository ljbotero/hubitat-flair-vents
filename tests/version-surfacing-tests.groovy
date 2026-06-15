
// Version Surfacing Tests (R7.1 — Expose the app/driver VERSION string)
//
// Strict-TDD failing specs for spec flair-vents-v0236 task 1.1.
// Assert that:
//   * the parent app exposes a version string in the running Groovy source
//     (an `@Field static final String APP_VERSION`) and surfaces it on the
//     config (main) page header;
//   * the Vent_Driver and Puck_Driver each expose a `VERSION` constant that is
//     readable on the device detail page (via a `version`/`driverVersion`
//     attribute or state).
//
// These specs inspect the running Groovy source (the same source #include-d /
// loaded on-device), mirroring the source-inspection convention used by
// device-driver-tests.groovy. They are RED until task 1.2 surfaces the
// version constants and UI/driver wiring.
//
// Requirements: 7.1, 7.2, 7.3, 7.4, 7.5
// Design: §R7.1

import spock.lang.Specification

class VersionSurfacingTest extends Specification {

  private static final File APP_FILE = new File('src/hubitat-flair-vents-app.groovy')
  private static final File VENT_DRIVER_FILE = new File('src/hubitat-flair-vents-driver.groovy')
  private static final File PUCK_DRIVER_FILE = new File('src/hubitat-flair-vents-pucks-driver.groovy')

  // A `version`/`driverVersion` exposure readable on the device detail page can
  // be satisfied either by a metadata attribute or by writing the value to
  // state. Accept either form so task 1.2 is free to choose.
  private static boolean exposesDriverVersion(String src) {
    boolean hasVersionAttribute =
      (src =~ /attribute\s+['"](version|driverVersion)['"]/) as boolean
    boolean hasVersionState =
      (src =~ /state\.(version|driverVersion)\s*=/) as boolean ||
      (src =~ /sendEvent\s*\([^)]*name\s*:\s*['"](version|driverVersion)['"]/) as boolean
    return hasVersionAttribute || hasVersionState
  }

  // R7.1.1 — the App SHALL expose its version string within the running Groovy
  // source (not only in packageManifest.json): an `@Field static final String
  // APP_VERSION`.
  def "App exposes an APP_VERSION constant in the running Groovy source"() {
    given:
    def appText = APP_FILE.text

    expect:
    appText =~ /@Field\s+static\s+final\s+String\s+APP_VERSION\s*=/
  }

  // R7.1.2 / R7.1.5 — the App SHALL display the current version within the App
  // UI / on the App configuration page so it can be reported in support
  // requests. The mainPage header must reference the APP_VERSION constant
  // (i.e. it is used, not merely declared).
  def "App surfaces APP_VERSION on the config (main) page header"() {
    given:
    def appText = APP_FILE.text

    when: 'the constant declaration is removed, a UI reference should remain'
    def withoutDeclaration = appText.replaceFirst(
      /@Field\s+static\s+final\s+String\s+APP_VERSION\s*=\s*[^\n]*/, '')

    then: 'APP_VERSION is referenced somewhere beyond its declaration (UI usage)'
    withoutDeclaration.contains('APP_VERSION')

    and: 'the mainPage renders the version string'
    def mainPageStart = appText.indexOf('def mainPage()')
    mainPageStart >= 0
    def mainPageBody = appText.substring(mainPageStart,
      Math.min(appText.length(), mainPageStart + 4000))
    mainPageBody.contains('APP_VERSION')
  }

  // R7.1.3 — the Vent_Driver SHALL expose its version string within the driver,
  // visible on the device detail page.
  def "Vent driver exposes a VERSION constant"() {
    given:
    def driverText = VENT_DRIVER_FILE.text

    expect:
    driverText =~ /static\s+final\s+String\s+VERSION\s*=/
  }

  def "Vent driver surfaces the version on the device detail page"() {
    given:
    def driverText = VENT_DRIVER_FILE.text

    expect:
    exposesDriverVersion(driverText)
  }

  // R7.1.4 — the Puck_Driver SHALL expose its version string within the driver,
  // visible on the device detail page.
  def "Puck driver exposes a VERSION constant"() {
    given:
    def driverText = PUCK_DRIVER_FILE.text

    expect:
    driverText =~ /static\s+final\s+String\s+VERSION\s*=/
  }

  def "Puck driver surfaces the version on the device detail page"() {
    given:
    def driverText = PUCK_DRIVER_FILE.text

    expect:
    exposesDriverVersion(driverText)
  }
}
