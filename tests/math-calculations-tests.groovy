package bot.flair

// Math and Calculation Functions Tests
// Run `gradle build` to test

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class MathCalculationsTest extends Specification {

  private static final File APP_FILE = new File('src/hubitat-flair-vents-app.groovy')
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice
          ]

  def "roundToNearestFifthTest - Standard Cases"() {
    setup:
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.roundToNearestFifth(12.4) == 10
    script.roundToNearestFifth(12.5) == 15
    script.roundToNearestFifth(12.6) == 15
    script.roundToNearestFifth(95.6) == 95
    script.roundToNearestFifth(97.5) == 100
  }

  def "roundToNearestFifthTest - Edge Cases"() {
    setup:
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.roundToNearestFifth(0) == 0
    script.roundToNearestFifth(2.5) == 5
    script.roundToNearestFifth(7.5) == 10
    script.roundToNearestFifth(100) == 100
    script.roundToNearestFifth(102.5) == 105  // Function doesn't cap at 100
    script.roundToNearestFifth(-2.5) == 0
    script.roundToNearestFifth(-7.5) == -5
  }

  def "rollingAverageTest - Basic Scenarios"() {
    setup:
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.rollingAverage(10, 15, 1, 2) == 12.5
    script.rollingAverage(10, 15, 0.5, 2) == 11.25
    script.rollingAverage(10, 15, 0, 2) == 10
    script.rollingAverage(10, 5, 1, 2) == 7.5
    script.rollingAverage(10, 5, 0.5, 2) == 8.75
    script.rollingAverage(10, 5, 1, 1000) == 9.995
  }

  def "rollingAverageTest - Edge Cases"() {
    setup:
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    // Zero entries should return 0
    script.rollingAverage(10, 15, 1, 0) == 0
    
    // Negative entries should return 0  
    script.rollingAverage(10, 15, 1, -5) == 0
    
    // Single entry (numEntries = 1)
    script.rollingAverage(10, 15, 1, 1) == 15
    
    // Very large weight - corrected expected value
    script.rollingAverage(10, 15, 10, 2) == 35
    
    // Zero weight (no change)
    script.rollingAverage(10, 15, 0, 2) == 10
    
    // Negative weight (reverse effect)
    script.rollingAverage(10, 15, -0.5, 2) == 8.75
    
    // Null current average
    script.rollingAverage(null, 15, 1, 2) == 15
    script.rollingAverage(0, 15, 1, 2) == 15
  }

  def "roundBigDecimalTest - Precision Handling"() {
    setup:
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.roundBigDecimal(3.14159, 0) == 3
    script.roundBigDecimal(3.14159, 1) == 3.1
    script.roundBigDecimal(3.14159, 2) == 3.14
    script.roundBigDecimal(3.14159, 3) == 3.142
    script.roundBigDecimal(3.14159, 4) == 3.1416
    script.roundBigDecimal(3.14159, 5) == 3.14159
    
    // Test rounding edge cases
    script.roundBigDecimal(2.5, 0) == 3  // Round half up
    script.roundBigDecimal(3.5, 0) == 4  // Round half up
    script.roundBigDecimal(-2.5, 0) == -3 // Round half up actually rounds down for negative
  }

  def "roundBigDecimalTest - Default Precision"() {
    setup:
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    // Default precision is 3 decimal places
    script.roundBigDecimal(3.14159) == 3.142
    script.roundBigDecimal(1.23456) == 1.235
    script.roundBigDecimal(0.0009) == 0.001
    script.roundBigDecimal(999.9999) == 1000.000
  }
}
