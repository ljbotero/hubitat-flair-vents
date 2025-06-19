# Changelog

All notable changes to the Hubitat Flair Vents integration will be documented in this file.

## [0.19] - 2025-06-18

### Added
- **API Request Throttling**: Implemented concurrent request limiting (max 3 requests) to prevent overwhelming the Flair API
  - Added request counting and queueing system with automatic retry mechanism
  - Requests exceeding the limit are delayed by 300ms and retried
- **Response Caching**: Added intelligent caching to reduce redundant API calls
  - Room data cached for 60 seconds
  - Device readings (vents and pucks) cached for 30 seconds
  - Duplicate request prevention while API calls are in progress
- **Periodic Cache Cleanup**: Scheduled tasks to clear expired cache entries
  - Room cache cleared every 10 minutes
  - Device cache cleared every 5 minutes
  - Pending request flags cleaned up every 5 minutes

### Changed
- Modified `getDeviceData()` to use new caching functions: `getRoomDataWithCache()`, `getDeviceDataWithCache()`, and `getDeviceReadingWithCache()`
- Updated `getDataAsync()` and `patchDataAsync()` to respect concurrent request limits
- Enhanced retry logic to handle cached data and prevent duplicate room requests

### Fixed
- Eliminated redundant API calls for room data and device readings through intelligent caching
- Improved API stability by preventing request flooding during device refreshes
- Better handling of device objects in retry scenarios to avoid serialization issues

### Technical Details
- Added constants: `API_CALL_DELAY_MS`, `MAX_CONCURRENT_REQUESTS`, `ROOM_CACHE_DURATION_MS`, `DEVICE_CACHE_DURATION_MS`
- New methods: `canMakeRequest()`, `incrementActiveRequests()`, `decrementActiveRequests()`, `clearRoomCache()`, `clearDeviceCache()`, `cleanupPendingRequests()`
- Cache storage: `roomDataCache`, `deviceReadingCache` with corresponding timestamp maps
- Comprehensive test coverage in new test files: `request-throttling-comprehensive-tests.groovy` and `manual-cache-test.groovy`

## [0.18] - 2025-06-15

### Added
- **Full Flair Pucks Support**: Complete implementation of Flair Pucks as temperature/humidity sensors
  - Created new device driver `hubitat-flair-vents-pucks-driver.groovy` with capabilities:
    - Temperature measurement (with °C to °F conversion)
    - Humidity measurement
    - Motion detection
    - Battery monitoring (calculated from voltage)
    - Comprehensive room attributes (temperature, humidity, occupancy, setpoints, etc.)
    - RSSI signal strength and firmware version tracking
  - OAuth scope expanded to include `pucks.view+pucks.edit` permissions
  - Multiple discovery methods: structure endpoint, all pucks endpoint, rooms with pucks
  - Pucks can be selected as temperature sources for individual vents in the DAB algorithm
  - Room control commands (setRoomActive) available for pucks
  - Pucks are excluded from efficiency calculations and the device efficiency table

### Changed
- Modified `handleDeviceList()` to discover and count both vents and pucks
- Updated `makeRealDevice()` to create appropriate device type based on discovery
- Updated `getDeviceData()` to handle pucks with dedicated handlers:
  - `handlePuckGet()` for puck attributes
  - `handlePuckReadingGet()` for sensor readings
- Modified `listDiscoveredDevices()` to exclude pucks from the efficiency table
- Enhanced discovery with `handleAllPucks()` and `handleRoomsWithPucks()` methods
- Improved error handling for puck API calls and battery calculations

### Fixed
- Fixed Groovy syntax errors for Hubitat compatibility (removed unsupported `?.[]` syntax)
- Added proper null checks and error handling for battery voltage calculations
- Resolved 403 errors by implementing proper OAuth scope and puck-specific handlers
- Fixed device creation issues with better validation and error handling

### Documentation
- Added comprehensive `architecture.md` file documenting:
  - Project overview and file structure
  - High-level system architecture
  - Low-level design details
  - Dynamic Airflow Balancing algorithm explanation
  - API integration details
  - Testing framework and coverage information

## [0.17] - 2025-06-15

Based on community feedback from the Hubitat forum, this release addresses critical bugs and implements requested features.

### Fixed
- **DAB Disable Mode**: Fixed issue where vents remained stuck at their last position when Dynamic Airflow Balancing (DAB) was disabled. Vents now properly respond to manual control and rules regardless of DAB state.
- **Logging Configuration**: Fixed bug where setting debug level to NONE still flooded logs with debug entries. The logging system now properly respects the debug level setting.
- **Device Driver Command**: Changed `setRoomActive` command parameter from boolean to string to comply with Hubitat's supported parameter types. Added validation to only accept "true" or "false" values.
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
