# Changelog

All notable changes to the Hubitat Flair Vents integration will be documented in this file.

## [0.21] - 2025-06-24

### Major Performance and Stability Improvements

#### Fixed
- **Critical Pending Request Issue**: Fixed stuck pending request flags that caused "Cleared X stuck pending request flags" messages every 5 minutes
  - Added proper error handling in `handleRoomGetWithCache` and `handleDeviceGetWithCache`
  - Ensured pending flags are always cleared in finally blocks, even when requests fail
  - Eliminated race conditions that prevented proper cleanup

#### Added
- **Temperature Sensor Noise Filtering**: Implemented intelligent temperature change detection to reduce sensor noise impact
  - Added constants: `TEMP_SENSOR_ACCURACY = 0.5°C`, `MIN_DETECTABLE_TEMP_CHANGE = 0.1°C`, `MIN_RUNTIME_FOR_RATE_CALC = 5 minutes`
  - Temperature changes below 0.1°C are now filtered as sensor noise
  - Minimum efficiency assigned for vents ≥30% open with no meaningful temperature change
  - Requires minimum 5 minutes HVAC runtime before calculating efficiency rates

- **Thermostat Hysteresis Control**: Added hysteresis to prevent frequent vent cycling
  - Added `THERMOSTAT_HYSTERESIS = 0.6°C (~1°F)` constant
  - Modified `thermostat1ChangeTemp` to only trigger vent adjustments for temperature changes ≥0.6°C
  - Prevents unnecessary vent adjustments when thermostat oscillates between adjacent temperatures (e.g., 79-80°F)

- **Intelligent Polling System**: Implemented dynamic device polling based on HVAC state
  - **Active HVAC** (cooling/heating): 3 minutes polling for optimal responsiveness
  - **Idle HVAC** (idle/fan only/off): 10 minutes polling for 70% API load reduction
  - Parent-child communication system for polling control
  - Automatic switching based on thermostat operating state changes
  - Added `updateDevicePollingInterval()` method in parent app
  - Added `updateParentPollingInterval()` method in both device drivers

- **Instance-Based Caching Infrastructure**: Replaced problematic static field caching with instance-based approach
  - Prevents cross-instance cache contamination in multi-instance environments
  - Reduced cache duration from 60s to 30s for better responsiveness
  - Implemented LRU (Least Recently Used) cache with max size limits (50 entries per instance)
  - Added comprehensive cache initialization and cleanup methods
  - Enhanced cache expiration and eviction logic

#### Changed
- **Enhanced Error Recovery**: Improved error handling and recovery mechanisms throughout the system
  - Better handling of failed API requests with proper cleanup
  - Enhanced logging for debugging while reducing verbose output
  - Improved pending request management to prevent stuck states

- **Optimized Performance**: Multiple performance improvements for better system efficiency
  - Intelligent API call reduction during HVAC idle periods
  - Better cache management with automatic cleanup
  - Reduced unnecessary temperature change calculations
  - Optimized polling schedules based on actual system activity

#### Technical Details
- **New Constants**: `THERMOSTAT_HYSTERESIS`, `TEMP_SENSOR_ACCURACY`, `MIN_DETECTABLE_TEMP_CHANGE`, `MIN_RUNTIME_FOR_RATE_CALC`, `POLLING_INTERVAL_ACTIVE`, `POLLING_INTERVAL_IDLE`
- **New Methods**: `updateDevicePollingInterval()`, `initializeInstanceCaches()`, `clearInstanceCache()`, `getCurrentTime()`, `getInstanceId()`
- **Enhanced Methods**: `calculateRoomChangeRate()` with noise filtering, `thermostat1ChangeTemp()` with hysteresis, `thermostat1ChangeStateHandler()` with dynamic polling
- **Driver Updates**: Both vent and puck drivers now support parent-controlled polling via `updateParentPollingInterval()`
- **Cache Infrastructure**: Complete instance-based caching system with LRU eviction, automatic cleanup, and thread-safe operations

#### Performance Benefits
- **70% reduction** in API calls during HVAC idle periods
- **Eliminated** stuck pending request flag issues
- **Improved accuracy** through temperature sensor noise filtering
- **Reduced cycling** through thermostat hysteresis control
- **Better responsiveness** with optimized cache durations
- **Enhanced stability** through comprehensive error handling

## [0.2] - 2025-06-22

### Fixed
- **Multiple Vents per Room**: Fixed issue where only one vent per room was getting its cooling/heating efficiency rate updated. Now all vents in the same room receive identical efficiency rates, ensuring consistent behavior across rooms with multiple vents.
- **ConcurrentModificationException**: Fixed runtime error in `cleanupPendingRequests()` method that occurred when modifying collections during iteration. Now collects keys first before modifying the maps.
- **Room HVAC Ineffective Flag**: Removed the problematic `room-hvac-ineffective` flag that was causing issues with room efficiency calculations.

### Added
- **Automatic Authentication**: OAuth authentication now runs automatically in multiple scenarios:
  - When credentials are first entered
  - On app initialization if token is missing
  - Automatically re-authenticates when API returns 401/403 errors
  - Maintains hourly token refresh schedule
- **Version Number**: Added version number (0.2) to the main app file header for better version tracking
- **Enhanced Temperature Fallback**: Improved temperature reading logic with better fallback handling and warning messages when temperature sensors are not reporting

### Changed
- **Security Enhancement**: Client Secret OAuth field now displays as a password field (dots/asterisks) instead of plain text
- **Authentication UI**: Removed manual "Authenticate" button in favor of automatic authentication with status indicators:
  - Shows "✓ Authenticated successfully" when authenticated
  - Shows "Authenticating..." during authentication
  - Shows retry button only on authentication failures
- **Improved Logging**: Reduced verbose logging by commenting out repetitive cache status and pending request messages while maintaining important debug information

### Technical Details
- Modified `finalizeRoomStates()` to use a Map to track and apply consistent rates across all vents in the same room
- Added `autoAuthenticate()` and `autoReauthenticate()` methods for automatic token management
- Enhanced `initialize()` method to check and perform auto-authentication on startup
- Updated `isValidResponse()` to detect authentication failures and trigger re-authentication
- Changed collection iteration pattern in `cleanupPendingRequests()` to avoid concurrent modification

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
