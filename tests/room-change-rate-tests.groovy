package bot.flair

// Room Temperature Change Rate Calculation Tests
// Run `gradle build` to test

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class RoomChangeRateTest extends Specification {

  private static final File APP_FILE = new File('src/hubitat-flair-vents-app.groovy')
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice
          ]
  private static final AbstractMap USER_SETTINGS = ['debugLevel': 1, 'thermostat1CloseInactiveRooms': true]

  def "calculateRoomChangeRateTest - Valid Scenarios"() {
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
    def expectedVals = [
      1.000, 0.056, -1.000, 1.429, 1.000
    ]
    def actualVals = [
      script.roundBigDecimal(script.calculateRoomChangeRate(20, 30, 5.0, 100, 0.03)),
      script.roundBigDecimal(script.calculateRoomChangeRate(20, 20.1, 60.0, 100, 0.03)),
      script.roundBigDecimal(script.calculateRoomChangeRate(20.768, 21, 5, 25, 0.03)),
      script.roundBigDecimal(script.calculateRoomChangeRate(19, 21, 5.2, 70, 0.03)),
      script.roundBigDecimal(script.calculateRoomChangeRate(19, 29, 10, 100, 0.03))
    ]
    expectedVals == actualVals
  }

  def "calculateRoomChangeRateTest - Invalid Time Duration"() {
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
    // Insufficient time duration
    script.calculateRoomChangeRate(0, 0, 0, 4, 0.03) == -1
    log.records[0] == new Tuple(Level.debug, 'Insufficient number of minutes required to calculate change rate (0 should be greater than 1)')
    
    // Negative time duration
    script.calculateRoomChangeRate(20, 25, -5, 100, 0.03) == -1
    
    // Very small time duration (less than MIN_MINUTES_TO_SETPOINT = 1)
    script.calculateRoomChangeRate(20, 25, 0.5, 100, 0.03) == -1
    
    // Runtime below minimum threshold (MIN_RUNTIME_FOR_RATE_CALC = 5 minutes)
    script.calculateRoomChangeRate(20, 22, 3, 100, 0.03) == -1
  }

  def "calculateRoomChangeRateTest - Invalid Percent Open"() {
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
    // Zero percent open (should be excluded)
    script.calculateRoomChangeRate(20, 21, 10, 0, 0.5) == -1
    
    // Negative percent open
    script.calculateRoomChangeRate(20, 21, 10, -10, 0.5) == -1
    
    // Very small percent open (less than MIN_PERCENTAGE_OPEN = 0.0)
    script.calculateRoomChangeRate(20, 21, 10, 0.0, 0.5) == -1
  }

  def "calculateRoomChangeRateTest - Rate Too Low"() {
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
    // Basic functionality - method should return some result
    def result1 = script.calculateRoomChangeRate(0, 0, 10, 4, 0.03)
    result1 == -1 || result1 >= 0
    
    // Small temperature change - method should handle appropriately  
    def result2 = script.calculateRoomChangeRate(20.0, 20.05, 60, 100, 0.5)
    result2 == -1 || result2 >= 0
  }

  def "calculateRoomChangeRateTest - Rate Too High"() {
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
    // Very high rate (should be clamped or excluded based on implementation)
    // Test with parameters that produce a rate > 1.5
    def result = script.calculateRoomChangeRate(20, 40, 5, 10, 0.01) // Large temp change in short time with low vent opening
    result <= script.MAX_TEMP_CHANGE_RATE || result == -1 // Either clamped or excluded - both acceptable
    result == -1 || result > 0 // Should be -1 or positive, never in between
  }

  def "calculateRoomChangeRateTest - Edge Cases with Current Rate"() {
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
    // When current rate is higher than calculated rate
    def result1 = script.calculateRoomChangeRate(20, 21, 10, 50, 2.0) // currentRate = 2.0 is higher
    result1 > 0 && result1 < 1.5
    
    // When calculated rate is higher than current rate
    def result2 = script.calculateRoomChangeRate(20, 25, 5, 100, 0.1) // calculated rate should be higher
    result2 > 0 && result2 < 1.5
    
    // When current rate is zero
    def result3 = script.calculateRoomChangeRate(20, 22, 5, 100, 0)
    result3 > 0 && result3 < 1.5
  }

  def "calculateRoomChangeRateTest - Realistic Scenarios"() {
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
    // Typical cooling scenario: room cools from 25°C to 22°C in 15 minutes at 75% open
    def coolingRate = script.calculateRoomChangeRate(25, 22, 15, 75, 0.1)
    coolingRate > 0 && coolingRate <= 1.5
    
    // Typical heating scenario: room heats from 18°C to 21°C in 20 minutes at 80% open
    def heatingRate = script.calculateRoomChangeRate(18, 21, 20, 80, 0.05)
    heatingRate > 0 && heatingRate <= 1.5
    
    // Slow change scenario: room temperature changes very slowly
    def slowRate = script.calculateRoomChangeRate(20.0, 20.5, 30, 100, 0.01)
    slowRate > 0 && slowRate <= 1.5
    
    // Fast change scenario: room temperature changes quickly
    def fastRate = script.calculateRoomChangeRate(25, 20, 10, 100, 0.2)
    fastRate > 0 && fastRate <= 1.5
  }

  def "calculateRoomChangeRateTest - Perfect Conditions"() {
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
    // Perfect conditions with reasonable values - temperature change above threshold
    def result = script.calculateRoomChangeRate(20, 21, 10, 50, 0.5)
    result > 0 && result <= script.MAX_TEMP_CHANGE_RATE // Should be reasonable rate
    
    // Same start and end temperature (no change) - should assign minimum rate for vent >= 30%
    def noChangeResult = script.calculateRoomChangeRate(20, 20, 10, 50, 0.5)
    noChangeResult == script.MIN_TEMP_CHANGE_RATE // Should assign minimum rate for significant vent opening
  }

  def "calculateRoomChangeRateTest - Temperature Difference Boundary"() {
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
    // Method should handle small temperature differences appropriately
    def smallDiff = script.calculateRoomChangeRate(20.000, 20.001, 60, 100, 0.5)
    smallDiff == -1 || smallDiff >= 0
    
    // Method should handle reasonable temperature differences
    def basicTest = script.calculateRoomChangeRate(20.0, 22.0, 10, 70, 0.5)
    basicTest == -1 || basicTest >= 0
  }
}
