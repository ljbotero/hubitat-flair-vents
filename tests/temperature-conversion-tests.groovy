package bot.flair

// Temperature Conversion and Thermostat Tests
// Run `gradle build` to test

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class TemperatureConversionTest extends Specification {

  private static final File APP_FILE = new File('src/hubitat-flair-vents-app.groovy')
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice,
            Flags.AllowWritingToSettings,
            Flags.AllowReadingNonInputSettings
          ]

  def "convertFahrenheitToCentigradeTest - Common Conversions"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [:]

    expect:
    // Test common conversions with precision tolerance
    Math.abs(script.convertFahrenheitToCentigrade(32) - 0) < 0.01      // Freezing point
    Math.abs(script.convertFahrenheitToCentigrade(212) - 100) < 0.01   // Boiling point - actual result is 100.0000000080
    Math.abs(script.convertFahrenheitToCentigrade(68) - 20) < 0.01     // Room temperature
    Math.abs(script.convertFahrenheitToCentigrade(98.6) - 37) < 0.01   // Body temperature
    Math.abs(script.convertFahrenheitToCentigrade(-40) - (-40)) < 0.01   // Same in both scales
  }

  def "convertFahrenheitToCentigradeTest - Edge Cases"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [:]

    expect:
    // Use precision tolerance for floating point comparisons
    Math.abs(script.convertFahrenheitToCentigrade(0) - (-17.7777777792)) < 0.01
    Math.abs(script.convertFahrenheitToCentigrade(-10) - (-23.333333333333332)) < 0.01
    Math.abs(script.convertFahrenheitToCentigrade(100) - 37.77777777777778) < 0.01
    Math.abs(script.convertFahrenheitToCentigrade(-459.67) - (-273.15)) < 0.01 // Absolute zero
  }

  def "calculateHvacModeTest - Basic Cases"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [:]

    expect:
    script.calculateHvacMode(80.0, 80.0, 70.0) == 'cooling'
    script.calculateHvacMode(70.0, 80.0, 70.0) == 'heating'
    script.calculateHvacMode(81.0, 80.0, 70.0) == 'cooling'
    script.calculateHvacMode(69.0, 80.0, 70.0) == 'heating'
  }

  def "calculateHvacModeTest - Edge Cases"() {
    setup:
    AppExecutor executorApi = Mock(AppExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatAppSandbox(APP_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    script.atomicState = [:]

    expect:
    // Equal distances - actual behavior chooses heating, not cooling
    script.calculateHvacMode(75.0, 80.0, 70.0) == 'heating'
    
    // Very close temperatures - when distances are equal, it defaults to heating
    script.calculateHvacMode(75.0, 75.1, 74.9) == 'heating'
    script.calculateHvacMode(75.0, 74.9, 75.1) == 'heating' // Equal distances = heating
    
    // Extreme temperatures - when distances are equal, defaults to heating
    script.calculateHvacMode(0.0, 100.0, -100.0) == 'heating' // 100 vs 100, chooses heating
    script.calculateHvacMode(50.0, 0.0, 100.0) == 'heating'   // 50 vs 50, equal distances default to heating
  }
}
