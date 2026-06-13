
// Topology + configuration validation tests (Task 9.1, R4 / R5.6 / R19).
//
// App-orchestration (non-pure) behavior, exercised against lightweight fakes of
// the Hubitat surface (settings + state) via the hubitat_ci sandbox — NOT
// property-based. Covers:
//   - supported topology ranges: 1-100 rooms / 1-200 smart / 0-200 conventional
//     / 1-20 thermostats, with no hard-coded device identities (R4.1);
//   - invalid topology rejected with the offending entry indicated and the
//     last-valid configuration preserved (R4.3);
//   - the single documented rule for an unassigned smart vent (R4.7);
//   - zero-smart-vent thermostat is a no-op, still counting conventional vents
//     for combined-flow reporting, no error (R4.8);
//   - all new numerics validated/clamped to documented defaults (R19.1/R19.2);
//   - internal Celsius with boundary conversion honoring thermostat1TempUnit
//     (R19.3).
//
// Run `./gradlew test --tests '*TopologyConfigValidation*'`.

import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class TopologyConfigValidationTest extends Specification {

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
  // Numeric config: documented defaults + clamping (R19.1, R19.2)
  // ---------------------------------------------------------------------------

  def "numeric config falls back to documented defaults when unset"() {
    setup:
    def script = buildScript()

    expect:
    script.getDabV2ControlStrategy() == 'balance'
    script.getDabV2SafetyFloorPct() == 40.0
    script.getDabV2SpreadGuardrailC() == 1.0
    script.getDabV2SpreadImprovementDeadbandC() == 0.3
    script.getDabV2CrosscouplingEnabled() == true
    script.getDabV2AirflowLimitedMarginPct() == 5.0
    script.getDabV2AirflowLimitedErrorC() == 0.5
    script.getDabV2ConventionalVentCount() == 0
    script.getDabV2ConventionalOpenPct() == 100.0
    script.getDabV2ActiveIntervalMin() == 3
    script.getDabV2IdleIntervalMin() == 10
    script.getDabV2ShortCycleGapMin() == 10
    script.getDabV2PreAdjustDwellMin() == 5
    script.getDabV2PreAdjustTriggerC() == 1.0
  }

  def "safety floor is clamped to its safe range and defaults on garbage"() {
    expect:
    buildScript(['safetyFloorPct': value]).getDabV2SafetyFloorPct() == expected

    where:
    value  || expected
    10     || 20.0
    20     || 20.0
    55     || 55.0
    90     || 90.0
    95     || 90.0
    'abc'  || 40.0
    null   || 40.0
  }

  def "spread guardrail and deadband clamp to documented ranges"() {
    expect:
    buildScript(['spreadGuardrailC': 0.0]).getDabV2SpreadGuardrailC() == 0.2
    buildScript(['spreadGuardrailC': 9.0]).getDabV2SpreadGuardrailC() == 5.0
    buildScript(['spreadImprovementDeadbandC': -1.0]).getDabV2SpreadImprovementDeadbandC() == 0.0
    buildScript(['spreadImprovementDeadbandC': 9.0]).getDabV2SpreadImprovementDeadbandC() == 2.0
  }

  def "cross-coupling defaults enabled and is toggleable"() {
    expect:
    buildScript(['crosscouplingEnabled': false]).getDabV2CrosscouplingEnabled() == false
    buildScript(['crosscouplingEnabled': true]).getDabV2CrosscouplingEnabled() == true
    buildScript([:]).getDabV2CrosscouplingEnabled() == true
  }

  def "conventional vent count clamps to 0-200"() {
    expect:
    buildScript(['thermostat1AdditionalStandardVents': 250]).getDabV2ConventionalVentCount() == 200
    buildScript(['thermostat1AdditionalStandardVents': -3]).getDabV2ConventionalVentCount() == 0
    buildScript(['thermostat1AdditionalStandardVents': 12]).getDabV2ConventionalVentCount() == 12
  }

  def "evaluation intervals clamp to scheduler-safe ranges"() {
    expect:
    buildScript(['activeIntervalMin': 0]).getDabV2ActiveIntervalMin() == 1
    buildScript(['activeIntervalMin': 99]).getDabV2ActiveIntervalMin() == 30
    buildScript(['idleIntervalMin': 0]).getDabV2IdleIntervalMin() == 1
    buildScript(['idleIntervalMin': 999]).getDabV2IdleIntervalMin() == 60
  }

  def "legacy strategy is preserved and invalid strategy falls back to balance"() {
    expect:
    buildScript(['controlStrategy': 'dab']).getDabV2ControlStrategy() == 'dab'
    buildScript(['controlStrategy': 'balance']).getDabV2ControlStrategy() == 'balance'
    buildScript(['controlStrategy': 'nonsense']).getDabV2ControlStrategy() == 'balance'
  }

  def "getDabV2Config resolves the full validated configuration"() {
    setup:
    def script = buildScript(['safetyFloorPct': 5, 'spreadGuardrailC': 2.0])

    when:
    def cfg = script.getDabV2Config()

    then:
    cfg.safetyFloorPct == 20.0       // clamped up from 5
    cfg.spreadGuardrailC == 2.0
    cfg.controlStrategy == 'balance'
    cfg.conventionalOpenPct == 100.0
    cfg.crosscouplingEnabled == true
  }

  // ---------------------------------------------------------------------------
  // Topology ranges + rejection of invalid topology (R4.1, R4.3)
  // ---------------------------------------------------------------------------

  def "a topology within supported ranges is valid"() {
    setup:
    def script = buildScript()

    when:
    def result = script.validateDabV2Topology([rooms: 5, smartVents: 10, conventionalVents: 2, thermostats: 1])

    then:
    result.valid == true
    result.offendingEntry == null
  }

  def "boundary topology counts are accepted"() {
    setup:
    def script = buildScript()

    expect:
    script.validateDabV2Topology([rooms: 1, smartVents: 1, conventionalVents: 0, thermostats: 1]).valid == true
    script.validateDabV2Topology([rooms: 100, smartVents: 200, conventionalVents: 200, thermostats: 20]).valid == true
  }

  def "out-of-range topology is rejected and indicates the offending entry"() {
    setup:
    def script = buildScript()

    expect:
    script.validateDabV2Topology([rooms: roomCount, smartVents: smart, conventionalVents: conv, thermostats: stats]).offendingEntry == offending

    where:
    roomCount | smart | conv | stats || offending
    0         | 10    | 0    | 1     || 'rooms'
    101       | 10    | 0    | 1     || 'rooms'
    5         | 0     | 0    | 1     || 'smartVents'
    5         | 201   | 0    | 1     || 'smartVents'
    5         | 10    | 201  | 1     || 'conventionalVents'
    5         | 10    | 0    | 0     || 'thermostats'
    5         | 10    | 0    | 21    || 'thermostats'
  }

  def "a vent assigned to a non-existent room is rejected with the vent indicated"() {
    setup:
    def script = buildScript()
    def topology = [
      roomIds: ['room1', 'room2'],
      ventRoomAssignments: ['ventA': 'room1', 'ventB': 'ghostRoom'],
      conventionalVents: 0,
      thermostats: 1
    ]

    when:
    def result = script.validateDabV2Topology(topology)

    then:
    result.valid == false
    result.offendingEntry == 'vent:ventB'
  }

  // ---------------------------------------------------------------------------
  // Unassigned smart vent — single documented rule (R4.7)
  // ---------------------------------------------------------------------------

  def "an unassigned smart vent follows the documented exclusion rule"() {
    setup:
    def script = buildScript()
    def topology = [
      roomIds: ['room1'],
      ventRoomAssignments: ['ventA': 'room1', 'ventOrphan': null],
      conventionalVents: 0,
      thermostats: 1
    ]

    when:
    def result = script.validateDabV2Topology(topology)

    then:
    result.valid == true
    result.excludedUnassignedVents.contains('ventOrphan')
    script.dabV2UnassignedVentRule() == 'excluded-from-balancing-and-combined-flow'
  }

  // ---------------------------------------------------------------------------
  // Last-valid configuration preserved on rejection (R4.3)
  // ---------------------------------------------------------------------------

  def "applying an invalid topology preserves the last valid configuration"() {
    setup:
    def stateMap = [:]
    def script = buildScript([:], stateMap)
    def good = [rooms: 4, smartVents: 8, conventionalVents: 1, thermostats: 1]
    def bad = [rooms: 999, smartVents: 8, conventionalVents: 1, thermostats: 1]

    when:
    def okResult = script.applyDabV2Topology(good)

    then:
    okResult.valid == true
    stateMap.dabv2LastValidTopology == good

    when:
    def badResult = script.applyDabV2Topology(bad)

    then:
    badResult.valid == false
    badResult.offendingEntry == 'rooms'
    // last-valid configuration is unchanged
    stateMap.dabv2LastValidTopology == good
    badResult.lastValidTopology == good
  }

  // ---------------------------------------------------------------------------
  // Zero-smart-vent thermostat is a no-op (R4.8)
  // ---------------------------------------------------------------------------

  def "a thermostat with zero smart vents is a balancing no-op but still counts conventional vents"() {
    setup:
    def script = buildScript()

    expect:
    script.isDabV2ThermostatBalancingNoOp(0) == true
    script.isDabV2ThermostatBalancingNoOp(2) == false

    when:
    def plan = script.dabV2ThermostatPlan(0, 3)

    then:
    plan.balancingNoOp == true
    plan.countsForCombinedFlow == true
    plan.conventionalVents == 3
  }

  // ---------------------------------------------------------------------------
  // Celsius internal, conversion only at the boundary (R19.3)
  // ---------------------------------------------------------------------------

  def "centigrade-to-fahrenheit conversion is correct"() {
    setup:
    def script = buildScript()

    expect:
    Math.abs(script.convertCentigradeToFahrenheit(0) - 32) < 0.01
    Math.abs(script.convertCentigradeToFahrenheit(100) - 212) < 0.01
    Math.abs(script.convertCentigradeToFahrenheit(20) - 68) < 0.01
  }

  def "boundary conversion honors the Fahrenheit thermostat unit"() {
    setup:
    def script = buildScript(['thermostat1TempUnit': '2'])

    expect:
    Math.abs(script.dabV2BoundaryToCelsius(68) - 20) < 0.01
    Math.abs(script.dabV2CelsiusToBoundary(20) - 68) < 0.01
  }

  def "boundary conversion is a pass-through when the thermostat reports Celsius"() {
    setup:
    def script = buildScript(['thermostat1TempUnit': '1'])

    expect:
    script.dabV2BoundaryToCelsius(20) == 20
    script.dabV2CelsiusToBoundary(20) == 20
  }

  // ---------------------------------------------------------------------------
  // Topology derived from the app's room/vent mapping (no hard-coded identity)
  // ---------------------------------------------------------------------------

  def "topology derived from the live room-vent mapping validates"() {
    setup:
    def script = buildScript(['thermostat1': 'thermo'], [:],
      [ventsByRoomId: ['room1': ['v1', 'v2'], 'room2': ['v3']]])

    when:
    def topology = script.deriveDabV2Topology()
    def result = script.validateDabV2Topology(topology)

    then:
    topology.roomIds.size() == 2
    topology.ventRoomAssignments.size() == 3
    topology.thermostats == 1
    result.valid == true
  }
}
