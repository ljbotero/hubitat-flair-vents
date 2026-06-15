
// HPM beta packaging / version-derivation tests
// (R7.2 — Clean up HPM beta-channel packaging/versioning; never "vnullbeta").
//
// Strict-TDD FAILING specs for spec flair-vents-v0236 task 18.1. They pin the
// behaviour that task 18.2 implements:
//
//   * a pure `dabv2DeriveDisplayVersion(manifest, channel, fallback)` helper in
//     FlairVentsDabv2 that derives the displayed version from the manifest:
//       - the stable channel derives from the `version` field            (R7.6)
//       - the beta / early-release channel derives from `betaVersion`     (R7.7)
//       - a null / missing / blank field falls back to a documented label
//         and NEVER renders "vnullbeta" or any null-derived version       (R7.8)
//   * the App surfaces a manifest-driven, non-null display version via
//     `appDisplayVersion()` that agrees with `APP_VERSION` and is never a
//     null-derived "vnullbeta" string                              (R7.8/R7.10)
//   * the Package Manager metadata (`packageManifest.json` / `repository.json`)
//     keeps the stable/beta version fields consistent with the `bundles/`
//     artifact filenames (`flair-vents.v0.235.zip`, `flair-vents.v0.236.zip`)
//                                                                  (R7.11/R7.12)
//
// Genuinely-RED (drive task 18.2, absent today):
//   - the pure `dabv2DeriveDisplayVersion` helper
//   - the app `appDisplayVersion()` method + manifest-mirroring constants
//     (`STABLE_VERSION` / `BETA_VERSION` / `RELEASE_CHANNEL` /
//      `VERSION_FALLBACK_LABEL`)
//
// Requirements: 7.6, 7.7, 7.8, 7.9, 7.10, 7.11, 7.12
// Design: §R7.2
//
// Run `./gradlew test --tests '*HpmVersionDerivation*'`.

import groovy.json.JsonSlurper
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Shared
import spock.lang.Specification

class HpmVersionDerivationTest extends Specification {

  // The methods-only DAB v2 library, loaded as a script so its pure helpers and
  // @Field constants are callable off-device.
  @Shared
  Script lib = new GroovyShell().parse(new File('libraries/flair-vents-dabv2.groovy'))

  private static final String APP_FILE = Dabv2AppHarness.combinedAppText()
  private static final File MANIFEST_FILE = new File('packageManifest.json')
  private static final File REPOSITORY_FILE = new File('repository.json')
  private static final File BUNDLES_DIR = new File('bundles')

  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice,
            Flags.AllowWritingToSettings,
            Flags.AllowReadingNonInputSettings
          ]

  private Object buildScript(Map userSettings = [:], Map stateMap = [:]) {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': userSettings)
    script.state = stateMap
    return script
  }

  private static Map manifest() {
    return (Map) new JsonSlurper().parseText(MANIFEST_FILE.text)
  }

  // ===========================================================================
  // R7.6 / R7.7 — channel-appropriate derivation from the manifest fields
  // ===========================================================================

  def "stable channel derives the displayed version from the `version` field (R7.6)"() {
    given:
    Map m = [version: '0.235', betaVersion: '0.236']

    expect:
    lib.dabv2DeriveDisplayVersion(m, 'stable', '0.0.0-dev') == '0.235'
  }

  def "beta channel derives the displayed version from the `betaVersion` field (R7.7)"() {
    given:
    Map m = [version: '0.235', betaVersion: '0.236']

    expect:
    lib.dabv2DeriveDisplayVersion(m, 'beta', '0.0.0-dev') == '0.236'
  }

  // ===========================================================================
  // R7.8 — null / missing / blank fields fall back; never "vnullbeta"
  // ===========================================================================

  def "a null beta field falls back to the documented label and never renders vnullbeta (R7.8)"() {
    given:
    Map m = [version: '0.235', betaVersion: null]

    when:
    String derived = lib.dabv2DeriveDisplayVersion(m, 'beta', '0.0.0-dev')

    then:
    derived == '0.0.0-dev'
    !derived.toLowerCase().contains('null')
    !derived.toLowerCase().contains('vnullbeta')
  }

  def "a missing stable field falls back to the documented label (R7.8)"() {
    given: 'no version field present at all'
    Map m = [betaVersion: '0.236']

    expect:
    lib.dabv2DeriveDisplayVersion(m, 'stable', '0.0.0-dev') == '0.0.0-dev'
  }

  def "a blank / literal-null-string field falls back rather than emitting a null-derived version (R7.8)"() {
    expect:
    lib.dabv2DeriveDisplayVersion([version: '', betaVersion: '0.236'], 'stable', '0.0.0-dev') == '0.0.0-dev'
    lib.dabv2DeriveDisplayVersion([version: '   ', betaVersion: '0.236'], 'stable', '0.0.0-dev') == '0.0.0-dev'
    lib.dabv2DeriveDisplayVersion([version: 'null', betaVersion: '0.236'], 'stable', '0.0.0-dev') == '0.0.0-dev'
  }

  def "a null manifest falls back to the documented label without throwing (R7.8)"() {
    expect:
    lib.dabv2DeriveDisplayVersion(null, 'beta', '0.0.0-dev') == '0.0.0-dev'
  }

  // ===========================================================================
  // R7.8 / R7.10 — the App surfaces a manifest-driven, non-null display version
  // ===========================================================================

  def "the App exposes appDisplayVersion() that is manifest-driven and never vnullbeta (R7.8/R7.10)"() {
    given:
    def script = buildScript()

    when:
    String shown = script.appDisplayVersion()

    then: 'a real, non-blank version string is surfaced'
    shown != null
    !shown.trim().isEmpty()

    and: 'it is never a null-derived placeholder'
    !shown.toLowerCase().contains('null')
    !shown.toLowerCase().contains('vnullbeta')

    and: 'it agrees with the APP_VERSION constant surfaced on the config page'
    shown == script.APP_VERSION
  }

  // ===========================================================================
  // R7.11 / R7.12 — metadata <-> bundles/ artifact-filename consistency
  // ===========================================================================

  def "packageManifest stable/beta versions match the bundles/ artifact filenames (R7.11/R7.12)"() {
    given:
    Map m = manifest()
    String stable = m.version
    String beta = m.betaVersion

    expect: 'a bundle artifact exists for each channel version, named by that version'
    new File(BUNDLES_DIR, "flair-vents.v${stable}.zip").exists()
    new File(BUNDLES_DIR, "flair-vents.v${beta}.zip").exists()

    and: 'the per-bundle location/betaLocation reference those same filenames'
    Map bundle = (Map) ((List) m.bundles)[0]
    bundle.location.endsWith("flair-vents.v${stable}.zip")
    bundle.betaLocation.endsWith("flair-vents.v${beta}.zip")
  }

  def "repository.json points at the packageManifest so its version fields govern both channels (R7.11/R7.12)"() {
    given:
    Map repo = (Map) new JsonSlurper().parseText(REPOSITORY_FILE.text)
    Map pkg = (Map) ((List) repo.packages)[0]

    expect:
    pkg.location.endsWith('packageManifest.json')
  }

  def "the App version-mirror constants stay consistent with the manifest channel fields (R7.11/R7.12)"() {
    given:
    def script = buildScript()
    Map m = manifest()

    expect: 'the source-of-record manifest fields are mirrored by the app constants'
    script.STABLE_VERSION == m.version
    script.BETA_VERSION == m.betaVersion
  }
}
