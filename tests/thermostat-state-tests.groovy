package bot.flair

// Thermostat State Change Detection Tests
// Run `gradle build` to test

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class ThermostatStateTest extends Specification {

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

  def "isThermostatAboutToChangeStateTest - Basic Functionality"() {
    setup:
    def mockAtomicState = [:]
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> mockAtomicState
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = mockAtomicState

    expect:
    // Initial state - should trigger
    script.isThermostatAboutToChangeState('cooling', 22.0, 22.4) == true
    
    // Already triggered state - should not trigger again
    script.isThermostatAboutToChangeState('cooling', 22.0, 22.4) == false
  }

  def "isThermostatAboutToChangeStateTest - Cooling Mode Logic"() {
    setup:
    def mockAtomicState = [:]
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> mockAtomicState
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = mockAtomicState

    expect:
    // Cooling: temp + SETPOINT_OFFSET_C - VENT_PRE_ADJUSTMENT_THRESHOLD_C < setpoint
    // Returns FALSE when: temp + 0.7 - 0.2 < setpoint, i.e., temp + 0.5 < setpoint (too close)
    script.isThermostatAboutToChangeState('cooling', 22.0, 21.4) == false  // 21.4 + 0.5 < 22.0
    
    // Reset state for next test - should trigger when temp is in range
    mockAtomicState.clear()
    script.isThermostatAboutToChangeState('cooling', 22.0, 22.2) == true // 22.2 + 0.5 > 22.0
    
    // Reset state for next test  
    mockAtomicState.clear()
    script.isThermostatAboutToChangeState('cooling', 22.0, 23.0) == true // 23.0 + 0.5 > 22.0
  }

  def "isThermostatAboutToChangeStateTest - Heating Mode Logic"() {
    setup:
    def mockAtomicState = [:]
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> mockAtomicState
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = mockAtomicState

    expect:
    // Heating: temp - SETPOINT_OFFSET_C + VENT_PRE_ADJUSTMENT_THRESHOLD_C > setpoint  
    // Returns FALSE when: temp - 0.7 + 0.2 > setpoint, i.e., temp - 0.5 > setpoint (too close)
    script.isThermostatAboutToChangeState('heating', 22.0, 22.6) == false  // 22.6 - 0.5 > 22.0
    
    // Reset state for next test - should trigger when temp is in range
    mockAtomicState.clear()
    script.isThermostatAboutToChangeState('heating', 22.0, 21.8) == true // 21.8 - 0.5 < 22.0
    
    // Reset state for next test
    mockAtomicState.clear()
    script.isThermostatAboutToChangeState('heating', 22.0, 20.0) == true // 20.0 - 0.5 < 22.0
  }

  def "isThermostatAboutToChangeStateTest - Edge Cases"() {
    setup:
    def mockAtomicState = [:]
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getAtomicState() >> mockAtomicState
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = mockAtomicState

    expect:
    // Very close to boundary - should not trigger (too close to setpoint)
    script.isThermostatAboutToChangeState('cooling', 22.0, 21.501) == true  // Still in pre-adjustment range
    
    // Reset and test when already triggered
    mockAtomicState.clear()
    script.isThermostatAboutToChangeState('cooling', 22.0, 23.0) == true   // First call
    script.isThermostatAboutToChangeState('cooling', 22.0, 23.0) == false  // Second call - already triggered
    
    // Test boundary condition
    mockAtomicState.clear()
    script.isThermostatAboutToChangeState('heating', 22.0, 21.4) == true  // 21.4 - 0.5 < 22.0
  }
}
