package bot.flair

// Sunlight condition classification tests
// Run `gradle build` to test

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import java.text.SimpleDateFormat

class SunlightConditionTest extends Specification {

  private static final File APP_FILE = new File('src/hubitat-flair-vents-app.groovy')
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice
          ]

  def "getSunlightCondition classification scenarios"() {
    setup:
    AppExecutor executorApi = Mock {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    def fmt = new SimpleDateFormat('yyyy-MM-dd HH:mm')
    def sunrise = fmt.parse('2024-05-01 06:00')
    def sunset = fmt.parse('2024-05-01 18:00')

    expect:
    script.getSunlightCondition(fmt.parse('2024-05-01 05:30'), sunrise, sunset, 'Clear') == 'night'
    script.getSunlightCondition(fmt.parse('2024-05-01 06:05'), sunrise, sunset, 'Clear') == 'sunrise'
    script.getSunlightCondition(fmt.parse('2024-05-01 12:00'), sunrise, sunset, 'Clear') == 'day'
    script.getSunlightCondition(fmt.parse('2024-05-01 12:00'), sunrise, sunset, 'Cloudy') == 'overcast'
    script.getSunlightCondition(fmt.parse('2024-05-01 17:55'), sunrise, sunset, 'Clear') == 'sunset'
    script.getSunlightCondition(fmt.parse('2024-05-01 22:00'), sunrise, sunset, 'Clear') == 'night'
  }
}
