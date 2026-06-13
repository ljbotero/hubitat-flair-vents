
// Dead / contradictory code-path tests (Task 2.6, F-05 / M3 / L1 / L3, DC-5).
//
// docs/review-findings.md F-05 (dead/contradictory code paths) plus the
// task-2.6 finding detail:
//
//   L1 - handleAllPucks(): the per-loop body and the `if (puckCount > 0) { log
//        "Discovered N pucks" }` summary block are entangled with confusing,
//        inconsistent indentation. The summary must fire EXACTLY ONCE after the
//        loop (a discovery summary), never once per puck. These tests pin that
//        observable behavior so the structural clean-up provably preserves it.
//
//   L3 - evaluateRebalancingVents(): the "is this smart vent open enough to be
//        worth rebalancing" cutoff reused STANDARD_VENT_DEFAULT_OPEN (50), a
//        *conventional-vent* default, for an unrelated purpose. The repair
//        introduces a dedicated, equal-valued constant REBALANCING_MIN_OPEN_PERCENT
//        so the threshold's meaning is explicit. These tests pin the eligibility
//        semantics (skip at/below the threshold; rebalance above it once a room
//        has reached setpoint) so the value-preserving rename changes nothing.
//
//   M3 - canMakeRequest(): a request gate that, on reaching the concurrency cap,
//        RESET the in-flight counter to 0 and returned true - i.e. a throttle
//        that disables itself precisely when it should engage, allowing the cap
//        to be exceeded. The contradictory inline reset is removed: canMakeRequest
//        becomes a pure predicate (no side effect, returns false at/over the cap),
//        and the stuck-counter recovery remains solely in the periodic
//        cleanupPendingRequests() (runEvery5Minutes). Callers already retry a
//        false result via runInMillis, so no request is dropped - it is deferred.
//        These tests pin the corrected, non-contradictory contract.
//
// Requirements: 2.4, 2.6, 18.4
//
// Run `./gradlew test --tests '*DeadCodePaths*'`.

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class DeadCodePathsTest extends Specification {

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

  // ----- helpers -------------------------------------------------------------

  // Map-coerced HTTP response proxy. isValidResponse() short-circuits on
  // hasProperty('hasError') == false, so this is treated as a valid response.
  private static Map okResponse(Map json) {
    return [
      hasProperty: { String p -> false },
      getStatus  : { -> 200 },
      getJson    : { -> json },
    ]
  }

  // ===========================================================================
  // L1 - handleAllPucks logs the discovery summary exactly once
  // ===========================================================================

  def "L1: handleAllPucks logs the 'Discovered N pucks' summary exactly once, after the loop"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 1]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      _ * getChildDevice(_) >> { String id -> null }
      // addChildDevice is exercised by makeRealDevice; return null-ish so the
      // handler's per-puck create path is a no-op (puckCount is already counted).
      _ * addChildDevice(*_) >> { throw new RuntimeException('no device in test') }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.atomicState = [activeRequests: 1]

    def resp = okResponse([data: [
      [id: 'puck-1', attributes: [name: 'Puck One']],
      [id: 'puck-2', attributes: [name: 'Puck Two']],
      [id: 'puck-3', attributes: [name: 'Puck Three']],
    ]])

    when:
    script.handleAllPucks(resp, null)

    then: 'the discovery summary is logged once, not once per puck'
    def summaries = log.records.findAll {
      it[1]?.contains('pucks from all pucks endpoint')
    }
    summaries.size() == 1
    summaries[0][1].contains('Discovered 3 pucks')
  }

  def "L1: handleAllPucks logs no discovery summary when there are zero pucks"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 1]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      _ * getChildDevice(_) >> { String id -> null }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.atomicState = [activeRequests: 1]

    def resp = okResponse([data: []])

    when:
    script.handleAllPucks(resp, null)

    then: 'puckCount stays 0, so the summary block does not fire'
    log.records.findAll { it[1]?.contains('pucks from all pucks endpoint') }.size() == 0
  }

  // ===========================================================================
  // L3 - evaluateRebalancingVents eligibility threshold semantics
  // ===========================================================================

  private Object buildRebalanceScript(CapturingLog log, int percentOpen, Map flags) {
    // Fake vent backing the eligibility branch (room active, given aperture).
    ChildDeviceWrapper vent = [
      getId       : { -> 'vent-1' },
      currentValue: { Object... a ->
        switch (a[0]) {
          case 'room-active' : return 'true'
          case 'percent-open': return percentOpen
          case 'room-name'   : return 'Room 1'
          default            : return null
        }
      },
      toString    : { -> 'FakeVent(vent-1)' },
    ] as ChildDeviceWrapper

    // getChildDevice is a PLATFORM method - it must be stubbed on the executor,
    // not the script metaClass (which only intercepts app-defined methods).
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [
        thermostat1State: [mode: 'cooling'],
        ventsByRoomId   : ['room1': ['vent-1']],
      ]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
      _ * getChildDevice(_) >> { String id -> id == 'vent-1' ? vent : null }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.atomicState = [
      thermostat1State: [mode: 'cooling'],
      ventsByRoomId   : ['room1': ['vent-1']],
    ]

    // Isolate the eligibility branch from setpoint/temperature collaborators.
    script.metaClass.getThermostatSetpoint = { String mode -> 21.0G }
    // Room is well past the cooling setpoint -> hasRoomReachedSetpoint() is true.
    script.metaClass.getRoomTemp = { device -> 19.0G }
    script.metaClass.reBalanceVents = { -> flags.rebalanced = true }
    return script
  }

  def "L3: a vent open at exactly the rebalance threshold (50%) is skipped"() {
    setup:
    final log = new CapturingLog()
    def flags = [rebalanced: false]
    def script = buildRebalanceScript(log, 50, flags)

    when:
    script.evaluateRebalancingVents()

    then: 'percent-open <= threshold short-circuits, so no rebalance is triggered'
    flags.rebalanced == false
  }

  def "L3: a vent open above the threshold and past setpoint triggers a rebalance"() {
    setup:
    final log = new CapturingLog()
    def flags = [rebalanced: false]
    def script = buildRebalanceScript(log, 70, flags)

    when:
    script.evaluateRebalancingVents()

    then: 'an over-threshold, satisfied room is eligible to rebalance'
    flags.rebalanced == true
  }

  def "L3: the rebalance eligibility threshold is a dedicated, explicit constant equal to 50"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect: 'the threshold no longer borrows the conventional-vent default constant'
    script.REBALANCING_MIN_OPEN_PERCENT == 50
  }

  // ===========================================================================
  // M3 - canMakeRequest is a pure, non-contradictory throttle predicate
  // ===========================================================================

  private Object buildThrottleScript(int activeRequests) {
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: activeRequests]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [activeRequests: activeRequests]
    return script
  }

  def "M3: canMakeRequest is true below the cap and does not mutate the counter"() {
    setup:
    def script = buildThrottleScript(2)

    when:
    def canMake = script.canMakeRequest()

    then:
    canMake == true
    script.atomicState.activeRequests == 2 // pure predicate: no side effect
  }

  def "M3: canMakeRequest is false at the cap (throttle engages) and leaves the counter intact"() {
    setup:
    def script = buildThrottleScript(8) // MAX_CONCURRENT_REQUESTS

    when:
    def canMake = script.canMakeRequest()

    then: 'the throttle no longer disables itself by resetting the counter'
    canMake == false
    script.atomicState.activeRequests == 8
  }

  def "M3: canMakeRequest is false over the cap (cannot be bypassed to exceed the limit)"() {
    setup:
    def script = buildThrottleScript(12)

    when:
    def canMake = script.canMakeRequest()

    then:
    canMake == false
    script.atomicState.activeRequests == 12
  }

  def "M3: cleanupPendingRequests remains the single place that recovers a genuinely stuck counter"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
      _ * getAtomicState() >> [activeRequests: 99]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': ['debugLevel': 1])
    script.state = [:]
    script.atomicState = [activeRequests: 99]
    // Minimal instance caches so cleanupPendingRequests can iterate without NPE.
    script.metaClass.initializeInstanceCaches = { -> }
    script.metaClass.getInstanceId = { -> 'test-instance' }
    script.state['instanceCache_test-instance_pendingRoomRequests'] = [:]
    script.state['instanceCache_test-instance_pendingDeviceRequests'] = [:]

    when:
    script.cleanupPendingRequests()

    then: 'a stuck counter (>= cap) is reset to 0 by the periodic cleanup'
    script.atomicState.activeRequests == 0
  }
}
