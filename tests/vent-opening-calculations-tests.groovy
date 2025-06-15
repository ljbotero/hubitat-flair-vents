package bot.flair

// Vent Opening Percentage Calculation Tests
// Run `gradle build` to test

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class VentOpeningCalculationsTest extends Specification {

  private static final File APP_FILE = new File('src/hubitat-flair-vents-app.groovy')
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice
          ]
  private static final AbstractMap USER_SETTINGS = ['debugLevel': 1, 'thermostat1CloseInactiveRooms': true]

  def "calculateVentOpenPercentageTest - Basic Scenarios"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
          'validationFlags': VALIDATION_FLAGS,
          'userSettingValues': USER_SETTINGS)

    expect:
    def expectedVals = [35.518, 65.063, 86.336, 12.625, 14.249, 10.324, 9.961, 32.834, 100.0]
    def retVals = [
      script.calculateVentOpenPercentage('', 65, 70, 'heating', 0.715, 12.6),
      script.calculateVentOpenPercentage('', 61, 70, 'heating', 0.550, 20),
      script.calculateVentOpenPercentage('', 98, 82, 'cooling', 0.850, 20),
      script.calculateVentOpenPercentage('', 84, 82, 'cooling', 0.950, 20),
      script.calculateVentOpenPercentage('', 85, 82, 'cooling', 0.950, 20),
      script.calculateVentOpenPercentage('', 86, 82, 'cooling', 2.5, 90),
      script.calculateVentOpenPercentage('', 87, 82, 'cooling', 2.5, 900),
      script.calculateVentOpenPercentage('', 87, 85, 'cooling', 0.384, 10),
      script.calculateVentOpenPercentage('', 87, 85, 'cooling', 0, 10)
    ]
    expectedVals == retVals
  }

  def "calculateVentOpenPercentageTest - Already Reached Setpoint"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
          'validationFlags': VALIDATION_FLAGS,
          'userSettingValues': USER_SETTINGS)

    expect:
    script.calculateVentOpenPercentage('', 75, 70, 'heating', 0.1, 1) == 0
    log.records[0] == new Tuple(Level.debug, "'' is already warmer (75) than setpoint (70)")
    script.calculateVentOpenPercentage('', 75, 80, 'cooling', 0.1, 1) == 0
    log.records[1] == new Tuple(Level.debug, "'' is already cooler (75) than setpoint (80)")
  }

  def "calculateVentOpenPercentageTest - Extreme Values"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
          'validationFlags': VALIDATION_FLAGS,
          'userSettingValues': USER_SETTINGS)

    expect:
    // Zero rate (should return 100%)
    script.calculateVentOpenPercentage('TestRoom', 20, 25, 'heating', 0, 10) == 100.0
    
    // Very high rate (actual calculation based on exponential formula)
    script.calculateVentOpenPercentage('TestRoom', 15, 25, 'heating', 10.0, 30) == 10.700
    
    // Very low rate (should return 100% - use MIN_TEMP_CHANGE_RATE_C = 0.001)
    script.calculateVentOpenPercentage('TestRoom', 20, 25, 'heating', 0.001, 30) == 100.0
    
    // Zero time to target (should return 100%)
    script.calculateVentOpenPercentage('TestRoom', 20, 25, 'heating', 0.5, 0) == 100.0
    
    // Negative time to target (should return 100%)
    script.calculateVentOpenPercentage('TestRoom', 20, 25, 'heating', 0.5, -10) == 100.0
  }

  def "calculateVentOpenPercentageTest - Boundary Conditions"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
          'validationFlags': VALIDATION_FLAGS,
          'userSettingValues': USER_SETTINGS)

    expect:
    // Test minimum and maximum percentage bounds
    def result1 = script.calculateVentOpenPercentage('TestRoom', 20, 25, 'heating', 0.1, 60)
    result1 >= 0.0 && result1 <= 100.0
    
    // Test with very small temperature differences
    def result2 = script.calculateVentOpenPercentage('TestRoom', 20.001, 20.002, 'heating', 0.1, 60)
    result2 >= 0.0 && result2 <= 100.0
    
    // Test with large temperature differences
    def result3 = script.calculateVentOpenPercentage('TestRoom', 10, 30, 'heating', 0.5, 60)
    result3 >= 0.0 && result3 <= 100.0
  }

  def "calculateOpenPercentageForAllVentsTest - Standard Scenario"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS)
    def rateAndTempPerVentId = [
      '1222bc5e': [rate:0.123, temp:26.444, active:true],
      '00f65b12':[rate:0.070, temp:25.784, active:true],
      'd3f411b2':[rate:0.035, temp:26.277, active:true],
      '472379e6':[rate:0.318, temp:24.892, active:true],
      '6ee4c352':[rate:0.318, temp:24.892, active:true],
      'c5e770b6':[rate:0.009, temp:23.666, active:true],
      'e522531c':[rate:0.061, temp:25.444, active:false],
      'acb0b95d':[rate:0.432, temp:25.944, active:true]
    ]

    expect:
    script.calculateOpenPercentageForAllVents(rateAndTempPerVentId, 'cooling', 23.666, 60) == [
      '1222bc5e':23.554,
      '00f65b12':31.608,
      'd3f411b2':100.0,
      '472379e6':11.488,
      '6ee4c352':11.488,
      'c5e770b6':0.0,
      'e522531c':0.0,
      'acb0b95d':12.130
    ]
  }

  def "calculateOpenPercentageForAllVentsTest - Mixed Active/Inactive"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS)
    
    expect:
    // Mix of active/inactive rooms
    def rateAndTempPerVentId = [
      'active1': [rate:0.5, temp:25, active:true],
      'inactive1': [rate:0.3, temp:23, active:false],
      'active2': [rate:0.1, temp:22, active:true],
      'noRate': [rate:0.0001, temp:26, active:true] // Below minimum rate
    ]
    def result = script.calculateOpenPercentageForAllVents(rateAndTempPerVentId, 'cooling', 20, 30, true)
    
    result['active1'] > 0 && result['active1'] <= 100 // Should have reasonable opening
    result['inactive1'] == 0 // Inactive room should be closed
    result['active2'] > 0 && result['active2'] <= 100 // Should have reasonable opening
    result['noRate'] == 100 // Very low rate should open fully
    
    // Don't close inactive rooms
    def result2 = script.calculateOpenPercentageForAllVents(rateAndTempPerVentId, 'cooling', 20, 30, false)
    result2['inactive1'] > 0 // Should not be closed when closeInactiveRooms is false
  }

  def "calculateOpenPercentageForAllVentsTest - Edge Cases"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS)

    expect:
    // Empty input
    script.calculateOpenPercentageForAllVents([:], 'cooling', 20, 30) == [:]
    
    // All rooms already at setpoint
    def allAtSetpoint = [
      'room1': [rate:0.5, temp:19, active:true], // Below cooling setpoint
      'room2': [rate:0.3, temp:18, active:true], // Below cooling setpoint
    ]
    def result = script.calculateOpenPercentageForAllVents(allAtSetpoint, 'cooling', 20, 30)
    result['room1'] == 0
    result['room2'] == 0
    
    // Very high rates
    def highRates = [
      'room1': [rate:5.0, temp:25, active:true]
    ]
    def result3 = script.calculateOpenPercentageForAllVents(highRates, 'cooling', 20, 30)
    result3['room1'] >= 0 && result3['room1'] <= 100
  }
}
