# Changelog

All notable changes to the Hubitat Flair Vents integration will be documented in this file.

## [0.17] - 2025-06-15

Based on community feedback from the Hubitat forum, this release addresses critical bugs and implements requested features.

### Fixed
- **DAB Disable Mode**: Fixed issue where vents remained stuck at their last position when Dynamic Airflow Balancing (DAB) was disabled. Vents now properly respond to manual control and rules regardless of DAB state.
- **Logging Configuration**: Fixed bug where setting debug level to NONE still flooded logs with debug entries. The logging system now properly respects the debug level setting.
- **Device Driver Command**: Changed `setRoomActive` command parameter from boolean to string to comply with Hubitat's supported parameter types.
- **Remote Sensor 404 Errors** (Issue #4): Fixed constant HTTP 404 errors when trying to fetch data from non-existent Flair Pucks. Added validation to check if remote sensors exist before making API calls and implemented graceful error handling.

### Added
- **Room Control Capability**: Added `setRoomActive` command to the device driver, allowing users to control Flair "rooms" active/away settings. This enables leveraging Flair's built-in logic while maintaining Hubitat automation control.
- **Improved OAuth Error Handling**: Added better error messages and guidance when OAuth authentication fails, including specific instructions for OAuth 1.0 vs 2.0 credentials.
- **Enhanced Device Discovery**: Improved device discovery with better error messages when no vents are found, typically indicating incorrect OAuth credentials.

### Changed
- **Structure Mode Management**: When DAB is disabled, the integration now keeps the structure in manual mode to allow Hubitat control without interfering with Flair's own logic.
- **Error Messages**: Enhanced authentication error messages to provide clearer guidance on credential requirements.

### Technical Details
- Fixed the logic for `log()` and `logDetails()` methods to properly check debug levels (using `>=` instead of `<=`)
- Modified `patchRoom()` to accept string values for the active parameter
- Enhanced `handleDeviceList()` to provide better feedback when device discovery fails

## [0.16] - 2024-07-14

### Added
- Initial release with Dynamic Airflow Balancing (DAB) algorithm
- OAuth 2.0 authentication support
- Device discovery and management
- Thermostat integration and HVAC cycle monitoring
- Temperature change rate learning
- Minimum airflow protection
- Pre-adjustment logic for HVAC efficiency

### Features
- Automatic vent position optimization based on real-time temperature data
- Support for both smart and conventional vents
- Room efficiency pattern learning over multiple HVAC cycles
- Configurable vent adjustment granularity
- Optional closing of vents in inactive rooms
