package bot.flair

// Run `gradle build` to test
// More info @ https://github.com/biocomp/hubitat_ci/blob/master/docs/how_to_test.md

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.api.common_api.InstalledAppWrapper
import me.biocomp.hubitat_ci.api.common_api.Log
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class Test extends Specification
{
    def sandbox = new HubitatAppSandbox(new File("hubitat-flair-vents-app.groovy"))
    def validationFlags = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy
          ]    

    def testRoomStates = [
      "122127":["coolingRate":0.055, "lastStartTemp":24.388, "heatingRate":0.030, "roomName":"", "ventIds":["1222bc5e"]], 
      "122129":["coolingRate":0.035, "lastStartTemp":23.194, "heatingRate":0.026, "roomName":"", "ventIds":["00f65b12"]], 
      "122128":["coolingRate":0.064, "lastStartTemp":23.722, "heatingRate":0.079, "roomName":"", "ventIds":["d3f411b2"]], 
      "122133":["coolingRate":0.067, "lastStartTemp":22.446, "heatingRate":0.067, "roomName":"", "ventIds":["472379e6", "6ee4c352"]], 
      "129424":["coolingRate":0.079, "lastStartTemp":21.666, "heatingRate":0.064, "roomName":"", "ventIds":["c5e770b6"]], 
      "122132":["coolingRate":0.026, "lastStartTemp":22.777, "heatingRate":0.035, "roomName":"", "ventIds":["e522531c"]], 
      "122131":["coolingRate":0.030, "lastStartTemp":23.500, "heatingRate":0.055, "roomName":"", "ventIds":["acb0b95d"]]
    ]

    def "roundToNearestFifth()"() {
      setup:
        AppExecutor executorApi = Mock{
          _*getState() >> [:]
        }
        def script = sandbox.run(api: executorApi, validationFlags: validationFlags)
      expect:
        script.roundToNearestFifth(12.4) == 10
        script.roundToNearestFifth(12.5) == 15
        script.roundToNearestFifth(12.6) == 15
        script.roundToNearestFifth(95.6) == 95
        script.roundToNearestFifth(97.5) == 100
    }

    def "calculateVentOpenPercentange()"() {
      setup:
         AppExecutor executorApi = Mock{
          _*getState() >> [:]
        }
        def script = sandbox.run(api: executorApi, validationFlags: validationFlags)

      expect:
        script.calculateVentOpenPercentange(70, "heating", 0.698, 62, 12.6) == 90
        script.calculateVentOpenPercentange(70, "heating", 0.715, 65, 12.6) == 55
        script.calculateVentOpenPercentange(70, "heating", 0.550, 61, 20) == 80
        script.calculateVentOpenPercentange(82, "cooling", 0.850, 98, 20) == 100
        script.calculateVentOpenPercentange(82, "cooling", 0.950, 84, 20) == 10
        script.calculateVentOpenPercentange(82, "cooling", 0.950, 85, 20) == 15
        script.calculateVentOpenPercentange(82, "cooling", 2.5, 86, 90) == 5
        script.calculateVentOpenPercentange(82, "cooling", 2.5, 87, 900) == 5
    }

}