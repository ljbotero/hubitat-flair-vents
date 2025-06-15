package bot.flair

// Device Driver Tests
// Tests for Flair Vent device driver functionality

import me.biocomp.hubitat_ci.util.CapturingLog.Level
import me.biocomp.hubitat_ci.util.CapturingLog
import me.biocomp.hubitat_ci.api.device_api.DeviceExecutor
import me.biocomp.hubitat_ci.device.HubitatDeviceSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

class DeviceDriverTest extends Specification {

  private static final File DRIVER_FILE = new File('src/hubitat-flair-vents-driver.groovy')
  private static final List VALIDATION_FLAGS = [
            Flags.DontValidateMetadata,
            Flags.DontValidatePreferences,
            Flags.DontValidateDefinition,
            Flags.DontRestrictGroovy,
            Flags.DontRequireParseMethodInDevice,
            Flags.AllowWritingToSettings,
            Flags.AllowReadingNonInputSettings
          ]

  def "Device Driver - Load Successfully"() {
    setup:
    DeviceExecutor executorApi = Mock(DeviceExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatDeviceSandbox(DRIVER_FILE)
    
    when:
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    
    then:
    // Should load without throwing exceptions
    script != null
    noExceptionThrown()
  }

  def "Device Driver - Has Required Methods"() {
    setup:
    DeviceExecutor executorApi = Mock(DeviceExecutor)
    def sandbox = new HubitatDeviceSandbox(DRIVER_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    
    expect:
    // Check that required methods exist
    script.metaClass.respondsTo(script, 'installed')
    script.metaClass.respondsTo(script, 'updated')
    script.metaClass.respondsTo(script, 'refresh')
    script.metaClass.respondsTo(script, 'setLevel', Number)
    script.metaClass.respondsTo(script, 'setRoomActive', Boolean)
  }

  def "Device Driver - State Management"() {
    setup:
    DeviceExecutor executorApi = Mock(DeviceExecutor) {
      _ * getState() >> [:]
    }
    def sandbox = new HubitatDeviceSandbox(DRIVER_FILE)
    def script = sandbox.run('api': executorApi, 'validationFlags': VALIDATION_FLAGS)
    
    when:
    script.setDeviceState('test-attr', 'test-value')
    def result = script.getDeviceState('test-attr')
    
    then:
    result == 'test-value'
  }
}
