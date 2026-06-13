
// DAB v2 parity-gated default flip + legacy-preservation tests
// (Task 10.2; R17.5, R20.1, R20.2, R20.5).
//
// App-orchestration (non-pure) behavior exercised against lightweight fakes of
// the Hubitat surface (settings + state) via the hubitat_ci sandbox. Covers:
//   - the new-install default control strategy is `balance` ONLY when the parity
//     suite (task 8.3 / Property 17) has passed; if the parity gate is NOT
//     satisfied the new-install default stays the legacy `dab` strategy so an
//     unvalidated `balance` never controls a house (R17.5 / R20.1);
//   - an existing user's EXPLICITLY configured strategy is preserved verbatim on
//     upgrade — never silently flipped to `balance` (R20.2);
//   - an existing user upgrading from a version WITHOUT the strategy input keeps
//     the legacy strategy (no silent override to `balance`) (R20.2);
//   - the live parity gate is currently satisfied, so `balance` is the live
//     new-install default (R17.5 — parity suite 8.3 is green).
//
// Run `./gradlew test --tests '*Dabv2ParityGatedDefault*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class Dabv2ParityGatedDefaultTest extends Specification {

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

  private Object buildScript(Map userSettings = [:], Map stateMap = [:], Map atomicStateMap = [:]) {
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> stateMap
      _ * getAtomicState() >> atomicStateMap
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': userSettings)
    script.atomicState = atomicStateMap
    return script
  }

  // ---------------------------------------------------------------------------
  // Parity gate controls the new-install default (R17.5 / R20.1)
  // ---------------------------------------------------------------------------

  def "the parity-gated default is balance only when the parity suite passes"() {
    setup:
    def script = buildScript()

    expect:
    // The default flip is GATED on the parity suite: balance only when parity
    // passes; otherwise the legacy strategy remains the default (R17.5/R20.1).
    script.dabV2DefaultStrategyForParity(true) == 'balance'
    script.dabV2DefaultStrategyForParity(false) == 'dab'
  }

  def "the new-install default flips to balance only after the parity gate passes"() {
    setup:
    def script = buildScript()

    expect:
    // New install + parity passed -> balance (R20.1).
    script.resolveDabV2StrategyDefault(null, true, true) == 'balance'
    // New install + parity NOT passed -> legacy stays the default (R17.5).
    script.resolveDabV2StrategyDefault(null, true, false) == 'dab'
  }

  // ---------------------------------------------------------------------------
  // Existing user's explicit strategy preserved on upgrade (R20.2)
  // ---------------------------------------------------------------------------

  def "an existing user's explicit strategy is preserved on upgrade and never silently overridden"() {
    setup:
    def script = buildScript()

    expect:
    // Existing explicit `dab` on upgrade stays `dab` even though parity passed —
    // NO silent flip to balance (R20.2).
    script.resolveDabV2StrategyDefault('dab', false, true) == 'dab'
    // Existing explicit `balance` on upgrade stays `balance`.
    script.resolveDabV2StrategyDefault('balance', false, true) == 'balance'
    // An explicit choice always wins, even on a fresh install.
    script.resolveDabV2StrategyDefault('dab', true, true) == 'dab'
  }

  def "a legacy user upgrading without an explicit strategy keeps legacy (no silent balance)"() {
    setup:
    def script = buildScript()

    expect:
    // Upgrade (not a fresh install) with no explicit selection -> legacy, so a
    // pre-DAB-v2 user is never silently flipped to balance (R20.2).
    script.resolveDabV2StrategyDefault(null, false, true) == 'dab'
  }

  // ---------------------------------------------------------------------------
  // Live parity gate is satisfied -> balance is the live new-install default
  // ---------------------------------------------------------------------------

  def "the live new-install default is balance because the parity suite (8.3) is green"() {
    setup:
    def script = buildScript()

    expect:
    script.dabV2NewInstallDefaultStrategy() == 'balance'
  }

  // ---------------------------------------------------------------------------
  // Lifecycle applier: seeds new installs, preserves upgrades, idempotent
  // ---------------------------------------------------------------------------

  def "a fresh install adopts the parity-gated default and records the decision"() {
    setup:
    def stateMap = [dabV2FreshInstall: true]
    def script = buildScript([:], stateMap)

    when:
    String applied = script.applyDabV2StrategyDefault()

    then:
    applied == 'balance'
    stateMap.dabV2StrategyDefaultApplied == true
  }

  def "an upgrade with an explicit strategy preserves it"() {
    setup:
    def stateMap = [:]
    def script = buildScript([controlStrategy: 'dab'], stateMap)

    when:
    String applied = script.applyDabV2StrategyDefault()

    then:
    applied == 'dab'
    stateMap.dabV2StrategyDefaultApplied == true
  }

  def "an upgrade without an explicit strategy keeps legacy"() {
    setup:
    def stateMap = [:]
    def script = buildScript([:], stateMap)

    when:
    String applied = script.applyDabV2StrategyDefault()

    then:
    applied == 'dab'
  }

  def "the default decision is applied once and never re-flips an explicit choice"() {
    setup:
    // Decision already recorded; the applier must be a no-op (idempotent) and
    // must not override the user's current explicit choice (R20.2).
    def stateMap = [dabV2StrategyDefaultApplied: true]
    def script = buildScript([controlStrategy: 'dab'], stateMap)

    when:
    String applied = script.applyDabV2StrategyDefault()

    then:
    applied == 'dab'
  }

}