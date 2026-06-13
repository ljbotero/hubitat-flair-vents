
// Cycle-finalize race tests (Task 2.4, F-01 / DC-3).
//
// docs/review-findings.md F-01 (Critical, DC-3): on transition to idle the
// handler snapshots the cycle params, schedules `finalizeRoomStates` 30s later
// (TEMP_READINGS_DELAY_MS) and IMMEDIATELY `atomicState.remove('thermostat1State')`.
// If the HVAC re-activates within that 30s window, the active branch creates a
// brand-new cycle and `recordStartingTemperatures()` overwrites the per-room
// starting temperatures; the still-pending finalize from the OLD cycle then
// fires DURING the new cycle and computes a learned rate from the new cycle's
// starting temps against the old cycle's time window — corrupting the new
// cycle's learned state. There is no cycle-identity (cycleId) guard, and the
// active branch never unschedules the pending finalize.
//
// These tests simulate active -> idle -> active within the finalize window and
// assert that a stale finalize from the superseded cycle is a NO-OP (it must not
// write/corrupt the new cycle's learned rate), while a legitimate finalize for
// the current cycle still runs and records the learned rate.
//
// Requirements: 2.3, 2.4, 21.7, 20.4
//
// Run `./gradlew test --tests '*CycleFinalizeRace*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class CycleFinalizeRaceTest extends Specification {

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
  private static final AbstractMap USER_SETTINGS = ['dabEnabled': true, 'debugLevel': 0]

  // Harness shared across phases: a controllable clock, a captured scheduler,
  // and a single fake vent whose attributes back getRoomTemp / starting temps.
  private static class Harness {
    Object script
    Map atomicState = [:]
    List scheduled = []
    Map clock = [v: 1_000_000L]
    Map ventAttrs = [:]
    List ventEvents = []
  }

  private Harness buildHarness() {
    def h = new Harness()
    h.ventAttrs.putAll([
      'room-name'                  : 'Room 1',
      'room-active'                : 'true',
      'percent-open'               : 50,
      'room-current-temperature-c' : 25.0,
      'room-cooling-rate'          : 0.5,
    ])
    // Map-coerced ChildDeviceWrapper proxy backed by the harness attribute map.
    ChildDeviceWrapper vent = [
      getId       : { -> 'vent-1' },
      currentValue: { Object... a -> h.ventAttrs[a[0]] },
      sendEvent   : { Object e -> h.ventAttrs[(e.name)] = e.value; h.ventEvents << e },
      toString    : { -> 'FakeVent(vent-1)' },
    ] as ChildDeviceWrapper
    def ventsById = ['vent-1': vent]
    h.atomicState.ventsByRoomId = ['room1': ['vent-1']]

    final log = new CapturingLog()
    // Platform (AppExecutor) methods are dispatched through the executor Mock,
    // NOT the script metaClass, so the controllable clock, captured scheduler,
    // and device fakes are stubbed here.
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> h.atomicState
      _ * getLog() >> log
      _ * now() >> { h.clock.v }
      _ * runInMillis(_, _, _) >> { Long d, String method, Map opts ->
        h.scheduled << [method: method, data: opts?.data]
      }
      _ * getChildDevice(_) >> { String id -> ventsById[id] }
      _ * getChildDevices() >> []
      _ * sendEvent(_, _) >> { device, Map ev -> device.sendEvent(ev) }
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS)
    h.script = script
    script.atomicState = h.atomicState

    // atomicStateUpdate is an app method; back it with the plain-map fake so
    // nested cycle state survives across phases.
    script.metaClass.atomicStateUpdate = { String stateKey, String key, value ->
      def m = (h.atomicState[stateKey] instanceof Map) ? h.atomicState[stateKey] : [:]
      m[(key)] = value
      h.atomicState[stateKey] = m
    }
    return h
  }

  def "stale finalize from a superseded cycle is a no-op during the new cycle (active -> idle -> active)"() {
    setup:
    def h = buildHarness()
    def script = h.script

    // --- Cycle A becomes active ---
    h.clock.v = 1_000_000L
    script.thermostat1ChangeStateHandler([value: 'cooling'])
    // recordStartingTemperatures() captured 25.0 as cycle A's starting temp.
    // Simulate initializeRoomStates having established the cycle start.
    h.atomicState.thermostat1State.startedCycle = h.clock.v

    // --- Cycle A runs 40 minutes, then the thermostat goes idle ---
    h.clock.v = 1_000_000L + (40L * 60 * 1000)
    h.ventAttrs['room-current-temperature-c'] = 23.0
    script.thermostat1ChangeStateHandler([value: 'idle'])
    // The idle branch scheduled a finalize for cycle A and removed thermostat1State.
    def finalizeEntry = h.scheduled.find { it.method == 'finalizeRoomStates' }
    assert finalizeEntry != null
    def paramsA = finalizeEntry.data

    // --- Cycle B re-activates 5s later, WITHIN the 30s finalize window ---
    h.clock.v += 5000
    h.ventAttrs['room-current-temperature-c'] = 24.0
    script.thermostat1ChangeStateHandler([value: 'cooling'])
    // recordStartingTemperatures() overwrote the starting temp with cycle B's 24.0.
    h.atomicState.thermostat1State.startedCycle = h.clock.v

    when: "the stale finalize timer from cycle A fires during cycle B"
    h.ventAttrs['room-current-temperature-c'] = 22.0
    h.ventEvents.clear()
    script.finalizeRoomStates(paramsA)

    then: "the stale finalize must not write/corrupt the new cycle's learned rate"
    h.ventEvents.find { it.name == 'room-cooling-rate' } == null

    and: "the new cycle's state is intact"
    h.atomicState.thermostat1State?.mode == 'cooling'
  }

  def "a legitimate finalize for the current cycle still runs and records the learned rate"() {
    setup:
    def h = buildHarness()
    def script = h.script

    // --- Cycle A becomes active ---
    h.clock.v = 1_000_000L
    script.thermostat1ChangeStateHandler([value: 'cooling'])
    h.atomicState.thermostat1State.startedCycle = h.clock.v

    // --- Cycle A runs 40 minutes, then idle (no re-activation) ---
    h.clock.v = 1_000_000L + (40L * 60 * 1000)
    script.thermostat1ChangeStateHandler([value: 'idle'])
    def finalizeEntry = h.scheduled.find { it.method == 'finalizeRoomStates' }
    def paramsA = finalizeEntry.data

    when: "the finalize timer fires with no newer cycle having started"
    h.ventAttrs['room-current-temperature-c'] = 22.0
    h.ventEvents.clear()
    script.finalizeRoomStates(paramsA)

    then: "the learned cooling rate is recorded for the room"
    h.ventEvents.find { it.name == 'room-cooling-rate' } != null
  }
}
