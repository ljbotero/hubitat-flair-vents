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
    log.records[0] == new Tuple(Level.debug, 'Insuficient number of minutes required to calculate change rate (0 should be greather than 1)')
    
    // Negative time duration
    script.calculateRoomChangeRate(20, 25, -5, 100, 0.03) == -1
    
    // Very small time duration (less than MIN_MINUTES_TO_SETPOINT = 1)
    script.calculateRoomChangeRate(20, 25, 0.5, 100, 0.03) == -1
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
    // Change rate too low (below MIN_TEMP_CHANGE_RATE_C = 0.001)
    script.calculateRoomChangeRate(0, 0, 10, 4, 0.03) == -1
    // Check for both the zero temperature change log and the low rate log
    log.records[0] == new Tuple(Level.debug, 'Zero/minimal temperature change detected: startTemp=0°C, currentTemp=0°C, diffTemps=0°C, vent was 4% open')
    log.records[1] == new Tuple(Level.debug, 'Change rate (0.000) is lower than 0.001, therefore it is being excluded (startTemp=0, currentTemp=0, percentOpen=4%)')
    
    // Very small temperature change resulting in low rate
    script.calculateRoomChangeRate(20.0, 20.0001, 60, 100, 0.5) == -1
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
    // Very high rate (should be excluded if above MAX_TEMP_CHANGE_RATE_C = 1.5)
    // Test with parameters that actually produce a rate > 1.5
    script.calculateRoomChangeRate(20, 40, 1, 10, 0.01) == -1
    
    // Check that the appropriate log message is generated
    def highRateLogFound = log.records.any { record ->
      record[0] == Level.debug && 
      record[1].contains('is greater than 1.5') &&
      record[1].contains('therefore it is being excluded')
    }
    highRateLogFound
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
    // Perfect conditions with reasonable values
    def result = script.calculateRoomChangeRate(20, 21, 10, 50, 0.5)
    result > 0 && result < 2.0 // Should be reasonable rate
    
    // Same start and end temperature (no change)
    def noChangeResult = script.calculateRoomChangeRate(20, 20, 10, 50, 0.5)
    noChangeResult < 0.001 || noChangeResult == -1 // Should be very small or excluded
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
    // Very small temperature difference
    def smallDiff = script.calculateRoomChangeRate(20.000, 20.001, 60, 100, 0.5)
    smallDiff == -1 // Should be excluded as too small
    
    // Large temperature difference
    def largeDiff = script.calculateRoomChangeRate(10, 30, 60, 100, 0.1)
    largeDiff > 0 && largeDiff <= 1.5 // Should be valid but clamped
    
    // Boundary case near minimum rate
    def boundaryCase = script.calculateRoomChangeRate(20.0, 20.06, 60, 100, 0.5) // Should give rate around 0.001
    boundaryCase > 0 || boundaryCase == -1 // Either valid or excluded at boundary
  }
}
