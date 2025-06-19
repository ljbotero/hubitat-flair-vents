package bot.flair

// Time and Duration Calculation Tests
// Run `gradle build` to test

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class TimeCalculationsTest extends Specification {

  private static final File APP_FILE = new File('src/hubitat-flair-vents-app.groovy')
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice
          ]
  private static final AbstractMap USER_SETTINGS = ['debugLevel': 1, 'thermostat1CloseInactiveRooms': true]

  def "calculateLongestMinutesToTargetTest - Standard Scenario"() {
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
      '1222bc5e': [rate:0.123, temp:26.444, active:true, name: '1'],
      '00f65b12':[rate:0.070, temp:25.784, active:true, name: '2'],
      'd3f411b2':[rate:0.035, temp:26.277, active:true, name: '3'],
      '472379e6':[rate:0.318, temp:24.892, active:true, name: '4'],
      '6ee4c352':[rate:0.318, temp:24.892, active:true, name: '5'],
      'c5e770b6':[rate:0.009, temp:23.666, active:true, name: '6'],
      'e522531c':[rate:0.061, temp:25.444, active:false, name: '7'],
      'acb0b95d':[rate:0.432, temp:25.944, active:true, name: '8']
    ]

    expect:
    script.calculateLongestMinutesToTarget(rateAndTempPerVentId, 'cooling', 23.666, 72) == 72
    log.records[2] == new Tuple(Level.warn, "'3' is estimated to take 74.600 minutes " +
      'to reach target temp, which is longer than the average 72.000 minutes')
  }

  def "calculateLongestMinutesToTargetTest - All Rooms Inactive"() {
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
    // All rooms inactive
    def rateAndTempPerVentId = [
      'vent1': [rate:0.5, temp:25, active:false, name: 'Room1'],
      'vent2': [rate:0.3, temp:23, active:false, name: 'Room2']
    ]
    script.calculateLongestMinutesToTarget(rateAndTempPerVentId, 'cooling', 20, 60, true) == -1
  }

  def "calculateLongestMinutesToTargetTest - All Rooms Reached Setpoint"() {
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
    // All rooms already reached setpoint
    def rateAndTempPerVentId = [
      'vent1': [rate:0.5, temp:18, active:true, name: 'Room1'],
      'vent2': [rate:0.3, temp:19, active:true, name: 'Room2']
    ]
    script.calculateLongestMinutesToTarget(rateAndTempPerVentId, 'cooling', 20, 60, true) == -1
  }

  def "calculateLongestMinutesToTargetTest - Zero Rate Scenarios"() {
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
    // Zero rate (should return 0)
    def rateAndTempPerVentId = [
      'vent1': [rate:0, temp:25, active:true, name: 'Room1']
    ]
    script.calculateLongestMinutesToTarget(rateAndTempPerVentId, 'cooling', 20, 60, true) == 0
  }

  def "calculateLongestMinutesToTargetTest - Very Slow Rate"() {
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
    // Very slow rate (should be clamped to maxRunningTime)
    def rateAndTempPerVentId = [
      'vent1': [rate:0.001, temp:30, active:true, name: 'Room1']
    ]
    def result = script.calculateLongestMinutesToTarget(rateAndTempPerVentId, 'cooling', 20, 60, true)
    result == 60 // Should be clamped to maxRunningTime
  }

  def "calculateLongestMinutesToTargetTest - Mixed Active/Inactive with closeInactiveRooms False"() {
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
    // Mix of active/inactive rooms with closeInactiveRooms = false
    def rateAndTempPerVentId = [
      'active1': [rate:0.5, temp:25, active:true, name: 'ActiveRoom'],
      'inactive1': [rate:0.3, temp:30, active:false, name: 'InactiveRoom']
    ]
    def result = script.calculateLongestMinutesToTarget(rateAndTempPerVentId, 'cooling', 20, 60, false)
    
    // Should consider both rooms since closeInactiveRooms is false
    result > 0 && result <= 60
  }

  def "calculateLongestMinutesToTargetTest - Heating vs Cooling"() {
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
    def rateAndTempPerVentId = [
      'room1': [rate:0.5, temp:15, active:true, name: 'ColdRoom']
    ]
    
    // Heating mode: room needs to warm up from 15 to 20 degrees
    def heatingResult = script.calculateLongestMinutesToTarget(rateAndTempPerVentId, 'heating', 20, 60, true)
    heatingResult == 10 // (20-15)/0.5 = 10 minutes
    
    // Cooling mode: room is already below setpoint, should be excluded
    def coolingResult = script.calculateLongestMinutesToTarget(rateAndTempPerVentId, 'cooling', 20, 60, true)
    coolingResult == -1 // Room already reached setpoint for cooling
  }

  def "calculateLongestMinutesToTargetTest - Warning Logging"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
      _ * getSetting('debugLevel') >> 1
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS)
    
    when:
    // Room that would take longer than max running time
    def rateAndTempPerVentId = [
      'slowRoom': [rate:0.1, temp:30, active:true, name: 'SlowRoom']
    ]
    def result = script.calculateLongestMinutesToTarget(rateAndTempPerVentId, 'cooling', 20, 30, true)
    
    then:
    result == 30 // Clamped to maxRunningTime
    
    // Check that warning was logged
    log.records.any { record ->
      record[0] == Level.warn && 
      record[1].contains('SlowRoom') && 
      record[1].contains('shows minimal temperature change')
    }
  }

  def "calculateLongestMinutesToTargetTest - Empty Input"() {
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
    // Empty input should return -1
    script.calculateLongestMinutesToTarget([:], 'cooling', 20, 60, true) == -1
  }
}
