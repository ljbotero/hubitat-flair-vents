package bot.flair

// Room Setpoint and HVAC State Detection Tests
// Run `gradle build` to test

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class RoomSetpointTest extends Specification {

  private static final File APP_FILE = new File('src/hubitat-flair-vents-app.groovy')
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice,
            Flags.AllowWritingToSettings,
            Flags.AllowReadingNonInputSettings
          ]

  def "hasRoomReachedSetpointTest - Basic Scenarios"() {
    setup:
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    // Cooling scenarios
    script.hasRoomReachedSetpoint('cooling', 80, 75) == true
    script.hasRoomReachedSetpoint('cooling', 80, 79) == true
    script.hasRoomReachedSetpoint('cooling', 80, 80) == true
    script.hasRoomReachedSetpoint('cooling', 80, 81) == false

    // Heating scenarios
    script.hasRoomReachedSetpoint('heating', 70, 65) == false
    script.hasRoomReachedSetpoint('heating', 70, 69) == false
    script.hasRoomReachedSetpoint('heating', 70, 70) == true
    script.hasRoomReachedSetpoint('heating', 70, 70.01) == true
  }

  def "hasRoomReachedSetpointTest - With Offset"() {
    setup:
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    // Cooling with offset
    script.hasRoomReachedSetpoint('cooling', 22.0, 21.0, 0.5) == true   // 21 <= 22-0.5
    script.hasRoomReachedSetpoint('cooling', 22.0, 21.6, 0.5) == false  // 21.6 > 22-0.5
    
    // Heating with offset  
    script.hasRoomReachedSetpoint('heating', 22.0, 23.0, 0.5) == true   // 23 >= 22+0.5
    script.hasRoomReachedSetpoint('heating', 22.0, 22.4, 0.5) == false  // 22.4 < 22+0.5
    
    // Zero offset (default behavior)
    script.hasRoomReachedSetpoint('cooling', 22.0, 22.0, 0) == true
    script.hasRoomReachedSetpoint('heating', 22.0, 22.0, 0) == true
  }

  def "hasRoomReachedSetpointTest - Edge Cases"() {
    setup:
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    // Very small differences
    script.hasRoomReachedSetpoint('cooling', 22.0, 21.999, 0) == true
    script.hasRoomReachedSetpoint('heating', 22.0, 22.001, 0) == true
    
    // Large offsets
    script.hasRoomReachedSetpoint('cooling', 22.0, 20.0, 1.0) == true  // 20 <= 22-1 = 21
    script.hasRoomReachedSetpoint('heating', 22.0, 24.0, 1.0) == true   // 24 >= 22+1 = 23
    
    // Negative offsets (invalid but should handle gracefully)
    script.hasRoomReachedSetpoint('cooling', 22.0, 23.0, -1.0) == true  // 23 <= 22-(-1) = 23
    script.hasRoomReachedSetpoint('heating', 22.0, 21.0, -1.0) == true  // 21 >= 22+(-1) = 21
  }

  def "isThermostatAboutToChangeStateTest - Cooling Pre-adjustment"() {
    setup:
    def mockAtomicState = [tempDiffsInsideThreshold: false]
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> mockAtomicState
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = mockAtomicState

    expect:
    // Cooling mode: temp approaching setpoint from above
    script.isThermostatAboutToChangeState('cooling', 22.0, 23.0) == true  // Within threshold
    script.isThermostatAboutToChangeState('cooling', 22.0, 21.5) == false // Too cold already
    script.isThermostatAboutToChangeState('cooling', 22.0, 24.0) == false // Too far away
  }

  def "isThermostatAboutToChangeStateTest - Heating Pre-adjustment"() {
    setup:
    def mockAtomicState = [tempDiffsInsideThreshold: false]
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> mockAtomicState
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = mockAtomicState

    expect:
    // Heating mode: temp approaching setpoint from below  
    script.isThermostatAboutToChangeState('heating', 22.0, 21.0) == true  // Within threshold
    script.isThermostatAboutToChangeState('heating', 22.0, 23.0) == false // Too warm already
    script.isThermostatAboutToChangeState('heating', 22.0, 19.0) == true  // Far away should trigger
  }

  def "isThermostatAboutToChangeStateTest - Already Triggered"() {
    setup:
    def mockAtomicState = [tempDiffsInsideThreshold: true]
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> mockAtomicState
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = mockAtomicState

    expect:
    // Should return false when already triggered
    script.isThermostatAboutToChangeState('cooling', 22.0, 23.0) == false
    script.isThermostatAboutToChangeState('heating', 22.0, 21.0) == false
  }

  def "isThermostatAboutToChangeStateTest - Cooling Boundary Conditions"() {
    setup:
    def mockAtomicState = [tempDiffsInsideThreshold: false]
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> mockAtomicState
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = mockAtomicState

    expect:
    // Test exact boundary conditions (using constants from source)
    // SETPOINT_OFFSET_C = 0.7, VENT_PRE_ADJUSTMENT_THRESHOLD_C = 0.2
    
    // Cooling: temp + SETPOINT_OFFSET_C - VENT_PRE_ADJUSTMENT_THRESHOLD_C < setpoint
    // Returns FALSE when too close: temp + 0.7 - 0.2 < setpoint, i.e., temp + 0.5 < setpoint
    script.isThermostatAboutToChangeState('cooling', 22.0, 21.4) == false // 21.4 + 0.5 < 22.0 (too close)
  }

  def "isThermostatAboutToChangeStateTest - Heating Boundary Condition 1"() {
    setup:
    def mockAtomicState = [tempDiffsInsideThreshold: false]
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> mockAtomicState
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = mockAtomicState

    expect:
    // Heating: temp - SETPOINT_OFFSET_C + VENT_PRE_ADJUSTMENT_THRESHOLD_C > setpoint  
    // Returns FALSE when too close: temp - 0.7 + 0.2 > setpoint, i.e., temp - 0.5 > setpoint
    script.isThermostatAboutToChangeState('heating', 22.0, 22.6) == false // 22.6 - 0.5 > 22.0 (too close)
  }

  def "isThermostatAboutToChangeStateTest - Heating Boundary Condition 2"() {
    setup:
    def mockAtomicState = [tempDiffsInsideThreshold: false]
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> mockAtomicState
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = mockAtomicState

    expect:
    // Heating: temp - SETPOINT_OFFSET_C + VENT_PRE_ADJUSTMENT_THRESHOLD_C > setpoint  
    // Returns TRUE when temp - 0.5 <= setpoint (boundary case triggers pre-adjustment)
    script.isThermostatAboutToChangeState('heating', 22.0, 22.5) == true // 22.5 - 0.5 = 22.0 (boundary triggers)
  }
}
