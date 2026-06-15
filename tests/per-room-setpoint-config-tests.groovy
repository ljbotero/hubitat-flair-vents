
// Per-room setpoint configuration / setRoomSetpoint / clamp / conflict / defer
// tests (R3 — Per-room absolute targets / signed offsets).
//
// Strict-TDD FAILING specs for spec flair-vents-v0236 task 7.5. They pin the
// behaviour that task 7.6 implements:
//
//   * a new `setRoomSetpoint(value, mode)` Vent_Driver command following the
//     existing `setRoomActive` rule-control pattern (delegates to a parent
//     `patchRoomSetpoint`)                                            (R3.14/R3.15)
//   * per-room target/offset config keys persisted at the config boundary with
//     the same `clampDecimal` validation for both UI- and Rule-Machine-supplied
//     values (abs 10.0–32.0 °C, offset -5.0..+5.0 °C)                 (R3.4/R3.5/R3.16)
//   * conflict resolution: an absolute target plus a non-zero offset resolves to
//     the absolute target (authoritative) AND surfaces a configuration warning
//                                                                     (R3.1/R3.2)
//   * a per-room-target map built for the allocator that DEFERS a room with a
//     configured target but no usable temperature (never command on missing
//     data) and stays neutral for rooms with no per-room config       (R3.12)
//   * the existing anti-chatter cooldown / position deadband continues to gate
//     ordinary moves and exempt only floor-required moves             (R3.13)
//
// Genuinely-RED (drive task 7.6, methods/commands absent today):
//   - the driver `setRoomSetpoint` command + delegation
//   - app `patchRoomSetpoint` config-key persistence + clamping
//   - app `resolveRoomSetpoint` clamp + conflict + surfaced warning
//   - app `dabV2BuildPerRoomTargetC` per-room map build + missing-temp defer
//
// Green guards (already satisfied by shipped code, asserted here for R3.13
// coverage): the pure `shouldApplyVentMove` anti-chatter gate.
//
// Requirements: 3.1, 3.2, 3.4, 3.5, 3.12, 3.13, 3.14, 3.15, 3.16
// Design: §R3 (1–6, 12–16)
//
// Run `./gradlew test --tests '*PerRoomSetpointConfig*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class PerRoomSetpointConfigTest extends Specification {

  private static final String APP_FILE = Dabv2AppHarness.combinedAppText()
  private static final File VENT_DRIVER_FILE = new File('src/hubitat-flair-vents-driver.groovy')
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice,
            Flags.AllowWritingToSettings,
            Flags.AllowReadingNonInputSettings
          ]

  // Build the combined app script. `stateMap` is the SAME mutable map returned
  // by getState(), so the specs can read back what an app method persisted.
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

  // A minimal Flair vent stand-in exposing the room-id the parent reads, mirroring
  // the map-backed device fakes used elsewhere in the suite.
  private static Object fakeVent(String roomId) {
    Map attrs = ['room-id': roomId, 'room-name': "Room ${roomId}".toString()]
    return [
      getId       : { -> "v-${roomId}".toString() },
      currentValue: { String a -> attrs[a] },
      _attrs      : attrs,
    ]
  }

  // ===========================================================================
  // R3.14/R3.15 — the Vent_Driver setRoomSetpoint command (RED: absent today)
  // ===========================================================================

  def "Vent driver registers a setRoomSetpoint command (R3.15)"() {
    given:
    def driverText = VENT_DRIVER_FILE.text

    expect: 'a setRoomSetpoint command is declared in driver metadata'
    driverText =~ /command\s+['"]setRoomSetpoint['"]/
  }

  def "Vent driver setRoomSetpoint follows the setRoomActive pattern and delegates to the parent (R3.15)"() {
    given:
    def driverText = VENT_DRIVER_FILE.text

    expect: 'a setRoomSetpoint method exists'
    driverText =~ /def\s+setRoomSetpoint\s*\(/

    and: 'it delegates to a parent patchRoomSetpoint, mirroring setRoomActive -> parent.patchRoom'
    driverText =~ /parent\.patchRoomSetpoint\s*\(/
  }

  // ===========================================================================
  // R3.4/R3.5/R3.16 — config-boundary clamping (RED: patchRoomSetpoint absent)
  // Both UI and Rule-Machine inputs flow through the same clamp, so an
  // out-of-range value is pinned to the nearest documented bound.
  // ===========================================================================

  def "patchRoomSetpoint clamps an absolute target to 10.0-32.0 C and persists a per-room config key (R3.4/R3.16)"() {
    setup: 'Celsius unit so inputs need no boundary conversion'
    Map stateMap = [:]
    def script = buildScript(['thermostat1TempUnit': '1'], stateMap)
    def vent = fakeVent('rA')

    when: 'a Rule-Machine command supplies an over-range absolute target'
    script.patchRoomSetpoint(vent, 99.0, 'absolute')

    then: 'it is clamped to the 32.0 C upper bound and stored under the room id'
    def cfg = stateMap.perRoomSetpoints['rA']
    cfg != null
    (cfg.absTargetC as BigDecimal) == 32.0G

    when: 'an under-range absolute target is supplied'
    script.patchRoomSetpoint(vent, 5.0, 'absolute')

    then: 'it is clamped to the 10.0 C lower bound'
    (stateMap.perRoomSetpoints['rA'].absTargetC as BigDecimal) == 10.0G
  }

  def "patchRoomSetpoint clamps a signed offset to -5.0..+5.0 C and persists it (R3.5/R3.16)"() {
    setup:
    Map stateMap = [:]
    def script = buildScript(['thermostat1TempUnit': '1'], stateMap)
    def vent = fakeVent('rB')

    when: 'an over-range positive offset is supplied'
    script.patchRoomSetpoint(vent, 9.0, 'offset')

    then: 'it is clamped to +5.0 C'
    (stateMap.perRoomSetpoints['rB'].offsetC as BigDecimal) == 5.0G

    when: 'an over-range negative offset is supplied'
    script.patchRoomSetpoint(vent, -9.0, 'offset')

    then: 'it is clamped to -5.0 C'
    (stateMap.perRoomSetpoints['rB'].offsetC as BigDecimal) == -5.0G
  }

  // ===========================================================================
  // R3.1/R3.2/R3.4/R3.5 — config-boundary resolution + conflict warning
  // (RED: resolveRoomSetpoint absent). resolveRoomSetpoint resolves the
  // effective target in Celsius (delegating to the pure dabv2ResolveRoomTargetC)
  // and reports whether the absolute/offset conflict was resolved.
  // ===========================================================================

  def "resolveRoomSetpoint adds a clamped offset when no absolute target is set (R3.3/R3.5)"() {
    setup:
    def script = buildScript(['thermostat1TempUnit': '1'])

    when: 'shared 21.0 C, no absolute, +1.5 offset'
    def res = script.resolveRoomSetpoint(21.0G, null, 1.5G)

    then:
    (res.targetC as BigDecimal) == 22.5G
    res.conflict == false
  }

  def "resolveRoomSetpoint treats an absolute target as authoritative over a non-zero offset and surfaces a warning (R3.1/R3.2)"() {
    setup:
    def script = buildScript(['thermostat1TempUnit': '1'])

    when: 'a room is configured with BOTH an absolute target and a non-zero offset'
    def res = script.resolveRoomSetpoint(21.0G, 24.0G, 3.0G)

    then: 'the absolute target wins (offset ignored)'
    (res.targetC as BigDecimal) == 24.0G

    and: 'the conflict is flagged and a configuration warning is surfaced'
    res.conflict == true
    res.warning != null
    (res.warning as String).toLowerCase().contains('absolute')
  }

  def "resolveRoomSetpoint clamps an out-of-range absolute target even when in conflict (R3.4)"() {
    setup:
    def script = buildScript(['thermostat1TempUnit': '1'])

    when: 'an over-range absolute target plus an offset'
    def res = script.resolveRoomSetpoint(21.0G, 99.0G, 3.0G)

    then: 'the absolute target wins, clamped to 32.0 C, with the conflict flagged'
    (res.targetC as BigDecimal) == 32.0G
    res.conflict == true
  }

  // ===========================================================================
  // R3.12 — defer on missing temperature + per-room map building
  // (RED: dabV2BuildPerRoomTargetC absent). The app builds the per-room target
  // map the allocator consumes from the persisted config; a room with a
  // configured target but no usable temperature is OMITTED (deferred — never
  // command on missing data), and a room with no per-room config stays neutral
  // (offset 0 == baseline, so it is omitted too).
  // ===========================================================================

  def "dabV2BuildPerRoomTargetC resolves a configured room with temperature and defers one without (R3.12)"() {
    setup:
    Map stateMap = [perRoomSetpoints: [
      'withTemp': [absTargetC: 18.0G, offsetC: 0.0G],
      'noTemp'  : [absTargetC: 18.0G, offsetC: 0.0G],
      'neutral' : [absTargetC: null,  offsetC: 0.0G]
    ]]
    def script = buildScript(['thermostat1TempUnit': '1'], stateMap)
    def roomData = [
      [roomId: 'withTemp', tempC: 23.0G, active: true, ventIds: ['vw']],
      [roomId: 'noTemp',   tempC: null,  active: true, ventIds: ['vn']],
      [roomId: 'neutral',  tempC: 24.0G, active: true, ventIds: ['vu']]
    ]

    when:
    Map perRoom = script.dabV2BuildPerRoomTargetC(roomData, 22.0G)

    then: 'the configured room with a usable temperature gets its resolved target'
    (perRoom['withTemp'] as BigDecimal) == 18.0G

    and: 'the configured room with no usable temperature is deferred (omitted)'
    !perRoom.containsKey('noTemp')

    and: 'a room with no per-room config stays neutral (omitted == shared-setpoint baseline)'
    !perRoom.containsKey('neutral')
  }

  // ===========================================================================
  // R3.13 — anti-chatter gating (GREEN GUARD: shouldApplyVentMove ships today).
  // Asserted here so the R3.13 contract is pinned: ordinary moves honor the
  // cooldown / position deadband while a floor-required move is exempt.
  // ===========================================================================

  def "an ordinary move inside the cooldown window is gated, a floor-required move is exempt (R3.13)"() {
    setup:
    def script = buildScript()

    expect: 'ordinary move 1s after the last move -> suppressed by the cooldown'
    script.shouldApplyVentMove(
      currentOpen: 60.0, proposedOpen: 80.0, mustOpenToMeetFloor: false,
      nowMs: 1000L, lastMoveMs: 0L,
      cooldownMs: 180000L, minPercent: 5.0) == false

    and: 'a move strictly required to reach the floor is applied immediately'
    script.shouldApplyVentMove(
      currentOpen: 10.0, proposedOpen: 40.0, mustOpenToMeetFloor: true,
      nowMs: 1000L, lastMoveMs: 0L,
      cooldownMs: 180000L, minPercent: 5.0) == true
  }
}
