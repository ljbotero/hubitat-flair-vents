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
    when:
    // Just validate the file exists and can be read
    def driverFile = DRIVER_FILE
    def driverText = driverFile.text
    
    then:
    // Should load without throwing exceptions
    driverFile.exists()
    driverText != null
    driverText.length() > 0
    driverText.contains('metadata')
    driverText.contains('definition')
  }

  def "Device Driver - Has Required Methods"() {
    setup:
    def driverText = DRIVER_FILE.text
    
    expect:
    // Check that required methods exist in the source code
    driverText.contains('def installed()')
    driverText.contains('def updated()')
    driverText.contains('def refresh()')
    driverText.contains('void setLevel(level')
    driverText.contains('def setRoomActive(isActive)')
  }

  def "Device Driver - State Management"() {
    setup:
    def driverText = DRIVER_FILE.text
    
    expect:
    // Check that state management methods exist
    driverText.contains('def setDeviceState(String attr, value)')
    driverText.contains('def getDeviceState(String attr)')
    driverText.contains('state[attr] = value')
  }
}
