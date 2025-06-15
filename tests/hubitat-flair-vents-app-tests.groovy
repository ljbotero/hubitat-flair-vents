package bot.flair

// Run `gradle build` to test
// More info @ https://github.com/biocomp/hubitat_ci/blob/master/docs/how_to_test.md

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class Test extends Specification {

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

  def "calculateHvacModeTest"() {
    setup:
    AppExecutor executorApi = Mock {
      _   * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.calculateHvacMode(80.0, 80.0, 70.0) == 'cooling'
    script.calculateHvacMode(70.0, 80.0, 70.0) == 'heating'

    script.calculateHvacMode(81.0, 80.0, 70.0) == 'cooling'
    script.calculateHvacMode(69.0, 80.0, 70.0) == 'heating'
  }


  def "hasRoomReachedSetpointTest"() {
    setup:
    AppExecutor executorApi = Mock {
      _   * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)

    expect:
    script.hasRoomReachedSetpoint('cooling', 80, 75) == true
    script.hasRoomReachedSetpoint('cooling', 80, 79) == true
    script.hasRoomReachedSetpoint('cooling', 80, 80) == true
    script.hasRoomReachedSetpoint('cooling', 80, 81) == false

    script.hasRoomReachedSetpoint('heating', 70, 65) == false
    script.hasRoomReachedSetpoint('heating', 70, 69) == false
    script.hasRoomReachedSetpoint('heating', 70, 70) == true
    script.hasRoomReachedSetpoint('heating', 70, 70.01) == true
  }

  def "roundToNearestFifthTest"() {
    setup:
    AppExecutor executorApi = Mock {
      _   * getState() >> [:]
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

  def "rollingAverageTest"() {
    setup:
    AppExecutor executorApi = Mock {
      _   * getState() >> [:]
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
    script.rollingAverage(10, 5, 1, 0) == 0
    script.rollingAverage(0, 15, 1, 2) == 15
    script.rollingAverage(null, 15, 1, 2) == 15
  }

  def "calculateOpenPercentageForAllVentsTest"() {
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

  def "calculateLongestMinutesToTargetTest"() {
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

  def "calculateVentOpenPercentangeTest"() {
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
      script.calculateVentOpenPercentange('', 65, 70, 'heating', 0.715, 12.6),
      script.calculateVentOpenPercentange('', 61, 70, 'heating', 0.550, 20),
      script.calculateVentOpenPercentange('', 98, 82, 'cooling', 0.850, 20),
      script.calculateVentOpenPercentange('', 84, 82, 'cooling', 0.950, 20),
      script.calculateVentOpenPercentange('', 85, 82, 'cooling', 0.950, 20),
      script.calculateVentOpenPercentange('', 86, 82, 'cooling', 2.5, 90),
      script.calculateVentOpenPercentange('', 87, 82, 'cooling', 2.5, 900),
      script.calculateVentOpenPercentange('', 87, 85, 'cooling', 0.384, 10),
      script.calculateVentOpenPercentange('', 87, 85, 'cooling', 0, 10)
    ]
    expectedVals == retVals
  }

  def "calculateVentOpenPercentangeTest - Already reached"() {
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
    script.calculateVentOpenPercentange('', 75, 70, 'heating', 0.1, 1) == 0
    log.records[0] == new Tuple(Level.debug, "'' is already warmer (75) than setpoint (70)")
    script.calculateVentOpenPercentange('', 75, 80, 'cooling', 0.1, 1) == 0
    log.records[1] == new Tuple(Level.debug, "'' is already cooler (75) than setpoint (80)")
  }

  def "adjustVentOpeningsToEnsureMinimumAirflowTarget() - empty state"() {
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

  def "adjustVentOpeningsToEnsureMinimumAirflowTargetTest - no percent open set"() {
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

  def "adjustVentOpeningsToEnsureMinimumAirflowTargetTest - single vent at 5%"() {
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

  def "adjustVentOpeningsToEnsureMinimumAirflowTargetTest - multiple vents"() {
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

  def "adjustVentOpeningsToEnsureMinimumAirflowTargetTest - multiple vents and conventional vents"() {
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

  def "calculateRoomChangeRateTest"() {
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
      -1, new Tuple(Level.debug, 'Insuficient number of minutes required to calculate change rate (0 should be greather than 1)'),
      -1, new Tuple(Level.debug, 'Zero/minimal temperature change detected: startTemp=0°C, currentTemp=0°C, diffTemps=0°C, vent was 4% open'),
      1.000, 0.056, -1.000, 1.429, 1.000
    ]
    def actualVals = [
      script.calculateRoomChangeRate(0, 0, 0, 4, 0.03), log.records[0],
      script.calculateRoomChangeRate(0, 0, 10, 4, 0.03), log.records[1],
      script.roundBigDecimal(script.calculateRoomChangeRate(20, 30, 5.0, 100, 0.03)),
      script.roundBigDecimal(script.calculateRoomChangeRate(20, 20.1, 60.0, 100, 0.03)),
      script.roundBigDecimal(script.calculateRoomChangeRate(20.768, 21, 5, 25, 0.03)),
      script.roundBigDecimal(script.calculateRoomChangeRate(19, 21, 5.2, 70, 0.03)),
      script.roundBigDecimal(script.calculateRoomChangeRate(19, 29, 10, 100, 0.03))
    ]
    actualVals == expectedVals
  }

}
