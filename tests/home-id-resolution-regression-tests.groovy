
// Home-Id Resolution Regression Tests (post-271)
//
// Task 9.1 (STRICT-TDD RED): deterministic Home-Id resolution must route through
// the pure helper `dabv2SelectStructureId` in BOTH structure paths -- the async
// `handleStructureResponse` and the blocking `getStructureData` -- instead of the
// blind `response.data.first()` shortcut.
//
// These specs are EXPECTED TO FAIL until task 9.2 wires `dabv2SelectStructureId`
// into both paths. They assert:
//   (a) Given a multi-structure API response with none configured, the
//       first-structure shortcut is NOT taken (no Home Id is silently adopted) so
//       no zone's devices can be commanded until an explicit selection is made.
//       (R2.17, R2.18)
//   (b) The same holds on the blocking getStructureData path. (R2.17, R2.18)
//   (c) A single instance-global, already-configured `structureId` is RETAINED
//       across a discovery re-run and never blindly re-picked to `first()`.
//       (R2.15, R2.16, R2.19)
// A single-structure account still auto-adopts its one Home Id (pinned contract,
// already green) so the fix in task 9.2 must preserve today's single-home UX.
//
// Built on the existing `getStructureIdTest` / `authentication-fix-tests` setup
// (HubitatAppSandbox over the shipped combined app+library text).
//
// Harness notes (why the hooks are at the executor boundary, not metaClass):
//   - The app calls `app.updateSetting(...)`, which resolves through the
//     @Delegate-generated `getApp()` whose return type is InstalledAppWrapper;
//     returning anything else throws a GroovyCastException that the app's
//     try/catch swallows. So the capture hook BE an InstalledAppWrapper Mock and
//     is installed on the executor's getApp() (a script `metaClass.app` override
//     is bypassed by the delegate).
//   - The blocking `getStructureData` path is intercepted by stubbing the
//     delegate `httpGet(Map, Closure)` on the executor (mirroring how the
//     dispatch tests capture runInMillis at the executor boundary), which is
//     reliable regardless of script-level metaClass timing.
//
// _Requirements: 2.15, 2.16, 2.17, 2.18, 2.19_
// _Design: §5.1, §8.2_

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.InstalledAppWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class HomeIdResolutionRegressionTest extends Specification {

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

  // Per-feature blocking-httpGet wiring, read at call time by the executor stub.
  private Map blockingHttpResponse = null
  private boolean blockingHttpInvoked = false

  // A multi-structure API payload: more than one structure is returned, so the
  // first-structure shortcut would silently (and wrongly) adopt structA.
  private static Map multiStructureJson() {
    return [
      data: [
        [id: 'structA', attributes: [name: 'Home A']],
        [id: 'structB', attributes: [name: 'Home B']],
        [id: 'structC', attributes: [name: 'Home C']]
      ]
    ]
  }

  // Builds the sandboxed app. The structureId-capture hook is on getApp(), and
  // the blocking httpGet is stubbed on the executor (see header notes).
  private def buildScript(Map userSettings, Map capturedSettings) {
    blockingHttpInvoked = false
    InstalledAppWrapper appStub = Mock(InstalledAppWrapper) {
      _ * updateSetting(*_) >> { args -> capturedSettings[args[0]] = args[1] }
    }
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 0]
      _ * getApp() >> appStub
      _ * httpGet(_, _) >> { args ->
        blockingHttpInvoked = true
        if (blockingHttpResponse != null) { args[1].call(blockingHttpResponse) }
        return null
      }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': userSettings)
    script.atomicState = [activeRequests: 0]
    // now() is used by the request-tracking timestamps; keep it deterministic.
    script.metaClass.now = { -> 1000L }
    return script
  }

  // Mock async HTTP response object (mirrors the api-communication-tests shape)
  // with a getJson() that returns the supplied payload.
  private static def asyncResp(Map json) {
    return [
      hasProperty: { String prop -> prop == 'hasError' },
      hasError: { -> return false },
      getStatus: { -> return 200 },
      getJson: { -> return json }
    ]
  }

  def "handleStructureResponse does NOT adopt response.data.first() when >1 structure and none configured (R2.17/R2.18)"() {
    setup:
    def captured = [:]
    def script = buildScript([:], captured)

    when: "the async structure response returns more than one structure with none configured"
    script.handleStructureResponse(asyncResp(multiStructureJson()), null)

    then: "the deterministic helper requires an explicit selection (no auto-pick)"
    script.dabv2SelectStructureId(multiStructureJson().data, null).requireSelection == true

    and: "the app does NOT silently adopt the first structure, so no Home Id is resolved and no devices can be commanded"
    captured.structureId == null
  }

  def "getStructureData (blocking path) does NOT adopt response.data.first() when >1 structure and none configured (R2.17/R2.18)"() {
    setup:
    def captured = [:]
    // Build the payload EAGERLY (a captured local) so resp.getData() does no work
    // inside the app's try/catch -- a lazily-resolved helper call would be
    // swallowed and mask the blind-first() adoption we are asserting against.
    def bodyJson = multiStructureJson()
    blockingHttpResponse = [ success: true, getData: { -> bodyJson } ]
    def script = buildScript([:], captured)

    when: "the blocking structure fetch returns more than one structure with none configured"
    script.getStructureData()

    then: "the blocking path actually issued its HTTP fetch (guards a vacuous pass)"
    blockingHttpInvoked == true

    and: "the app does NOT silently adopt the first structure on the blocking path either"
    captured.structureId == null
  }

  def "handleStructureResponse adopts the single structure when exactly one is returned and none configured (R2.18 - pinned contract)"() {
    setup:
    def captured = [:]
    def script = buildScript([:], captured)
    // The common single-home account: exactly one structure is returned.
    def singleJson = [ data: [ [id: 'onlyStruct', attributes: [name: 'The Home']] ] ]

    when: "the async structure response returns exactly one structure with none configured"
    script.handleStructureResponse(asyncResp(singleJson), null)

    then: "the deterministic helper auto-adopts the single structure (no explicit selection needed)"
    script.dabv2SelectStructureId(singleJson.data, null) == [id: 'onlyStruct', requireSelection: false]

    and: "the single structure IS adopted as the instance-global Home Id (preserves today's UX)"
    captured.structureId == 'onlyStruct'
  }

  def "configured instance-global structureId is retained across a discovery re-run (R2.15/R2.16/R2.19)"() {
    setup:
    def captured = [:]
    // An instance that already has a Home Id configured (the common single-home case).
    def script = buildScript(['structureId': 'structB'], captured)
    // Re-run discovery: the API again returns multiple structures, and 'structB'
    // is NOT first -- a blind first() would corrupt the configured Home Id.
    def reorderedJson = multiStructureJson()

    when: "discovery re-runs against the multi-structure response"
    script.handleStructureResponse(asyncResp(reorderedJson), null)

    then: "the deterministic helper keeps the configured Home Id"
    script.dabv2SelectStructureId(reorderedJson.data, 'structB') == [id: 'structB', requireSelection: false]

    and: "the configured Home Id is never blindly re-picked to first() / overwritten with a different structure"
    captured.structureId == null || captured.structureId == 'structB'
  }
}
