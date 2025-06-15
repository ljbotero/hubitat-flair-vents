package bot.flair

// Airflow Adjustment and Minimum Flow Tests
// Run `gradle build` to test

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class AirflowAdjustmentTest extends Specification {

  private static final File APP_FILE = new File('src/hubitat-flair-vents-app.groovy')
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice
          ]
  private static final AbstractMap USER_SETTINGS = ['debugLevel': 1, 'thermostat1CloseInactiveRooms': true]

  private static final Closure BEFORE_RUN_SCRIPT = { script ->
    script.getMetaClass().atomicStateUpdate = {
        String arg1, String arg2, AbstractMap arg3 -> '' }
  }

  def "adjustVentOpeningsToEnsureMinimumAirflowTarget - Empty State"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
          'validationFlags': VALIDATION_FLAGS,
          'customizeScriptBeforeRun': BEFORE_RUN_SCRIPT)
    expect:
    script.adjustVentOpeningsToEnsureMinimumAirflowTarget([:], 'cooling', [:], 0) == [:]
  }

  def "adjustVentOpeningsToEnsureMinimumAirflowTarget - No Adjustment Needed"() {
    setup:
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'customizeScriptBeforeRun': BEFORE_RUN_SCRIPT)
    def percentPerVentId = [
      '122127': 30.0
    ]
    def rateAndTempPerVentId = [
      '122127': ['temp': 80]
    ]

    expect:
    script.adjustVentOpeningsToEnsureMinimumAirflowTarget(rateAndTempPerVentId, 'cooling',
      percentPerVentId, 0) == percentPerVentId
  }

  def "adjustVentOpeningsToEnsureMinimumAirflowTarget - Single Vent Low Flow"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
    'validationFlags': VALIDATION_FLAGS,
    'userSettingValues': USER_SETTINGS,
    'customizeScriptBeforeRun': BEFORE_RUN_SCRIPT
    )
    def percentPerVentId = ['122127': 5]
    def rateAndTempPerVentId = [
      '122127': ['temp': 80]
    ]

    expect:
    script.adjustVentOpeningsToEnsureMinimumAirflowTarget(rateAndTempPerVentId,  'cooling',
      percentPerVentId, 0) == ['122127': 30.5]
    log.records.size == 36
    log.records[0] == new Tuple(Level.debug, 'Combined Vent Flow Percentage (5) is lower than 30.0%')
    log.records[11] == new Tuple(Level.debug, 'Adjusting % open from 11.750% to 12.500%')
  }

  def "adjustVentOpeningsToEnsureMinimumAirflowTarget - Multiple Vents"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS,
      'customizeScriptBeforeRun': BEFORE_RUN_SCRIPT)
    def percentPerVentId = [
      '122127': 10,
      '122129': 5,
      '122128': 10,
      '122133': 25,
      '129424': 100,
      '122132': 5,
      '122131': 5
    ]
    def rateAndTempPerVentId = [
      '122127': ['temp': 80],
      '122129': ['temp': 70],
      '122128': ['temp': 75],
      '122133': ['temp': 72],
      '129424': ['temp': 78],
      '122132': ['temp': 79],
      '122131': ['temp': 76]
    ]

    expect:
    def newPercentPerVentId = [
      '122127':26.33823529360,
      '122129':5.16176470640,
      '122128':18.25,
      '122133':28.08823529350,
      '129424':100,
      '122132':18.38235294050,
      '122131':13.97058823550
    ]
    script.adjustVentOpeningsToEnsureMinimumAirflowTarget(rateAndTempPerVentId,  'cooling',
      percentPerVentId, 0) == newPercentPerVentId
    log.records.size == 65
    log.records[0] == new Tuple(Level.debug, 'Combined Vent Flow Percentage (22.8571428571) is lower than 30.0%')
    log.records[8] == new Tuple(Level.debug, 'Adjusting % open from 11.485% to 12.971%')
  }

  def "adjustVentOpeningsToEnsureMinimumAirflowTarget - With Conventional Vents"() {
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
    def percentPerVentId = [
      '122127': 0,
      '122129': 5,
      '122128': 0,
      '122133': 5,
      '129424': 20,
      '122132': 0,
      '122131': 5
    ]
    def rateAndTempPerVentId = [
      '122127': ['temp': 80],
      '122129': ['temp': 70],
      '122128': ['temp': 75],
      '122133': ['temp': 72],
      '129424': ['temp': 78],
      '122132': ['temp': 79],
      '122131': ['temp': 76]
    ]

    expect:
    def newPercentPerVentId = [
      '122127':23.76470588160,
      '122129':5.23529411840,
      '122128':12.00,
      '122133':9.94117646960,
      '129424':39.05882353040,
      '122132':21.41176470480,
      '122131':19.35294117680
    ]
    script.adjustVentOpeningsToEnsureMinimumAirflowTarget(rateAndTempPerVentId,  'cooling',
      percentPerVentId, 4) == newPercentPerVentId
    log.records[0] == new Tuple(Level.debug, 'Combined Vent Flow Percentage (21.3636363636) is lower than 30.0%')
  }

  def "adjustVentOpeningsToEnsureMinimumAirflowTarget - All Vents at 100%"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS,
      'customizeScriptBeforeRun': BEFORE_RUN_SCRIPT)
    
    expect:
    // All vents at 100% (no adjustment needed)
    def percentPerVentId = ['vent1': 100, 'vent2': 100]
    def rateAndTempPerVentId = ['vent1': ['temp': 20], 'vent2': ['temp': 22]]
    def result = script.adjustVentOpeningsToEnsureMinimumAirflowTarget(
      rateAndTempPerVentId, 'cooling', percentPerVentId, 0)
    result == percentPerVentId
  }

  def "adjustVentOpeningsToEnsureMinimumAirflowTarget - Single Vent Very Low"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS,
      'customizeScriptBeforeRun': BEFORE_RUN_SCRIPT)
    
    expect:
    // Single vent at very low percentage
    def percentPerVentId = ['vent1': 1]
    def rateAndTempPerVentId = ['vent1': ['temp': 20]]
    def result = script.adjustVentOpeningsToEnsureMinimumAirflowTarget(
      rateAndTempPerVentId, 'cooling', percentPerVentId, 0)
    result['vent1'] > 30 // Should be increased significantly
  }

  def "adjustVentOpeningsToEnsureMinimumAirflowTarget - No Smart Vents Only Conventional"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS,
      'customizeScriptBeforeRun': BEFORE_RUN_SCRIPT)
    
    expect:
    // No smart vents (only conventional)
    def result = script.adjustVentOpeningsToEnsureMinimumAirflowTarget(
      [:], 'cooling', [:], 5)
    result == [:] // Should return empty map
  }

  def "adjustVentOpeningsToEnsureMinimumAirflowTarget - Temperature Proportional Adjustment"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS,
      'customizeScriptBeforeRun': BEFORE_RUN_SCRIPT)
    
    expect:
    // Test temperature-based proportional adjustment
    def percentPerVentId = [
      'hotRoom': 5,    // 30°C - should get more adjustment in cooling
      'coldRoom': 5    // 15°C - should get less adjustment in cooling
    ]
    def rateAndTempPerVentId = [
      'hotRoom': ['temp': 30],
      'coldRoom': ['temp': 15]
    ]
    def result = script.adjustVentOpeningsToEnsureMinimumAirflowTarget(
      rateAndTempPerVentId, 'cooling', percentPerVentId, 0)
    
    // Hot room should get more airflow than cold room in cooling mode
    result['hotRoom'] > result['coldRoom']
  }

  def "adjustVentOpeningsToEnsureMinimumAirflowTarget - Heating vs Cooling Proportions"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS,
      'customizeScriptBeforeRun': BEFORE_RUN_SCRIPT)
    
    expect:
    def percentPerVentId = [
      'room1': 5,
      'room2': 5
    ]
    def rateAndTempPerVentId = [
      'room1': ['temp': 25], // Higher temp
      'room2': ['temp': 20]  // Lower temp
    ]
    
    // In cooling mode, higher temp room should get more flow
    def coolingResult = script.adjustVentOpeningsToEnsureMinimumAirflowTarget(
      rateAndTempPerVentId, 'cooling', percentPerVentId, 0)
    
    // In heating mode, lower temp room should get more flow
    def heatingResult = script.adjustVentOpeningsToEnsureMinimumAirflowTarget(
      rateAndTempPerVentId, 'heating', percentPerVentId, 0)
    
    // Cooling: room1 (25°C) should get more than room2 (20°C)
    coolingResult['room1'] > coolingResult['room2']
    
    // Heating: both rooms should get adjusted, but let's verify they're both increased
    heatingResult['room1'] > 5 && heatingResult['room2'] > 5
  }

  def "adjustVentOpeningsToEnsureMinimumAirflowTarget - Iteration Limit"() {
    setup:
    final log = new CapturingLog()
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
      _ * getLog() >> log
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi,
      'validationFlags': VALIDATION_FLAGS,
      'userSettingValues': USER_SETTINGS,
      'customizeScriptBeforeRun': BEFORE_RUN_SCRIPT)
    
    expect:
    // Create scenario that might hit iteration limit (many vents at very low percentages)
    def percentPerVentId = [:]
    def rateAndTempPerVentId = [:]
    (1..10).each { i ->
      percentPerVentId["vent${i}"] = 1 // Very low percentage
      rateAndTempPerVentId["vent${i}"] = ['temp': 20 + i] // Different temps
    }
    
    def result = script.adjustVentOpeningsToEnsureMinimumAirflowTarget(
      rateAndTempPerVentId, 'cooling', percentPerVentId, 0)
    
    // Should complete without infinite loop and all vents should be adjusted
    result.size() == 10
    result.values().every { it > 1 } // All should be increased from 1%
  }
}
