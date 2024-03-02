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
      '1222bc5e':1.654,
      '00f65b12':3.835,
      'd3f411b2':100.0,
      '472379e6':0.212,
      '6ee4c352':0.212,
      'c5e770b6':0.0,
      'e522531c':0.0,
      'acb0b95d':0.248
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
    log.records[2] == new Tuple(Level.warn, "Room '3' is taking 74.6 minutes, " +
      'which is longer than the limit of 72 minutes')
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
    def expectedVals = [5.354, 30.238, 67.911, 0.278, 0.393, 0.156, 0.141, 4.276, 100.0]
    def retVals = [
      script.calculateVentOpenPercentange(65, 70, 'heating', 0.715, 12.6),
      script.calculateVentOpenPercentange(61, 70, 'heating', 0.550, 20),
      script.calculateVentOpenPercentange(98, 82, 'cooling', 0.850, 20),
      script.calculateVentOpenPercentange(84, 82, 'cooling', 0.950, 20),
      script.calculateVentOpenPercentange(85, 82, 'cooling', 0.950, 20),
      script.calculateVentOpenPercentange(86, 82, 'cooling', 2.5, 90),
      script.calculateVentOpenPercentange(87, 82, 'cooling', 2.5, 900),
      script.calculateVentOpenPercentange(87, 85, 'cooling', 0.384, 10),
      script.calculateVentOpenPercentange(87, 85, 'cooling', 0, 10)
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
    script.calculateVentOpenPercentange(75, 70, 'heating', 0.1, 1) == 0
    log.records[0] == new Tuple(Level.debug, 'Room is already warmer/cooler (75) than setpoint (70)')
    script.calculateVentOpenPercentange(75, 80, 'cooling', 0.1, 1) == 0
    log.records[1] == new Tuple(Level.debug, 'Room is already warmer/cooler (75) than setpoint (80)')
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
    script.adjustVentOpeningsToEnsureMinimumAirflowTarget([:], 0) == [:]
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

    expect:
    script.adjustVentOpeningsToEnsureMinimumAirflowTarget(percentPerVentId, 0) == percentPerVentId
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

    expect:
    script.adjustVentOpeningsToEnsureMinimumAirflowTarget(percentPerVentId, 0) ==
      ['122127': 30.301]
    log.records.size == 123
    log.records[0] == new Tuple(Level.debug, 'Combined Vent Flow Percentage (5) is lower than 30.0%')
    log.records[11] == new Tuple(Level.debug, 'Adjusting % open from 5.716260% to 5.802%')
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

    expect:
    def newPercentPerVentId = [
      '122127':18.412,
      '122129':9.206,
      '122128':18.412,
      '122133':46.029,
      '129424':100,
      '122132':9.206,
      '122131':9.206
    ]
    script.adjustVentOpeningsToEnsureMinimumAirflowTarget(percentPerVentId, 0) == newPercentPerVentId
    log.records.size == 248
    log.records[0] == new Tuple(Level.debug, 'Combined Vent Flow Percentage (22.8571428571) is lower than 30.0%')
    log.records[8] == new Tuple(Level.debug, 'Adjusting % open from 10.14975% to 10.302%')
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

    expect:
    def newPercentPerVentId = [
      '122127':0.015,
      '122129':18.815,
      '122128':0.015,
      '122133':18.815,
      '129424':75.245,
      '122132':0.015,
      '122131':18.815
    ]
    script.adjustVentOpeningsToEnsureMinimumAirflowTarget(percentPerVentId, 4) == newPercentPerVentId
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
      -1, new Tuple(Level.debug, 'Insuficient number of minutes required to calculate change rate (0 should be greather than 4)'),
      -1, new Tuple(Level.debug, 'Change rate (0.000) is lower than 0.001, therefore it is being excluded'),
      2,
      0.002,
      0.059,
      0.407,
      1.000
    ]
    def actualVals = [
      script.calculateRoomChangeRate(0, 0, 0, 4), log.records[0],
      script.calculateRoomChangeRate(0, 0, 10, 4), log.records[1],
      script.roundBigDecimal(script.calculateRoomChangeRate(20, 30, 5.0, 100)),
      script.roundBigDecimal(script.calculateRoomChangeRate(20, 20.1, 60.0, 100)),
      script.roundBigDecimal(script.calculateRoomChangeRate(20.768, 21, 5, 25)),
      script.roundBigDecimal(script.calculateRoomChangeRate(19, 21, 5.2, 70)),
      script.roundBigDecimal(script.calculateRoomChangeRate(19, 29, 10, 100))
    ]
    actualVals == expectedVals
  }

}
