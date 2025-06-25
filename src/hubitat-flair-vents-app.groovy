/**
 *  Hubitat Flair Vents Integration
 *  Version 0.21
 *
 *  Copyright 2024 Jaime Botero. All Rights Reserved
 *
 *  Licensed under the Apache License, Version 2.0 (the 'License');
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an 'AS IS' BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

import groovy.transform.Field
import groovy.json.JsonOutput

// ------------------------------
// Constants and Configuration
// ------------------------------

// Base URL for Flair API endpoints.
@Field static final String BASE_URL = 'https://api.flair.co'

// Instance-based cache durations (reduced from 60s to 30s for better responsiveness)
@Field static final Long ROOM_CACHE_DURATION_MS = 30000 // 30 second cache duration
@Field static final Long DEVICE_CACHE_DURATION_MS = 30000 // 30 second cache duration for device readings
@Field static final Integer MAX_CACHE_SIZE = 50 // Maximum cache entries per instance

// Content-Type header for API requests.
@Field static final String CONTENT_TYPE = 'application/json'

// HVAC mode constants.
@Field static final String COOLING = 'cooling'
@Field static final String HEATING = 'heating'

// Pending HVAC mode values returned by the thermostat.
@Field static final String PENDING_COOL = 'pending cool'
@Field static final String PENDING_HEAT = 'pending heat'

// Delay (in milliseconds) before re-reading temperature after an HVAC event.
@Field static final Integer TEMP_READINGS_DELAY_MS = 30000  // 30 seconds

// Minimum and maximum vent open percentages (in %).
@Field static final BigDecimal MIN_PERCENTAGE_OPEN = 0.0
@Field static final BigDecimal MAX_PERCENTAGE_OPEN = 100.0

// Threshold (in °C) used to trigger a pre-adjustment of vent settings before the setpoint is reached.
@Field static final BigDecimal VENT_PRE_ADJUST_THRESHOLD = 0.2

// HVAC timing constants.
@Field static final BigDecimal MAX_MINUTES_TO_SETPOINT = 60       // Maximum minutes to reach setpoint.
@Field static final BigDecimal MIN_MINUTES_TO_SETPOINT = 1        // Minimum minutes required to compute temperature change rate.

// Temperature offset (in °C) applied to thermostat setpoints.
@Field static final BigDecimal SETPOINT_OFFSET = 0.7

// Acceptable temperature change rate limits (in °C per minute).
@Field static final BigDecimal MAX_TEMP_CHANGE_RATE = 1.5
@Field static final BigDecimal MIN_TEMP_CHANGE_RATE = 0.001

// Temperature sensor accuracy and noise filtering
@Field static final BigDecimal TEMP_SENSOR_ACCURACY = 0.5  // ±0.5°C typical sensor accuracy
@Field static final BigDecimal MIN_DETECTABLE_TEMP_CHANGE = 0.1  // Minimum change to consider real
@Field static final Integer MIN_RUNTIME_FOR_RATE_CALC = 5  // Minimum minutes before calculating rate

// Minimum combined vent airflow percentage across all vents (to ensure proper HVAC operation).
@Field static final BigDecimal MIN_COMBINED_VENT_FLOW = 30.0

// INCREMENT_PERCENTAGE is used as a base multiplier when incrementally increasing vent open percentages
// during airflow adjustments. For example, if the computed proportion for a vent is 0.5,
// then the vent’s open percentage will be increased by 1.5 * 0.5 = 0.75% in that iteration.
// This increment is applied repeatedly until the total combined airflow meets the minimum target.
@Field static final BigDecimal INCREMENT_PERCENTAGE = 1.5

// Maximum number of standard (non-Flair) vents allowed.
@Field static final Integer MAX_STANDARD_VENTS = 15

// Maximum iterations for the while-loop when adjusting vent openings.
@Field static final Integer MAX_ITERATIONS = 500

// HTTP timeout for API requests (in seconds).
@Field static final Integer HTTP_TIMEOUT_SECS = 5

// Default opening percentage for standard (non-Flair) vents (in %).
@Field static final Integer STANDARD_VENT_DEFAULT_OPEN = 50

// Temperature tolerance for rebalancing vent operations (in °C).
@Field static final BigDecimal REBALANCING_TOLERANCE = 0.5

// Temperature boundary adjustment for airflow calculations (in °C).
@Field static final BigDecimal TEMP_BOUNDARY_ADJUSTMENT = 0.1

// Thermostat hysteresis to prevent cycling (in °C).
@Field static final BigDecimal THERMOSTAT_HYSTERESIS = 0.6  // ~1°F

// Polling intervals based on HVAC state (in minutes).
@Field static final Integer POLLING_INTERVAL_ACTIVE = 3     // When HVAC is running
@Field static final Integer POLLING_INTERVAL_IDLE = 10      // When HVAC is idle

// Delay before initializing room states after certain events (in milliseconds).
@Field static final Integer INITIALIZATION_DELAY_MS = 3000

// Delay after a thermostat state change before reinitializing (in milliseconds).
@Field static final Integer POST_STATE_CHANGE_DELAY_MS = 1000

// Simple API throttling delay to prevent overwhelming the Flair API (in milliseconds).
@Field static final Integer API_CALL_DELAY_MS = 300

// Maximum concurrent HTTP requests to prevent API overload.
@Field static final Integer MAX_CONCURRENT_REQUESTS = 3

// ------------------------------
// End Constants
// ------------------------------

definition(
    name: 'Flair Vents',
    namespace: 'bot.flair',
    author: 'Jaime Botero',
    description: 'Provides discovery and control capabilities for Flair Vent devices',
    category: 'Discovery',
    oauth: false,
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: '',
    singleInstance: false
)

preferences {
  page(name: 'mainPage')
}

def mainPage() {
  dynamicPage(name: 'mainPage', title: 'Setup', install: true, uninstall: true) {
    section('OAuth Setup') {
      input name: 'clientId', type: 'text', title: 'Client Id (OAuth 2.0)', required: true, submitOnChange: true
      input name: 'clientSecret', type: 'password', title: 'Client Secret OAuth 2.0', required: true, submitOnChange: true
      paragraph '<small><b>Obtain your client Id and secret from ' +
                "<a href='https://forms.gle/VohiQjWNv9CAP2ASA' target='_blank'>here</a></b></small>"
      if (settings?.clientId && settings?.clientSecret) {
        // Auto-authenticate when credentials are provided
        if (!state.flairAccessToken || state.authError) {
          runIn(1, 'autoAuthenticate')
        }
        if (state.flairAccessToken && !state.authError) {
          paragraph "<span style='color: green;'>✓ Authenticated successfully</span>"
        } else {
          paragraph "<span style='color: orange;'>Authenticating...</span>"
        }
      }
      if (state.authError) {
        section {
          paragraph "<span style='color: red;'>${state.authError}</span>"
          input name: 'retryAuth', type: 'button', title: 'Retry Authentication', submitOnChange: true
        }
      }
    }

    if (state.flairAccessToken) {
      section('Device Discovery') {
        input name: 'discoverDevices', type: 'button', title: 'Discover', submitOnChange: true
        input name: 'structureId', type: 'text', title: 'Home Id (SID)', required: false, submitOnChange: true
      }
      listDiscoveredDevices()

      section('<h2>Dynamic Airflow Balancing</h2>') {
        input name: 'dabEnabled', type: 'bool', title: 'Use Dynamic Airflow Balancing', defaultValue: false, submitOnChange: true
        if (dabEnabled) {
          input name: 'thermostat1', type: 'capability.thermostat', title: 'Choose Thermostat for Vents', multiple: false, required: true
          input name: 'thermostat1TempUnit', type: 'enum', title: 'Units used by Thermostat', defaultValue: 2,
                options: [1: 'Celsius (°C)', 2: 'Fahrenheit (°F)']
          input name: 'thermostat1AdditionalStandardVents', type: 'number', title: 'Count of conventional Vents', defaultValue: 0, submitOnChange: true
          paragraph '<small>Enter the total number of standard (non-Flair) adjustable vents in the home associated ' +
                    'with the chosen thermostat, excluding Flair vents. This ensures the combined airflow does not drop ' +
                    'below a specified percent to prevent HVAC issues.</small>'
          input name: 'thermostat1CloseInactiveRooms', type: 'bool', title: 'Close vents on inactive rooms', defaultValue: true, submitOnChange: true

          if (settings.thermostat1AdditionalStandardVents < 0) {
            app.updateSetting('thermostat1AdditionalStandardVents', 0)
          } else if (settings.thermostat1AdditionalStandardVents > MAX_STANDARD_VENTS) {
            app.updateSetting('thermostat1AdditionalStandardVents', MAX_STANDARD_VENTS)
          }

          if (!getThermostat1Mode() || getThermostat1Mode() == 'auto') {
            patchStructureData([mode: 'manual'])
            atomicState?.putAt('thermostat1Mode', 'manual')
          }
        }
        // Only show vents in DAB section, not pucks
        def vents = getChildDevices().findAll { it.hasAttribute('percent-open') }
        for (child in vents) {
          input name: "thermostat${child.getId()}", type: 'capability.temperatureMeasurement', title: "Choose Thermostat for ${child.getLabel()} (Optional)", multiple: false, required: false
        }
      }

      section('Vent Options') {
        input name: 'ventGranularity', type: 'enum', title: 'Vent Adjustment Granularity (in %)',
              options: ['5':'5%', '10':'10%', '25':'25%', '50':'50%', '100':'100%'],
              defaultValue: '5', required: true, submitOnChange: true
        paragraph '<small>Select how granular the vent adjustments should be. For example, if you choose 50%, vents ' +
                  'will only adjust to 0%, 50%, or 100%. Lower percentages allow for finer control, but may ' +
                  'result in more frequent adjustments (which could affect battery-powered vents).</small>'
      }
    } else {
      section {
        paragraph 'Device discovery button is hidden until authorization is completed.'
      }
    }
    section('Debug Options') {
      input name: 'debugLevel', type: 'enum', title: 'Choose debug level', defaultValue: 0,
            options: [0: 'None', 1: 'Level 1 (All)', 2: 'Level 2', 3: 'Level 3'], submitOnChange: true
    }
  }
}

// ------------------------------
// List and Device Discovery Functions
// ------------------------------
def listDiscoveredDevices() {
  final String acBoosterLink = 'https://amzn.to/3QwVGbs'
  def children = getChildDevices()
  // Filter only vents by checking for percent-open attribute which pucks don't have
  def vents = children.findAll { it.hasAttribute('percent-open') }
  BigDecimal maxCoolEfficiency = 0
  BigDecimal maxHeatEfficiency = 0

  vents.each { vent ->
    def coolRate = vent.currentValue('room-cooling-rate') ?: 0
    def heatRate = vent.currentValue('room-heating-rate') ?: 0
    maxCoolEfficiency = maxCoolEfficiency.max(coolRate)
    maxHeatEfficiency = maxHeatEfficiency.max(heatRate)
  }

  def builder = new StringBuilder()
  builder << '''
  <style>
    .device-table { width: 100%; border-collapse: collapse; font-family: Arial, sans-serif; color: black; }
    .device-table th, .device-table td { padding: 8px; text-align: left; border-bottom: 1px solid #ddd; }
    .device-table th { background-color: #f2f2f2; color: #333; }
    .device-table tr:hover { background-color: #f5f5f5; }
    .device-table a { color: #333; text-decoration: none; }
    .device-table a:hover { color: #666; }
    .device-table th:not(:first-child), .device-table td:not(:first-child) { text-align: center; }
    .warning-message { color: darkorange; cursor: pointer; }
    .danger-message { color: red; cursor: pointer; }
  </style>
  <table class="device-table">
    <thead>
      <tr>
        <th>Device</th>
        <th>Cooling Efficiency</th>
        <th>Heating Efficiency</th>
      </tr>
    </thead>
    <tbody>
  '''

  vents.each { vent ->
    def coolRate = vent.currentValue('room-cooling-rate') ?: 0
    def heatRate = vent.currentValue('room-heating-rate') ?: 0
    def coolEfficiency = maxCoolEfficiency > 0 ? roundBigDecimal((coolRate / maxCoolEfficiency) * 100, 0) : 0
    def heatEfficiency = maxHeatEfficiency > 0 ? roundBigDecimal((heatRate / maxHeatEfficiency) * 100, 0) : 0
    def warnMsg = 'This vent is very inefficient, consider installing an HVAC booster. Click for a recommendation.'

    def coolClass = coolEfficiency <= 25 ? 'danger-message' : (coolEfficiency <= 45 ? 'warning-message' : '')
    def heatClass = heatEfficiency <= 25 ? 'danger-message' : (heatEfficiency <= 45 ? 'warning-message' : '')

    def coolHtml = coolEfficiency <= 45 ? "<span class='${coolClass}' onclick=\"window.open('${acBoosterLink}');\" title='${warnMsg}'>${coolEfficiency}%</span>" : "${coolEfficiency}%"
    def heatHtml = heatEfficiency <= 45 ? "<span class='${heatClass}' onclick=\"window.open('${acBoosterLink}');\" title='${warnMsg}'>${heatEfficiency}%</span>" : "${heatEfficiency}%"

    builder << "<tr><td><a href='/device/edit/${vent.getId()}'>${vent.getLabel()}</a></td><td>${coolHtml}</td><td>${heatHtml}</td></tr>"
  }
  builder << '</tbody></table>'

  section {
    paragraph 'Discovered devices:'
    paragraph builder.toString()
  }
}

def getStructureId() {
  if (!settings?.structureId) { getStructureData() }
  return settings?.structureId
}

def updated() {
  log.debug 'Hubitat Flair App updating'
  initialize()
}

def installed() {
  log.debug 'Hubitat Flair App installed'
  initialize()
}

def uninstalled() {
  log.debug 'Hubitat Flair App uninstalling'
  removeChildren()
  unschedule()
  unsubscribe()
}

def initialize() {
  unsubscribe()
  
  // Initialize instance-based caches
  initializeInstanceCaches()
  
  // Check if we need to auto-authenticate on startup
  if (settings?.clientId && settings?.clientSecret) {
    if (!state.flairAccessToken) {
      log 'No access token found on initialization, auto-authenticating...', 2
      autoAuthenticate()
    } else {
      // Token exists, ensure hourly refresh is scheduled
      unschedule(login)
      runEvery1Hour(login)
    }
  }
  
  if (settings.thermostat1) {
    subscribe(settings.thermostat1, 'thermostatOperatingState', thermostat1ChangeStateHandler)
    subscribe(settings.thermostat1, 'temperature', thermostat1ChangeTemp)
    def temp = thermostat1.currentValue('temperature') ?: 0
    def coolingSetpoint = thermostat1.currentValue('coolingSetpoint') ?: 0
    def heatingSetpoint = thermostat1.currentValue('heatingSetpoint') ?: 0
    String hvacMode = calculateHvacMode(temp, coolingSetpoint, heatingSetpoint)
    runInMillis(INITIALIZATION_DELAY_MS, 'initializeRoomStates', [data: hvacMode])
    
    // Set initial polling based on current thermostat state
    def currentThermostatState = settings.thermostat1?.currentValue('thermostatOperatingState')
    def initialInterval = (currentThermostatState in ['cooling', 'heating']) ? 
        POLLING_INTERVAL_ACTIVE : POLLING_INTERVAL_IDLE
    
    log "Setting initial polling interval to ${initialInterval} minutes based on thermostat state: ${currentThermostatState}", 3
    updateDevicePollingInterval(initialInterval)
  }
  // Schedule periodic cleanup of instance caches and pending requests
  runEvery5Minutes('cleanupPendingRequests')
  runEvery10Minutes('clearRoomCache')
  runEvery5Minutes('clearDeviceCache')
}

// ------------------------------
// Helper Functions
// ------------------------------

private openAllVents(Map ventIdsByRoomId, int percentOpen) {
  ventIdsByRoomId.each { roomId, ventIds ->
    ventIds.each { ventId ->
      def vent = getChildDevice(ventId)
      if (vent) { patchVent(vent, percentOpen) }
    }
  }
}

private BigDecimal getRoomTemp(def vent) {
  def ventId = vent.getId()
  def roomName = vent.currentValue('room-name') ?: 'Unknown'
  def tempDevice = settings."thermostat${ventId}"
  
  if (tempDevice) {
    def temp = tempDevice.currentValue('temperature')
    if (temp == null) {
      log "WARNING: Temperature device ${tempDevice.getLabel()} for room '${roomName}' is not reporting temperature!", 2
      // Fall back to room temperature
      def roomTemp = vent.currentValue('room-current-temperature-c') ?: 0
      log "Falling back to room temperature for '${roomName}': ${roomTemp}°C", 2
      return roomTemp
    }
    if (settings.thermostat1TempUnit == '2') {
      temp = convertFahrenheitToCentigrade(temp)
    }
    log "Got temp from ${tempDevice.getLabel()} for '${roomName}': ${temp}°C", 2
    return temp
  }
  
  def roomTemp = vent.currentValue('room-current-temperature-c')
  if (roomTemp == null) {
    log "ERROR: No temperature available for room '${roomName}' - neither from Puck nor from room API!", 2
    return 0
  }
  log "Using room temperature for '${roomName}': ${roomTemp}°C", 2
  return roomTemp
}

private atomicStateUpdate(String stateKey, String key, value) {
  atomicState.updateMapValue(stateKey, key, value)
  log "atomicStateUpdate(${stateKey}, ${key}, ${value})", 1
}

def getThermostatSetpoint(String hvacMode) {
  BigDecimal setpoint = hvacMode == COOLING ?
      ((thermostat1.currentValue('coolingSetpoint') ?: 0) - SETPOINT_OFFSET) :
      ((thermostat1.currentValue('heatingSetpoint') ?: 0) + SETPOINT_OFFSET)
  setpoint = setpoint ?: thermostat1.currentValue('thermostatSetpoint')
  if (!setpoint) {
    logError 'Thermostat has no setpoint property, please choose a valid thermostat'
    return setpoint
  }
  if (settings.thermostat1TempUnit == '2') {
    setpoint = convertFahrenheitToCentigrade(setpoint)
  }
  return setpoint
}

def roundBigDecimal(BigDecimal number, int scale = 3) {
  number.setScale(scale, BigDecimal.ROUND_HALF_UP)
}

// Modified rounding function that uses the user-configured granularity.
// It has been renamed to roundToNearestMultiple since it rounds a value to the nearest multiple of a given granularity.
int roundToNearestMultiple(BigDecimal num) {
  int granularity = settings.ventGranularity ? settings.ventGranularity.toInteger() : 5
  return (int)(Math.round(num / granularity) * granularity)
}


def convertFahrenheitToCentigrade(BigDecimal tempValue) {
  (tempValue - 32) * (5 / 9)
}

def rollingAverage(BigDecimal currentAverage, BigDecimal newNumber, BigDecimal weight = 1, int numEntries = 10) {
  if (numEntries <= 0) { return 0 }
  BigDecimal base = (currentAverage ?: 0) == 0 ? newNumber : currentAverage
  BigDecimal sum = base * (numEntries - 1)
  def weightedValue = (newNumber - base) * weight
  def numberToAdd = base + weightedValue
  sum += numberToAdd
  return sum / numEntries
}

def hasRoomReachedSetpoint(String hvacMode, BigDecimal setpoint, BigDecimal currentTemp, BigDecimal offset = 0) {
  (hvacMode == COOLING && currentTemp <= setpoint - offset) ||
  (hvacMode == HEATING && currentTemp >= setpoint + offset)
}

def calculateHvacMode(BigDecimal temp, BigDecimal coolingSetpoint, BigDecimal heatingSetpoint) {
  Math.abs(temp - coolingSetpoint) < Math.abs(temp - heatingSetpoint) ? COOLING : HEATING
}

void removeChildren() {
  def children = getChildDevices()
  log "Deleting all child devices: ${children}", 2
  children.each { if (it) deleteChildDevice(it.getDeviceNetworkId()) }
}

// Only log messages if their level is greater than or equal to the debug level setting.
private log(String msg, int level = 3) {
  def settingsLevel = (settings?.debugLevel as Integer) ?: 0
  if (settingsLevel == 0) { return }
  if (level >= settingsLevel) {
    log.debug msg
  }
}

// Safe getter for thermostat mode from atomic state
private getThermostat1Mode() {
  return atomicState?.thermostat1Mode
}

// Get appropriate state object (atomicState in production, state in tests)
private getStateObject() {
  try {
    // Try atomicState first (production)
    if (atomicState != null) {
      return atomicState
    }
  } catch (Exception e) {
    // Fall back to state for test compatibility
  }
  return state
}

// Safe sendEvent wrapper for test compatibility
private safeSendEvent(device, Map eventData) {
  try {
    sendEvent(device, eventData)
  } catch (Exception e) {
    // In test environment, sendEvent might not be available
    log "Warning: Could not send event ${eventData} to device ${device}: ${e.message}", 2
  }
}

// ------------------------------
// Instance-Based Caching Infrastructure
// ------------------------------

// Get current time - now() is always available in Hubitat
private getCurrentTime() {
  return now()
}

// Get unique instance identifier
private getInstanceId() {
  try {
    // Try to use app ID if available (production)
    def appId = app?.getId()?.toString()
    if (appId) {
      return appId
    }
  } catch (Exception e) {
    // Expected in test environment
  }
  
  // For test environment, use current time as unique identifier
  // This provides reasonable uniqueness for test instances
  return "test-${now()}"
}

// Initialize instance-level cache variables
private initializeInstanceCaches() {
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  
  if (!state."${cacheKey}_initialized") {
    state."${cacheKey}_roomCache" = [:]
    state."${cacheKey}_roomCacheTimestamps" = [:]
    state."${cacheKey}_deviceCache" = [:]
    state."${cacheKey}_deviceCacheTimestamps" = [:]
    state."${cacheKey}_pendingRoomRequests" = [:]
    state."${cacheKey}_pendingDeviceRequests" = [:]
    state."${cacheKey}_initialized" = true
    log "Initialized instance-based caches for instance ${instanceId}", 3
  }
}

// Room data caching methods
def cacheRoomData(String roomId, Map roomData) {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  
  def roomCache = state."${cacheKey}_roomCache"
  def roomCacheTimestamps = state."${cacheKey}_roomCacheTimestamps"
  
  // Implement LRU cache with max size
  if (roomCache.size() >= MAX_CACHE_SIZE) {
    // Remove least recently used entry (oldest access time)
    def lruKey = null
    def oldestAccessTime = Long.MAX_VALUE
    roomCacheTimestamps.each { key, timestamp ->
      if (timestamp < oldestAccessTime) {
        oldestAccessTime = timestamp
        lruKey = key
      }
    }
    if (lruKey) {
      roomCache.remove(lruKey)
      roomCacheTimestamps.remove(lruKey)
      log "Evicted LRU cache entry: ${lruKey}", 4
    }
  }
  
  roomCache[roomId] = roomData
  roomCacheTimestamps[roomId] = getCurrentTime()
}

def getCachedRoomData(String roomId) {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  
  def roomCache = state."${cacheKey}_roomCache"
  def roomCacheTimestamps = state."${cacheKey}_roomCacheTimestamps"
  
  def timestamp = roomCacheTimestamps[roomId]
  if (!timestamp) return null
  
  if (isCacheExpired(roomId)) {
    roomCache.remove(roomId)
    roomCacheTimestamps.remove(roomId)
    return null
  }
  
  // Update access time for LRU tracking when item is accessed
  roomCacheTimestamps[roomId] = getCurrentTime()
  
  return roomCache[roomId]
}

def getRoomCacheSize() {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  def roomCache = state."${cacheKey}_roomCache"
  return roomCache.size()
}

// Test helper method
def cacheRoomDataWithTimestamp(String roomId, Map roomData, Long timestamp) {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  
  def roomCache = state."${cacheKey}_roomCache"
  def roomCacheTimestamps = state."${cacheKey}_roomCacheTimestamps"
  
  roomCache[roomId] = roomData
  roomCacheTimestamps[roomId] = timestamp
}

def isCacheExpired(String roomId) {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  def roomCacheTimestamps = state."${cacheKey}_roomCacheTimestamps"
  
  def timestamp = roomCacheTimestamps[roomId]
  if (!timestamp) return true
  return (getCurrentTime() - timestamp) > ROOM_CACHE_DURATION_MS
}

// Pending request tracking
def markRequestPending(String requestId) {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  def pendingRequests = state."${cacheKey}_pendingRoomRequests"
  pendingRequests[requestId] = true
}

def isRequestPending(String requestId) {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  def pendingRequests = state."${cacheKey}_pendingRoomRequests"
  return pendingRequests[requestId] == true
}

def clearPendingRequest(String requestId) {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  def pendingRequests = state."${cacheKey}_pendingRoomRequests"
  pendingRequests[requestId] = false
}

// Device reading caching methods
def cacheDeviceReading(String deviceKey, Map deviceData) {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  
  def deviceCache = state."${cacheKey}_deviceCache"
  def deviceCacheTimestamps = state."${cacheKey}_deviceCacheTimestamps"
  
  // Implement LRU cache with max size
  if (deviceCache.size() >= MAX_CACHE_SIZE) {
    // Remove least recently used entry (oldest access time)
    def lruKey = null
    def oldestAccessTime = Long.MAX_VALUE
    deviceCacheTimestamps.each { key, timestamp ->
      if (timestamp < oldestAccessTime) {
        oldestAccessTime = timestamp
        lruKey = key
      }
    }
    if (lruKey) {
      deviceCache.remove(lruKey)
      deviceCacheTimestamps.remove(lruKey)
      log "Evicted LRU device cache entry: ${lruKey}", 4
    }
  }
  
  deviceCache[deviceKey] = deviceData
  deviceCacheTimestamps[deviceKey] = getCurrentTime()
}

def getCachedDeviceReading(String deviceKey) {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  
  def deviceCache = state."${cacheKey}_deviceCache"
  def deviceCacheTimestamps = state."${cacheKey}_deviceCacheTimestamps"
  
  def timestamp = deviceCacheTimestamps[deviceKey]
  if (!timestamp) return null
  
  if ((getCurrentTime() - timestamp) > DEVICE_CACHE_DURATION_MS) {
    deviceCache.remove(deviceKey)
    deviceCacheTimestamps.remove(deviceKey)
    return null
  }
  
  // Update access time for LRU tracking when item is accessed
  deviceCacheTimestamps[deviceKey] = getCurrentTime()
  
  return deviceCache[deviceKey]
}

// Device pending request tracking
def isDeviceRequestPending(String deviceKey) {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  def pendingRequests = state."${cacheKey}_pendingDeviceRequests"
  return pendingRequests[deviceKey] == true
}

def markDeviceRequestPending(String deviceKey) {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  def pendingRequests = state."${cacheKey}_pendingDeviceRequests"
  pendingRequests[deviceKey] = true
}

def clearDeviceRequestPending(String deviceKey) {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  def pendingRequests = state."${cacheKey}_pendingDeviceRequests"
  pendingRequests[deviceKey] = false
}

// Clear all instance caches
def clearInstanceCache() {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  
  def roomCache = state."${cacheKey}_roomCache"
  def roomCacheTimestamps = state."${cacheKey}_roomCacheTimestamps"
  def deviceCache = state."${cacheKey}_deviceCache"
  def deviceCacheTimestamps = state."${cacheKey}_deviceCacheTimestamps"
  def pendingRoomRequests = state."${cacheKey}_pendingRoomRequests"
  def pendingDeviceRequests = state."${cacheKey}_pendingDeviceRequests"
  
  roomCache.clear()
  roomCacheTimestamps.clear()
  deviceCache.clear()
  deviceCacheTimestamps.clear()
  pendingRoomRequests.clear()
  pendingDeviceRequests.clear()
  log "Cleared all instance caches", 3
}

// ------------------------------
// End Instance-Based Caching Infrastructure
// ------------------------------

// Initialize request tracking
private initRequestTracking() {
  def stateObj = getStateObject()
  if (stateObj.activeRequests == null) {
    stateObj.activeRequests = 0
  }
}

// Check if we can make a request (under concurrent limit)
def canMakeRequest() {
  initRequestTracking()
  def stateObj = getStateObject()
  return (stateObj.activeRequests ?: 0) < MAX_CONCURRENT_REQUESTS
}

// Increment active request counter
def incrementActiveRequests() {
  initRequestTracking()
  def stateObj = getStateObject()
  stateObj.activeRequests = (stateObj.activeRequests ?: 0) + 1
}

// Decrement active request counter
private decrementActiveRequests() {
  initRequestTracking()
  def stateObj = getStateObject()
  stateObj.activeRequests = Math.max(0, (stateObj.activeRequests ?: 0) - 1)
}

// Retry methods for throttled requests
def retryGetDataAsync(data) {
  if (!data || !data.uri) {
    logError "retryGetDataAsync called with invalid data: ${data}"
    return
  }
  
  // Check if this is a room data request that should go through cache
  if (data.uri.contains('/room') && data.callback == 'handleRoomGetWithCache' && data.data?.deviceId) {
    // When retry data is passed through runInMillis, device objects become serialized
    // So we need to look up the device by ID instead
    def deviceId = data.data.deviceId
    def device = getChildDevice(deviceId)
    
    if (!device) {
      logError "retryGetDataAsync: Could not find device with ID ${deviceId}"
      return
    }
    
    def isPuck = !device.hasAttribute('percent-open')
    def roomId = device.currentValue('room-id')
    
    if (roomId) {
      // Check cache first using instance-based cache
      def cachedData = getCachedRoomData(roomId)
      if (cachedData) {
        log "Using cached room data for room ${roomId} on retry", 3
        processRoomTraits(device, cachedData)
        return
      }
      
      // Check if request is already pending
      if (isRequestPending(roomId)) {
        // log "Room data request already pending for room ${roomId} on retry, skipping", 3
        return
      }
    }
    
    // Re-route through cache check
    getRoomDataWithCache(device, deviceId, isPuck)
  } else {
    // Normal retry for non-room requests
    getDataAsync(data.uri, data.callback, data.data)
  }
}

def retryPatchDataAsync(data) {
  if (!data || !data.uri) {
    logError "retryPatchDataAsync called with invalid data: ${data}"
    return
  }
  patchDataAsync(data.uri, data.callback, data.body, data.data)
}

// Wrapper for log.error that respects debugLevel setting
private logError(String msg) {
  def settingsLevel = (settings?.debugLevel as Integer) ?: 0
  if (settingsLevel > 0) {
    log.error msg
  }
}

// Wrapper for log.warn that respects debugLevel setting
private logWarn(String msg) {
  def settingsLevel = (settings?.debugLevel as Integer) ?: 0
  if (settingsLevel > 0) {
    log.warn msg
  }
}

private logDetails(String msg, details = null, int level = 3) {
  def settingsLevel = (settings?.debugLevel as Integer) ?: 0
  if (settingsLevel == 0) { return }
  if (level >= settingsLevel) {
    if (details) {
      log?.debug "${msg}\n${details}"
    } else {
      log?.debug msg
    }
  }
}

def isValidResponse(resp) {
  if (!resp) {
    log 'HTTP Null response', 1
    return false
  }
  try {
    if (resp.hasError()) {
      // Check for authentication failures
      if (resp.getStatus() == 401 || resp.getStatus() == 403) {
        log "Authentication error detected (${resp.getStatus()}), re-authenticating...", 2
        runIn(1, 'autoReauthenticate')
        return false
      }
      // Don't log 404s at error level - they might be expected
      if (resp.getStatus() == 404) {
        log "HTTP 404 response", 1
      } else {
        log "HTTP response error: ${resp.getStatus()}", 1
      }
      return false
    }
  } catch (err) {
    log "HTTP response validation error: ${err.message ?: err.toString()}", 1
    return false
  }
  return true
}

// Updated getDataAsync to accept a String callback name with simple throttling.
def getDataAsync(String uri, String callback, data = null) {
  if (canMakeRequest()) {
    incrementActiveRequests()
    def headers = [ Authorization: "Bearer ${state.flairAccessToken}" ]
    def httpParams = [ uri: uri, headers: headers, contentType: CONTENT_TYPE, timeout: HTTP_TIMEOUT_SECS ]
    asynchttpGet(callback, httpParams, data)
    runInMillis(100, 'decrementActiveRequests')
  } else {
    def retryData = [uri: uri, callback: callback]
    if (data?.device && uri.contains('/room')) {
      retryData.data = [deviceId: data.device.getDeviceNetworkId()]
    } else {
      retryData.data = data
    }
    runInMillis(API_CALL_DELAY_MS, 'retryGetDataAsync', [data: retryData])
  }
}

// Updated patchDataAsync to accept a String callback name with simple throttling.
// If callback is null, we use a no-op callback.
def patchDataAsync(String uri, String callback, body, data = null) {
  if (!callback) { callback = 'noOpHandler' }
  if (canMakeRequest()) {
    incrementActiveRequests()
    def headers = [ Authorization: "Bearer ${state.flairAccessToken}" ]
    def httpParams = [
       uri: uri,
       headers: headers,
       contentType: CONTENT_TYPE,
       requestContentType: CONTENT_TYPE,
       timeout: HTTP_TIMEOUT_SECS,
       body: JsonOutput.toJson(body)
    ]
    asynchttpPatch(callback, httpParams, data)
    runInMillis(100, 'decrementActiveRequests')
  } else {
    def retryData = [uri: uri, callback: callback, body: body, data: data]
    runInMillis(API_CALL_DELAY_MS, 'retryPatchDataAsync', [data: retryData])
  }
}

def noOpHandler(resp, data) {
  log 'noOpHandler called', 3
}

def login() {
  authenticate()
  getStructureData()
}

def authenticate() {
  log 'Getting access_token from Flair', 2
  def uri = "${BASE_URL}/oauth2/token"
  def body = "client_id=${settings?.clientId}&client_secret=${settings?.clientSecret}" +
    '&scope=vents.view+vents.edit+structures.view+structures.edit+pucks.view+pucks.edit&grant_type=client_credentials'
  def params = [uri: uri, body: body, timeout: HTTP_TIMEOUT_SECS]
  try {
    httpPost(params) { response -> handleAuthResponse(response) }
    state.remove('authError')
  } catch (groovyx.net.http.HttpResponseException e) {
    def err = "Login failed - ${e.getLocalizedMessage()}: ${e.response.data}"
    logError err
    state.authError = err
    return err
  }
  return ''
}

def handleAuthResponse(resp) {
  def respJson = resp.getData()
  if (respJson?.access_token) {
    state.flairAccessToken = respJson.access_token
    state.remove('authError')
    log 'Authentication successful', 3
  } else {
    def errorDetails = respJson?.error_description ?: respJson?.error ?: 'Unknown error'
    state.authError = "Authentication failed: ${errorDetails}. " +
                      "If you're using OAuth 1.0 credentials, please ensure they are Legacy API credentials. " +
                      "OAuth 2.0 credentials are recommended for better device discovery."
    logError state.authError
  }
}

def appButtonHandler(String btn) {
  switch (btn) {
    case 'authenticate':
      login()
      unschedule(login)
      runEvery1Hour(login)
      break
    case 'retryAuth':
      login()
      unschedule(login)
      runEvery1Hour(login)
      break
    case 'discoverDevices':
      discover()
      break
  }
}

// Auto-authenticate when credentials are provided
def autoAuthenticate() {
  if (settings?.clientId && settings?.clientSecret && !state.flairAccessToken) {
    log 'Auto-authenticating with provided credentials', 2
    login()
    unschedule(login)
    runEvery1Hour(login)
  }
}

// Automatically re-authenticate when token expires
def autoReauthenticate() {
  log 'Token expired or invalid, re-authenticating...', 2
  state.remove('flairAccessToken')
  // Clear any error state
  state.remove('authError')
  // Re-authenticate and reschedule
  if (authenticate() == '') {
    // If authentication succeeded, reschedule hourly refresh
    unschedule(login)
    runEvery1Hour(login)
    log 'Re-authentication successful, rescheduled hourly token refresh', 2
  }
}

private void discover() {
  log 'Discovery started', 3
  atomicState.remove('ventsByRoomId')
  def structureId = getStructureId()
  // Discover vents first
  def ventsUri = "${BASE_URL}/api/structures/${structureId}/vents"
  log "Calling vents endpoint: ${ventsUri}", 2
  getDataAsync(ventsUri, 'handleDeviceList', [deviceType: 'vents'])
  // Then discover pucks separately - they might be at a different endpoint
  def pucksUri = "${BASE_URL}/api/structures/${structureId}/pucks"
  log "Calling pucks endpoint: ${pucksUri}", 2
  getDataAsync(pucksUri, 'handleDeviceList', [deviceType: 'pucks'])
  // Also try to get pucks from rooms since they might be associated there
  def roomsUri = "${BASE_URL}/api/structures/${structureId}/rooms?include=pucks"
  log "Calling rooms endpoint for pucks: ${roomsUri}", 2
  getDataAsync(roomsUri, 'handleRoomsWithPucks')
  // Try getting pucks directly without structure
  def allPucksUri = "${BASE_URL}/api/pucks"
  log "Calling all pucks endpoint: ${allPucksUri}", 2
  getDataAsync(allPucksUri, 'handleAllPucks')
}


def handleAllPucks(resp, data) {
  try {
    log "handleAllPucks called", 2
    if (!isValidResponse(resp)) { 
      log "handleAllPucks: Invalid response status: ${resp?.getStatus()}", 2
      return 
    }
    def respJson = resp.getJson()
    log "All pucks endpoint response: has data=${respJson?.data != null}, count=${respJson?.data?.size() ?: 0}", 2
    
    if (respJson?.data) {
      def puckCount = 0
      respJson.data.each { puckData ->
        try {
          if (puckData?.id) {
            puckCount++
            def puckId = puckData.id?.toString()?.trim()
            def puckName = puckData.attributes?.name?.toString()?.trim() ?: "Puck-${puckId}"
            
            log "Creating puck from all pucks endpoint: ${puckName} (${puckId})", 2
            
            def device = [
              id   : puckId,
              type : 'pucks',
              label: puckName
            ]
            
            def dev = makeRealDevice(device)
            if (dev) {
              log "Created puck device: ${puckName}", 2
            }
          }
        } catch (Exception e) {
          log "Error processing puck from all pucks: ${e.message}", 1
        }
      }
      if (puckCount > 0) {
        log "Discovered ${puckCount} pucks from all pucks endpoint", 3
      }
    }
  } catch (Exception e) {
    log "Error in handleAllPucks: ${e.message}", 1
  }
}

def handleRoomsWithPucks(resp, data) {
  try {
    log "handleRoomsWithPucks called", 2
    if (!isValidResponse(resp)) { 
      log "handleRoomsWithPucks: Invalid response status: ${resp?.getStatus()}", 2
      return 
    }
    def respJson = resp.getJson()
    
    // Log the structure to debug
    log "handleRoomsWithPucks response: has included=${respJson?.included != null}, included count=${respJson?.included?.size() ?: 0}, has data=${respJson?.data != null}, data count=${respJson?.data?.size() ?: 0}", 2
    
    // Check if we have included pucks data
    if (respJson?.included) {
      def puckCount = 0
      respJson.included.each { it ->
        try {
          if (it?.type == 'pucks' && it?.id) {
            puckCount++
            def puckId = it.id?.toString()?.trim()
            if (!puckId || puckId.isEmpty()) {
              log "Skipping puck with invalid ID", 2
              return // Skip this puck
            }
            
            def puckName = it.attributes?.name?.toString()?.trim()
            // Ensure we have a valid name
            if (!puckName || puckName.isEmpty()) {
              puckName = "Puck-${puckId}"
            }
            
            // Double-check the name is not empty after all processing
            if (!puckName || puckName.isEmpty()) {
              log "Skipping puck with empty name even after fallback", 2
              return
            }
            
            log "About to create puck device with id: ${puckId}, name: ${puckName}", 1
            
            def device = [
              id   : puckId,
              type : 'pucks',  // Use string literal to ensure it's not null
              label: puckName
            ]
            
            def dev = makeRealDevice(device)
            if (dev) {
              log "Created puck device: ${puckName}", 2
            }
          }
        } catch (Exception e) {
          log "Error processing puck in loop: ${e.message}, line: ${e.stackTrace?.find()?.lineNumber}", 1
        }
      }
      if (puckCount > 0) {
        log "Discovered ${puckCount} pucks from rooms include", 3
      }
    }
  } catch (Exception e) {
    log "Error in handleRoomsWithPucks: ${e.message} at line ${e.stackTrace?.find()?.lineNumber}", 1
  }
  
  
  // Also check if pucks are in the room data relationships
  try {
    if (respJson?.data) {
      def roomPuckCount = 0
      respJson.data.each { room ->
        if (room.relationships?.pucks?.data) {
          room.relationships.pucks.data.each { puck ->
            try {
              roomPuckCount++
              def puckId = puck.id?.toString()?.trim()
              if (!puckId || puckId.isEmpty()) {
                log "Skipping puck with invalid ID in room ${room.attributes?.name}", 2
                return
              }
              
              // Create a minimal puck device from the reference
              def puckName = "Puck-${puckId}"
              if (room.attributes?.name) {
                puckName = "${room.attributes.name} Puck"
              }
              
              log "Creating puck device from room reference: ${puckName} (${puckId})", 2
              
              def device = [
                id   : puckId,
                type : 'pucks',
                label: puckName
              ]
              
              def dev = makeRealDevice(device)
              if (dev) {
                log "Created puck device from room reference: ${puckName}", 2
              }
            } catch (Exception e) {
              log "Error creating puck from room reference: ${e.message}", 1
            }
          }
        }
      }
      if (roomPuckCount > 0) {
        log "Found ${roomPuckCount} puck references in rooms", 3
      }
    }
  } catch (Exception e) {
    log "Error checking room puck relationships: ${e.message}", 1
  }
}


def handleDeviceList(resp, data) {
  log "handleDeviceList called for ${data?.deviceType}", 2
  if (!isValidResponse(resp)) { 
    // Check if this was a pucks request that returned 404
    if (resp?.hasError() && resp.getStatus() == 404 && data?.deviceType == 'pucks') {
      log "Pucks endpoint returned 404 - this is normal, trying other methods", 2
    } else if (data?.deviceType == 'pucks') {
      log "Pucks endpoint failed with error: ${resp?.getStatus()}", 2
    }
    return 
  }
  def respJson = resp.getJson()
  if (!respJson?.data || respJson.data.isEmpty()) {
    if (data?.deviceType == 'pucks') {
      log "No pucks found in structure endpoint - they may be included with rooms instead", 2
    } else {
      logWarn "No devices discovered. This may occur with OAuth 1.0 credentials. " +
              "Please ensure you're using OAuth 2.0 credentials or Legacy API (OAuth 1.0) credentials."
    }
    return
  }
  def ventCount = 0
  def puckCount = 0
  respJson.data.each { it ->
    if (it.type == 'vents' || it.type == 'pucks') {
      if (it.type == 'vents') {
        ventCount++
      } else if (it.type == 'pucks') {
        puckCount++
      }
      def device = [
        id   : it.id,
        type : it.type,
        label: it.attributes.name
      ]
      def dev = makeRealDevice(device)
      if (dev && it.type == 'vents') {
        processVentTraits(dev, [data: it])
      }
    }
  }
  log "Discovered ${ventCount} vents and ${puckCount} pucks", 3
  if (ventCount == 0 && puckCount == 0) {
    logWarn "No devices found in the structure. " +
            "This typically happens with incorrect OAuth credentials."
  }
}

def makeRealDevice(Map device) {
  // Validate inputs
  if (!device?.id || !device?.label || !device?.type) {
    logError "Invalid device data: ${device}"
    return null
  }
  
  def deviceId = device.id?.toString()?.trim()
  def deviceLabel = device.label?.toString()?.trim()
  
  if (!deviceId || deviceId.isEmpty() || !deviceLabel || deviceLabel.isEmpty()) {
    logError "Invalid device ID or label: id=${deviceId}, label=${deviceLabel}"
    return null
  }
  
  def newDevice = getChildDevice(deviceId)
  if (!newDevice) {
    def deviceType = device.type == 'vents' ? 'Flair vents' : 'Flair pucks'
    try {
      newDevice = addChildDevice('bot.flair', deviceType, deviceId, [name: deviceLabel, label: deviceLabel])
    } catch (Exception e) {
      logError "Failed to add child device: ${e.message}"
      return null
    }
  }
  return newDevice
}

def getDeviceData(device) {
  log "Refresh device details for ${device}", 2
  def deviceId = device.getDeviceNetworkId()
  def roomId = device.currentValue('room-id')
  
  // Check if it's a puck by looking for the percent-open attribute which only vents have
  def isPuck = !device.hasAttribute('percent-open')
  
  if (isPuck) {
    // Get puck data and current reading with caching
    getDeviceDataWithCache(device, deviceId, 'pucks', 'handlePuckGet')
    getDeviceReadingWithCache(device, deviceId, 'pucks', 'handlePuckReadingGet')
    // Check cache before making room API call
    getRoomDataWithCache(device, deviceId, isPuck)
  } else {
    // Get vent reading with caching
    getDeviceReadingWithCache(device, deviceId, 'vents', 'handleDeviceGet')
    // Check cache before making room API call
    getRoomDataWithCache(device, deviceId, isPuck)
  }
}

// New function to handle room data with caching
def getRoomDataWithCache(device, deviceId, isPuck) {
  def roomId = device.currentValue('room-id')
  
  if (roomId) {
    // Check cache first using instance-based cache
    def cachedData = getCachedRoomData(roomId)
    if (cachedData) {
      log "Using cached room data for room ${roomId}", 3
      processRoomTraits(device, cachedData)
      return
    }
    
    // Check if a request is already pending for this room
    if (isRequestPending(roomId)) {
      // log "Room data request already pending for room ${roomId}, skipping duplicate request", 3
      return
    }
    
    // Mark this room as having a pending request
    markRequestPending(roomId)
  }
  
  // No valid cache and no pending request, make the API call
  def endpoint = isPuck ? "pucks" : "vents"
  getDataAsync("${BASE_URL}/api/${endpoint}/${deviceId}/room", 'handleRoomGetWithCache', [device: device])
}

// New function to handle device data with caching (for pucks)
def getDeviceDataWithCache(device, deviceId, deviceType, callback) {
  def cacheKey = "${deviceType}_${deviceId}"
  
  // Check cache first using instance-based cache
  def cachedData = getCachedDeviceReading(cacheKey)
  if (cachedData) {
    log "Using cached ${deviceType} data for device ${deviceId}", 3
    // Process the cached data
    if (callback == 'handlePuckGet') {
      handlePuckGet([getJson: { cachedData }], [device: device])
    }
    return
  }
  
  // Check if a request is already pending
  if (isDeviceRequestPending(cacheKey)) {
    // log "${deviceType} data request already pending for device ${deviceId}, skipping duplicate request", 3
    return
  }
  
  // Mark this device as having a pending request
  markDeviceRequestPending(cacheKey)
  
  // No valid cache and no pending request, make the API call
  def uri = "${BASE_URL}/api/${deviceType}/${deviceId}"
  getDataAsync(uri, callback + 'WithCache', [device: device, cacheKey: cacheKey])
}

// New function to handle device reading with caching
def getDeviceReadingWithCache(device, deviceId, deviceType, callback) {
  def cacheKey = "${deviceType}_reading_${deviceId}"
  
  // Check cache first using instance-based cache
  def cachedData = getCachedDeviceReading(cacheKey)
  if (cachedData) {
    log "Using cached ${deviceType} reading for device ${deviceId}", 3
    // Process the cached data
    if (callback == 'handlePuckReadingGet') {
      handlePuckReadingGet([getJson: { cachedData }], [device: device])
    } else if (callback == 'handleDeviceGet') {
      handleDeviceGet([getJson: { cachedData }], [device: device])
    }
    return
  }
  
  // Check if a request is already pending
  if (isDeviceRequestPending(cacheKey)) {
    // log "${deviceType} reading request already pending for device ${deviceId}, skipping duplicate request", 3
    return
  }
  
  // Mark this device as having a pending request
  markDeviceRequestPending(cacheKey)
  
  // No valid cache and no pending request, make the API call
  def uri = deviceType == 'pucks' ? "${BASE_URL}/api/pucks/${deviceId}/current-reading" : "${BASE_URL}/api/vents/${deviceId}/current-reading"
  getDataAsync(uri, callback + 'WithCache', [device: device, cacheKey: cacheKey])
}

def handleRoomGet(resp, data) {
  if (!isValidResponse(resp) || !data?.device) { return }
  processRoomTraits(data.device, resp.getJson())
}

// Modified handleRoomGet to include caching
def handleRoomGetWithCache(resp, data) {
  def roomData = null
  def roomId = null
  
  try {
    // First, try to get roomId from device for cleanup purposes
    if (data?.device) {
      roomId = data.device.currentValue('room-id')
    }
    
    if (isValidResponse(resp) && data?.device) {
      roomData = resp.getJson()
      // Update roomId if we got it from response
      if (roomData?.data?.id) {
        roomId = roomData.data.id
      }
      
      if (roomId) {
        // Cache the room data using instance-based cache
        cacheRoomData(roomId, roomData)
        log "Cached room data for room ${roomId}", 3
      }
      
      processRoomTraits(data.device, roomData)
    } else {
      // Log the error for debugging
      log "Room data request failed for device ${data?.device}, status: ${resp?.getStatus()}", 2
    }
  } catch (Exception e) {
    log "Error in handleRoomGetWithCache: ${e.message}", 1
  } finally {
    // Always clear the pending flag, even if the request failed
    if (roomId) {
      clearPendingRequest(roomId)
      log "Cleared pending request for room ${roomId}", 1
    }
  }
}

// Add a method to clear the cache periodically (optional)
def clearRoomCache() {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  def currentTime = getCurrentTime()
  def expiredRooms = []
  
  def roomCacheTimestamps = state."${cacheKey}_roomCacheTimestamps"
  def roomCache = state."${cacheKey}_roomCache"
  
  roomCacheTimestamps.each { roomId, timestamp ->
    if ((currentTime - timestamp) > ROOM_CACHE_DURATION_MS) {
      expiredRooms << roomId
    }
  }
  
  expiredRooms.each { roomId ->
    roomCache.remove(roomId)
    roomCacheTimestamps.remove(roomId)
    log "Cleared expired cache for room ${roomId}", 4
  }
}

// Clear device cache periodically
def clearDeviceCache() {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  def currentTime = getCurrentTime()
  def expiredDevices = []
  
  def deviceCacheTimestamps = state."${cacheKey}_deviceCacheTimestamps"
  def deviceCache = state."${cacheKey}_deviceCache"
  
  deviceCacheTimestamps.each { deviceKey, timestamp ->
    if ((currentTime - timestamp) > DEVICE_CACHE_DURATION_MS) {
      expiredDevices << deviceKey
    }
  }
  
  expiredDevices.each { deviceKey ->
    deviceCache.remove(deviceKey)
    deviceCacheTimestamps.remove(deviceKey)
    log "Cleared expired cache for device ${deviceKey}", 4
  }
}

// Periodic cleanup of pending request flags
def cleanupPendingRequests() {
  initializeInstanceCaches()
  def instanceId = getInstanceId()
  def cacheKey = "instanceCache_${instanceId}"
  
  def pendingRoomRequests = state."${cacheKey}_pendingRoomRequests"
  def pendingDeviceRequests = state."${cacheKey}_pendingDeviceRequests"
  
  // Collect keys first to avoid concurrent modification
  def roomsToClean = []
  pendingRoomRequests.each { roomId, isPending ->
    if (isPending) {
      roomsToClean << roomId
    }
  }
  
  // Now modify the map outside of iteration
  roomsToClean.each { roomId ->
    pendingRoomRequests[roomId] = false
  }
  
  if (roomsToClean.size() > 0) {
    log "Cleared ${roomsToClean.size()} stuck pending request flags for rooms: ${roomsToClean.join(', ')}", 2
  }
  
  // Same for device requests
  def devicesToClean = []
  pendingDeviceRequests.each { deviceKey, isPending ->
    if (isPending) {
      devicesToClean << deviceKey
    }
  }
  
  devicesToClean.each { deviceKey ->
    pendingDeviceRequests[deviceKey] = false
  }
  
  if (devicesToClean.size() > 0) {
    log "Cleared ${devicesToClean.size()} stuck pending request flags for devices: ${devicesToClean.join(', ')}", 2
  }
}

def handleDeviceGet(resp, data) {
  if (!isValidResponse(resp) || !data?.device) { return }
  processVentTraits(data.device, resp.getJson())
}

// Modified handleDeviceGet to include caching
def handleDeviceGetWithCache(resp, data) {
  def deviceData = null
  def cacheKey = data?.cacheKey
  
  try {
    if (isValidResponse(resp) && data?.device) {
      deviceData = resp.getJson()
      
      if (cacheKey && deviceData) {
        // Cache the device data using instance-based cache
        cacheDeviceReading(cacheKey, deviceData)
        log "Cached device reading for ${cacheKey}", 3
      }
      
      processVentTraits(data.device, deviceData)
    } else {
      log "Device reading request failed for ${cacheKey}, status: ${resp?.getStatus()}", 2
    }
  } catch (Exception e) {
    log "Error in handleDeviceGetWithCache: ${e.message}", 1
  } finally {
    // Always clear the pending flag
    if (cacheKey) {
      clearDeviceRequestPending(cacheKey)
      log "Cleared pending device request for ${cacheKey}", 1
    }
  }
}

def handlePuckGet(resp, data) {
  if (!isValidResponse(resp) || !data?.device) { return }
  def respJson = resp.getJson()
  if (respJson?.data) {
    def puckData = respJson.data
    // Extract puck attributes
    if (puckData.attributes?.'current-temperature-c' != null) {
      def tempC = puckData.attributes['current-temperature-c']
      def tempF = (tempC * 9/5) + 32
      sendEvent(data.device, [name: 'temperature', value: tempF, unit: '°F'])
      log "Puck temperature: ${tempF}°F", 2
    }
    if (puckData.attributes?.'current-humidity' != null) {
      sendEvent(data.device, [name: 'humidity', value: puckData.attributes['current-humidity'], unit: '%'])
    }
    if (puckData.attributes?.voltage != null) {
      try {
        def voltage = puckData.attributes.voltage as BigDecimal
        def battery = ((voltage - 2.0) / 1.6) * 100  // Assuming 2.0V = 0%, 3.6V = 100%
        battery = Math.max(0, Math.min(100, battery.round() as int))
        sendEvent(data.device, [name: 'battery', value: battery, unit: '%'])
      } catch (Exception e) {
        log "Error calculating battery for puck: ${e.message}", 2
      }
    }
    ['inactive', 'created-at', 'updated-at', 'current-rssi', 'name'].each { attr ->
      if (puckData.attributes && puckData.attributes[attr] != null) {
        sendEvent(data.device, [name: attr, value: puckData.attributes[attr]])
      }
    }
  }
}

// Modified handlePuckGet to include caching
def handlePuckGetWithCache(resp, data) {
  def deviceData = null
  def cacheKey = data?.cacheKey
  
  try {
    if (isValidResponse(resp) && data?.device) {
      deviceData = resp.getJson()
      
      if (cacheKey && deviceData) {
        // Cache the device data using instance-based cache
        cacheDeviceReading(cacheKey, deviceData)
        log "Cached puck data for ${cacheKey}", 3
      }
      
      // Process using existing logic
      handlePuckGet([getJson: { deviceData }], data)
    }
  } finally {
    // Always clear the pending flag
    if (cacheKey) {
      clearDeviceRequestPending(cacheKey)
    }
  }
}

def handlePuckReadingGet(resp, data) {
  if (!isValidResponse(resp) || !data?.device) { return }
  def respJson = resp.getJson()
  if (respJson?.data) {
    def reading = respJson.data
    // Process sensor reading data
    if (reading.attributes?.'room-temperature-c' != null) {
      def tempC = reading.attributes['room-temperature-c']
      def tempF = (tempC * 9/5) + 32
      sendEvent(data.device, [name: 'temperature', value: tempF, unit: '°F'])
      log "Puck temperature from reading: ${tempF}°F", 2
    }
    if (reading.attributes?.humidity != null) {
      sendEvent(data.device, [name: 'humidity', value: reading.attributes.humidity, unit: '%'])
    }
    if (reading.attributes?.'system-voltage' != null) {
      try {
        def voltage = reading.attributes['system-voltage'] as BigDecimal
        def battery = ((voltage - 2.0) / 1.6) * 100
        battery = Math.max(0, Math.min(100, battery.round() as int))
        sendEvent(data.device, [name: 'battery', value: battery, unit: '%'])
      } catch (Exception e) {
        log "Error calculating battery from reading: ${e.message}", 2
      }
    }
  }
}

// Modified handlePuckReadingGet to include caching
def handlePuckReadingGetWithCache(resp, data) {
  def deviceData = null
  def cacheKey = data?.cacheKey
  
  try {
    if (isValidResponse(resp) && data?.device) {
      deviceData = resp.getJson()
      
      if (cacheKey && deviceData) {
        // Cache the device data using instance-based cache
        cacheDeviceReading(cacheKey, deviceData)
        log "Cached puck reading for ${cacheKey}", 3
      }
      
      // Process using existing logic
      handlePuckReadingGet([getJson: { deviceData }], data)
    }
  } finally {
    // Always clear the pending flag
    if (cacheKey) {
      clearDeviceRequestPending(cacheKey)
    }
  }
}

def traitExtract(device, details, String propNameData, String propNameDriver = propNameData, unit = null) {
  try {
    def propValue = details.data.attributes[propNameData]
    if (propValue != null) {
      def eventData = [name: propNameDriver, value: propValue]
      if (unit) { eventData.unit = unit }
      sendEvent(device, eventData)
    }
    log "Extracted: ${propNameData} = ${propValue}", 1
  } catch (err) {
    logWarn err
  }
}

def processVentTraits(device, details) {
  logDetails "Processing Vent data for ${device}", details, 1
  if (!details?.data) {
    logWarn "Failed extracting data for ${device}"
    return
  }
  ['firmware-version-s', 'rssi', 'connected-gateway-name', 'created-at', 'duct-pressure',
   'percent-open', 'duct-temperature-c', 'motor-run-time', 'system-voltage', 'motor-current',
   'has-buzzed', 'updated-at', 'inactive'].each { attr ->
      traitExtract(device, details, attr, attr == 'percent-open' ? 'level' : attr, attr == 'percent-open' ? '%' : null)
   }
}

def processRoomTraits(device, details) {
  if (!device || !details?.data || !details.data.id) { return }
  logDetails "Processing Room data for ${device}", details, 1
  sendEvent(device, [name: 'room-id', value: details.data.id])
  [
    'name': 'room-name',
    'current-temperature-c': 'room-current-temperature-c',
    'room-conclusion-mode': 'room-conclusion-mode',
    'humidity-away-min': 'room-humidity-away-min',
    'room-type': 'room-type',
    'temp-away-min-c': 'room-temp-away-min-c',
    'level': 'room-level',
    'hold-until': 'room-hold-until',
    'room-away-mode': 'room-away-mode',
    'heat-cool-mode': 'room-heat-cool-mode',
    'updated-at': 'room-updated-at',
    'state-updated-at': 'room-state-updated-at',
    'set-point-c': 'room-set-point-c',
    'hold-until-schedule-event': 'room-hold-until-schedule-event',
    'frozen-pipe-pet-protect': 'room-frozen-pipe-pet-protect',
    'created-at': 'room-created-at',
    'windows': 'room-windows',
    'air-return': 'room-air-return',
    'current-humidity': 'room-current-humidity',
    'hold-reason': 'room-hold-reason',
    'occupancy-mode': 'room-occupancy-mode',
    'temp-away-max-c': 'room-temp-away-max-c',
    'humidity-away-max': 'room-humidity-away-max',
    'preheat-precool': 'room-preheat-precool',
    'active': 'room-active',
    'set-point-manual': 'room-set-point-manual',
    'pucks-inactive': 'room-pucks-inactive'
  ].each { key, driverKey ->
    traitExtract(device, details, key, driverKey)
  }

  if (details?.data?.relationships?.structure?.data) {
    sendEvent(device, [name: 'structure-id', value: details.data.relationships.structure.data.id])
  }
  if (details?.data?.relationships['remote-sensors']?.data && 
      !details.data.relationships['remote-sensors'].data.isEmpty()) {
    def remoteSensor = details.data.relationships['remote-sensors'].data.first()
    if (remoteSensor?.id) {
      def uri = "${BASE_URL}/api/remote-sensors/${remoteSensor.id}/sensor-readings"
      getDataAsync(uri, 'handleRemoteSensorGet', [device: device])
    }
  }
  updateByRoomIdState(details)
}

def handleRemoteSensorGet(resp, data) {
  if (!data) { return }
  
  // Don't log 404 errors for missing sensors - this is expected
  if (resp?.hasError() && resp.getStatus() == 404) {
    log "No remote sensor data available for ${data.device}", 1
    return
  }
  
  if (!isValidResponse(resp)) { return }
  def details = resp.getJson()
  if (!details?.data?.first()) { return }
  def propValue = details.data.first().attributes['occupied']
  sendEvent(data.device, [name: 'room-occupied', value: propValue])
}

def updateByRoomIdState(details) {
  if (!details?.data?.relationships?.vents?.data) { return }
  def roomId = details.data.id
  if (!atomicState.ventsByRoomId?."${roomId}") {
    def ventIds = details.data.relationships.vents.data.collect { it.id }
    atomicStateUpdate('ventsByRoomId', roomId, ventIds)
  }
}

def patchStructureData(Map attributes) {
  def body = [data: [type: 'structures', attributes: attributes]]
  def uri = "${BASE_URL}/api/structures/${getStructureId()}"
  patchDataAsync(uri, null, body)
}

def getStructureData() {
  log 'getStructureData', 1
  def uri = "${BASE_URL}/api/structures"
  def headers = [ Authorization: "Bearer ${state.flairAccessToken}" ]
  def httpParams = [ uri: uri, headers: headers, contentType: CONTENT_TYPE, timeout: HTTP_TIMEOUT_SECS ]
  httpGet(httpParams) { resp ->
    if (!resp.success) { return }
    def response = resp.getData()
    if (!response) {
      logError 'getStructureData: no data'
      return
    }
    // Only log full response at debug level 1
    logDetails 'Structure response: ', response, 1
    def myStruct = response.data.first()
    if (!myStruct?.attributes) {
      logError 'getStructureData: no structure data'
      return
    }
    // Log only essential fields at level 3
    log "Structure loaded: id=${myStruct.id}, name=${myStruct.attributes.name}, mode=${myStruct.attributes.mode}", 3
    app.updateSetting('structureId', myStruct.id)
  }
}

def patchVent(device, int percentOpen) {
  def pOpen = Math.min(100, Math.max(0, percentOpen))
  def currentOpen = (device?.currentValue('percent-open') ?: 0).toInteger()
  if (pOpen == currentOpen) {
    log "Keeping ${device} percent open unchanged at ${pOpen}%", 3
    return
  }
  log "Setting ${device} percent open from ${currentOpen} to ${pOpen}%", 3
  def deviceId = device.getDeviceNetworkId()
  def uri = "${BASE_URL}/api/vents/${deviceId}"
  def body = [ data: [ type: 'vents', attributes: [ 'percent-open': pOpen ] ] ]
  patchDataAsync(uri, 'handleVentPatch', body, [device: device])
  try {
    safeSendEvent(device, [name: 'percent-open', value: pOpen])
    safeSendEvent(device, [name: 'level', value: pOpen])
  } catch (Exception e) {
    log "Warning: Could not send device events: ${e.message}", 2
  }
}

def handleVentPatch(resp, data) {
  if (!isValidResponse(resp) || !data) { return }
  traitExtract(data.device, resp.getJson(), 'percent-open', '%')
  traitExtract(data.device, resp.getJson(), 'percent-open', 'level', '%')
}

def patchRoom(device, active) {
  def roomId = device.currentValue('room-id')
  if (!roomId || active == null) { return }
  if (active == device.currentValue('room-active')) { return }
  log "Setting active state to ${active} for '${device.currentValue('room-name')}'", 3
  def uri = "${BASE_URL}/api/rooms/${roomId}"
  def body = [ data: [ type: 'rooms', attributes: [ 'active': active == 'true' ] ] ]
  patchDataAsync(uri, 'handleRoomPatch', body, [device: device])
}

def handleRoomPatch(resp, data) {
  if (!isValidResponse(resp) || !data) { return }
  traitExtract(data.device, resp.getJson(), 'active', 'room-active')
}

def thermostat1ChangeTemp(evt) {
  log "Thermostat changed temp to: ${evt.value}", 2
  def temp = thermostat1.currentValue('temperature')
  def coolingSetpoint = thermostat1.currentValue('coolingSetpoint') ?: 0
  def heatingSetpoint = thermostat1.currentValue('heatingSetpoint') ?: 0
  String hvacMode = calculateHvacMode(temp, coolingSetpoint, heatingSetpoint)
  def thermostatSetpoint = getThermostatSetpoint(hvacMode)
  
  // Apply hysteresis to prevent frequent cycling
  def lastSignificantTemp = atomicState.lastSignificantTemp ?: temp
  def tempDiff = Math.abs(temp - lastSignificantTemp)
  
  if (tempDiff >= THERMOSTAT_HYSTERESIS) {
    atomicState.lastSignificantTemp = temp
    log "Significant temperature change detected: ${tempDiff}°C (threshold: ${THERMOSTAT_HYSTERESIS}°C)", 2
    
    if (isThermostatAboutToChangeState(hvacMode, thermostatSetpoint, temp)) {
      runInMillis(INITIALIZATION_DELAY_MS, 'initializeRoomStates', [data: hvacMode])
    }
  } else {
    log "Temperature change ${tempDiff}°C is below hysteresis threshold ${THERMOSTAT_HYSTERESIS}°C - ignoring", 3
  }
}

def isThermostatAboutToChangeState(String hvacMode, BigDecimal setpoint, BigDecimal temp) {
  if (hvacMode == COOLING && temp + SETPOINT_OFFSET - VENT_PRE_ADJUST_THRESHOLD < setpoint) {
    atomicState.tempDiffsInsideThreshold = false
    return false
  } else if (hvacMode == HEATING && temp - SETPOINT_OFFSET + VENT_PRE_ADJUST_THRESHOLD > setpoint) {
    atomicState.tempDiffsInsideThreshold = false
    return false
  }
  if (atomicState.tempDiffsInsideThreshold == true) { return false }
  atomicState.tempDiffsInsideThreshold = true
  log "Pre-adjusting vents for upcoming HVAC start. [mode=${hvacMode}, setpoint=${setpoint}, temp=${temp}]", 3
  return true
}

def thermostat1ChangeStateHandler(evt) {
  log "Thermostat changed state to: ${evt.value}", 3
  def hvacMode = evt.value in [PENDING_COOL, PENDING_HEAT] ? (evt.value == PENDING_COOL ? COOLING : HEATING) : evt.value
  switch (hvacMode) {
    case COOLING:
    case HEATING:
      if (atomicState.thermostat1State) {
        log "initializeRoomStates already executed (${evt.value})", 3
        return
      }
      atomicStateUpdate('thermostat1State', 'mode', hvacMode)
      atomicStateUpdate('thermostat1State', 'startedRunning', now())
      unschedule(initializeRoomStates)
      runInMillis(POST_STATE_CHANGE_DELAY_MS, 'initializeRoomStates', [data: hvacMode])
      recordStartingTemperatures()
      runEvery5Minutes('evaluateRebalancingVents')
      runEvery30Minutes('reBalanceVents')
      
      // Update polling to active interval when HVAC is running
      updateDevicePollingInterval(POLLING_INTERVAL_ACTIVE)
      break
    default:
      unschedule(initializeRoomStates)
      unschedule(finalizeRoomStates)
      unschedule(evaluateRebalancingVents)
      unschedule(reBalanceVents)
      if (atomicState.thermostat1State) {
        atomicStateUpdate('thermostat1State', 'finishedRunning', now())
        def params = [
          ventIdsByRoomId: atomicState.ventsByRoomId,
          startedCycle: atomicState.thermostat1State?.startedCycle,
          startedRunning: atomicState.thermostat1State?.startedRunning,
          finishedRunning: atomicState.thermostat1State?.finishedRunning,
          hvacMode: atomicState.thermostat1State?.mode
        ]
        runInMillis(TEMP_READINGS_DELAY_MS, 'finalizeRoomStates', [data: params])
        atomicState.remove('thermostat1State')
      }
      
      // Update polling to idle interval when HVAC is idle
      updateDevicePollingInterval(POLLING_INTERVAL_IDLE)
      break
  }
}

def reBalanceVents() {
  log 'Rebalancing Vents!!!', 3
  def params = [
    ventIdsByRoomId: atomicState.ventsByRoomId,
    startedCycle: atomicState.thermostat1State?.startedCycle,
    startedRunning: atomicState.thermostat1State?.startedRunning,
    finishedRunning: now(),
    hvacMode: atomicState.thermostat1State?.mode
  ]
  finalizeRoomStates(params)
  initializeRoomStates(atomicState.thermostat1State?.mode)
}

def evaluateRebalancingVents() {
  if (!atomicState.thermostat1State) { return }
  def ventIdsByRoomId = atomicState.ventsByRoomId
  String hvacMode = atomicState.thermostat1State?.mode
  def setPoint = getThermostatSetpoint(hvacMode)

  ventIdsByRoomId.each { roomId, ventIds ->
    for (ventId in ventIds) {
      try {
        def vent = getChildDevice(ventId)
        if (!vent) { continue }
        if (vent.currentValue('room-active') != 'true') { continue }
        def currPercentOpen = (vent.currentValue('percent-open') ?: 0).toInteger()
        if (currPercentOpen <= STANDARD_VENT_DEFAULT_OPEN) { continue }
        def roomTemp = getRoomTemp(vent)
        if (!hasRoomReachedSetpoint(hvacMode, setPoint, roomTemp, REBALANCING_TOLERANCE)) {
          continue
        }
        log "Rebalancing Vents - '${vent.currentValue('room-name')}' is at ${roomTemp}° (target: ${setPoint})", 3
        reBalanceVents()
        break
      } catch (err) {
        logError err
      }
    }
  }
}

def finalizeRoomStates(data) {
  // Check for required parameters
  if (!data.ventIdsByRoomId || !data.startedCycle || !data.finishedRunning) {
    logWarn "Finalizing room states: missing required parameters (${data})"
    return
  }
  
  // Handle edge case when HVAC was already running during code deployment
  if (!data.startedRunning || !data.hvacMode) {
    log "Skipping room state finalization - HVAC cycle started before code deployment", 2
    return
  }
  log 'Start - Finalizing room states', 3
  def totalRunningMinutes = (data.finishedRunning - data.startedRunning) / (1000 * 60)
  def totalCycleMinutes = (data.finishedRunning - data.startedCycle) / (1000 * 60)
  log "HVAC ran for ${totalRunningMinutes} minutes", 3

  atomicState.maxHvacRunningTime = roundBigDecimal(
      rollingAverage(atomicState.maxHvacRunningTime ?: totalRunningMinutes, totalRunningMinutes), 6)

  if (totalCycleMinutes >= MIN_MINUTES_TO_SETPOINT) {
    // Track room rates that have been calculated
    Map<String, BigDecimal> roomRates = [:]
    
    data.ventIdsByRoomId.each { roomId, ventIds ->
      for (ventId in ventIds) {
        def vent = getChildDevice(ventId)
        if (!vent) {
          log "Failed getting vent Id ${ventId}", 3
          continue
        }
        
        def roomName = vent.currentValue('room-name')
        def ratePropName = data.hvacMode == COOLING ? 'room-cooling-rate' : 'room-heating-rate'
        
        // Check if rate already calculated for this room
        if (roomRates.containsKey(roomName)) {
          // Use the already calculated rate for this room
          def rate = roomRates[roomName]
          sendEvent(vent, [name: ratePropName, value: rate])
          log "Applying same ${ratePropName} (${roundBigDecimal(rate)}) to additional vent in '${roomName}'", 3
          continue
        }
        
        // Calculate rate for this room (first vent in room)
        def percentOpen = (vent.currentValue('percent-open') ?: 0).toInteger()
        BigDecimal currentTemp = getRoomTemp(vent)
        BigDecimal lastStartTemp = vent.currentValue('room-starting-temperature-c') ?: 0
        BigDecimal currentRate = vent.currentValue(ratePropName) ?: 0
        def newRate = calculateRoomChangeRate(lastStartTemp, currentTemp, totalCycleMinutes, percentOpen, currentRate)
        
        if (newRate <= 0) {
          log "New rate for ${roomName} is ${newRate}", 3
          
          // Check if room is already at or beyond setpoint
          def isAtSetpoint = hasRoomReachedSetpoint(data.hvacMode, 
              getThermostatSetpoint(data.hvacMode), currentTemp)
          
          if (isAtSetpoint && currentRate > 0) {
            // Room is already at setpoint - maintain last known efficiency
            log "${roomName} is already at setpoint, maintaining last known efficiency rate: ${currentRate}", 3
            newRate = currentRate  // Keep existing rate
          } else if (percentOpen > 0) {
            // Vent was open but no temperature change - use minimum rate
            newRate = MIN_TEMP_CHANGE_RATE
            log "Setting minimum rate for ${roomName} - no temperature change detected with ${percentOpen}% open vent", 3
          } else if (currentRate == 0) {
            // Room has zero efficiency and vent was closed - set baseline efficiency
            def maxRate = data.hvacMode == COOLING ? 
                atomicState.maxCoolingRate ?: MAX_TEMP_CHANGE_RATE : 
                atomicState.maxHeatingRate ?: MAX_TEMP_CHANGE_RATE
            newRate = maxRate * 0.1  // 10% of maximum as baseline
            log "Setting baseline efficiency for ${roomName} (10% of max rate: ${newRate})", 3
          } else {
            continue  // Skip if vent was closed and room has existing efficiency
          }
        }
        
        def rate = rollingAverage(currentRate, newRate, percentOpen / 100, 4)
        sendEvent(vent, [name: ratePropName, value: rate])
        log "Updating ${roomName}'s ${ratePropName} to ${roundBigDecimal(rate)}", 3
        
        // Store the calculated rate for this room
        roomRates[roomName] = rate
        
        // Track maximum rates for baseline calculations
        if (rate > 0) {
          if (data.hvacMode == COOLING) {
            def maxCoolRate = atomicState.maxCoolingRate ?: 0
            if (rate > maxCoolRate) {
              atomicState.maxCoolingRate = rate
              log "Updated maximum cooling rate to ${rate}", 3
            }
          } else if (data.hvacMode == HEATING) {
            def maxHeatRate = atomicState.maxHeatingRate ?: 0
            if (rate > maxHeatRate) {
              atomicState.maxHeatingRate = rate
              log "Updated maximum heating rate to ${rate}", 3
            }
          }
        }
      }
    }
  } else {
    log "Could not calculate room states as it ran for ${totalCycleMinutes} minutes and needs to run for at least ${MIN_MINUTES_TO_SETPOINT} minutes", 3
  }
  log 'End - Finalizing room states', 3
}

def recordStartingTemperatures() {
  if (!atomicState.ventsByRoomId) { return }
  log "Recording starting temperatures for all rooms", 2
  atomicState.ventsByRoomId.each { roomId, ventIds ->
    ventIds.each { ventId ->
      try {
        def vent = getChildDevice(ventId)
        if (!vent) { return }
        BigDecimal currentTemp = getRoomTemp(vent)
        sendEvent(vent, [name: 'room-starting-temperature-c', value: currentTemp])
        log "Starting temperature for '${vent.currentValue('room-name')}': ${currentTemp}°C", 2
      } catch (err) {
        logError err
      }
    }
  }
}

def initializeRoomStates(String hvacMode) {
  if (!settings.dabEnabled) { return }
  log "Initializing room states - hvac mode: ${hvacMode}", 3
  if (!atomicState.ventsByRoomId) { return }
  
  BigDecimal setpoint = getThermostatSetpoint(hvacMode)
  if (!setpoint) { return }
  atomicStateUpdate('thermostat1State', 'startedCycle', now())
  def rateAndTempPerVentId = getAttribsPerVentId(atomicState.ventsByRoomId, hvacMode)
  
  def maxRunningTime = atomicState.maxHvacRunningTime ?: MAX_MINUTES_TO_SETPOINT
  def longestTimeToTarget = calculateLongestMinutesToTarget(rateAndTempPerVentId, hvacMode, setpoint, maxRunningTime, settings.thermostat1CloseInactiveRooms)
  if (longestTimeToTarget < 0) {
    log "All vents already reached setpoint (${setpoint})", 3
    longestTimeToTarget = maxRunningTime
  }
  if (longestTimeToTarget == 0) {
    log "Opening all vents (setpoint: ${setpoint})", 3
    openAllVents(atomicState.ventsByRoomId, MAX_PERCENTAGE_OPEN as int)
    return
  }
  log "Initializing room states - setpoint: ${setpoint}, longestTimeToTarget: ${roundBigDecimal(longestTimeToTarget)}", 3

  def calcPercentOpen = calculateOpenPercentageForAllVents(rateAndTempPerVentId, hvacMode, setpoint, longestTimeToTarget, settings.thermostat1CloseInactiveRooms)
  if (!calcPercentOpen) {
    log "No vents are being changed (setpoint: ${setpoint})", 3
    return
  }

  calcPercentOpen = adjustVentOpeningsToEnsureMinimumAirflowTarget(rateAndTempPerVentId, hvacMode, calcPercentOpen, settings.thermostat1AdditionalStandardVents)

  calcPercentOpen.each { ventId, percentOpen ->
    def vent = getChildDevice(ventId)
    if (vent) {
      patchVent(vent, roundToNearestMultiple(percentOpen))
    }
  }
}

def adjustVentOpeningsToEnsureMinimumAirflowTarget(rateAndTempPerVentId, String hvacMode, Map calculatedPercentOpen, additionalStandardVents) {
  int totalDeviceCount = additionalStandardVents > 0 ? additionalStandardVents : 0
  def sumPercentages = totalDeviceCount * STANDARD_VENT_DEFAULT_OPEN
  calculatedPercentOpen.each { ventId, percent ->
    totalDeviceCount++
    sumPercentages += percent ?: 0
  }
  if (totalDeviceCount <= 0) {
    logWarn 'Total device count is zero'
    return calculatedPercentOpen
  }

  BigDecimal maxTemp = null
  BigDecimal minTemp = null
  rateAndTempPerVentId.each { ventId, stateVal ->
    maxTemp = maxTemp == null || maxTemp < stateVal.temp ? stateVal.temp : maxTemp
    minTemp = minTemp == null || minTemp > stateVal.temp ? stateVal.temp : minTemp
  }
  if (minTemp == null || maxTemp == null) {
    minTemp = 20.0
    maxTemp = 25.0
  } else {
    minTemp = minTemp - TEMP_BOUNDARY_ADJUSTMENT
    maxTemp = maxTemp + TEMP_BOUNDARY_ADJUSTMENT
  }

  def combinedFlowPercentage = (100 * sumPercentages) / (totalDeviceCount * 100)
  if (combinedFlowPercentage >= MIN_COMBINED_VENT_FLOW) {
    log "Combined vent flow percentage (${combinedFlowPercentage}%) is greater than ${MIN_COMBINED_VENT_FLOW}%", 3
    return calculatedPercentOpen
  }
  log "Combined Vent Flow Percentage (${combinedFlowPercentage}) is lower than ${MIN_COMBINED_VENT_FLOW}%", 3
  def targetPercentSum = MIN_COMBINED_VENT_FLOW * totalDeviceCount
  def diffPercentageSum = targetPercentSum - sumPercentages
  log "sumPercentages=${sumPercentages}, targetPercentSum=${targetPercentSum}, diffPercentageSum=${diffPercentageSum}", 2
  int iterations = 0
  while (diffPercentageSum > 0 && iterations++ < MAX_ITERATIONS) {
    for (item in rateAndTempPerVentId) {
      def ventId = item.key
      def stateVal = item.value
      BigDecimal percentOpenVal = calculatedPercentOpen[ventId] ?: 0
      if (percentOpenVal >= MAX_PERCENTAGE_OPEN) {
        percentOpenVal = MAX_PERCENTAGE_OPEN
      } else {
        def proportion = hvacMode == COOLING ?
          (stateVal.temp - minTemp) / (maxTemp - minTemp) :
          (maxTemp - stateVal.temp) / (maxTemp - minTemp)
        def increment = INCREMENT_PERCENTAGE * proportion
        percentOpenVal = percentOpenVal + increment
        calculatedPercentOpen[ventId] = percentOpenVal
        log "Adjusting % open from ${roundBigDecimal(percentOpenVal - increment)}% to ${roundBigDecimal(percentOpenVal)}%", 2
        diffPercentageSum = diffPercentageSum - increment
        if (diffPercentageSum <= 0) { break }
      }
    }
  }
  return calculatedPercentOpen
}

def getAttribsPerVentId(ventsByRoomId, String hvacMode) {
  def rateAndTemp = [:]
  ventsByRoomId.each { roomId, ventIds ->
    ventIds.each { ventId ->
      try {
        def vent = getChildDevice(ventId)
        if (!vent) { return }
        def rate = hvacMode == COOLING ? (vent.currentValue('room-cooling-rate') ?: 0) : (vent.currentValue('room-heating-rate') ?: 0)
        rate = rate ?: 0
        def isActive = vent.currentValue('room-active') == 'true'
        def roomTemp = getRoomTemp(vent)
        def roomName = vent.currentValue('room-name') ?: ''
        
        // Log rooms with zero efficiency for debugging
        if (rate == 0) {
          def tempSource = settings."thermostat${ventId}" ? "Puck ${settings."thermostat${ventId}".getLabel()}" : "Room API"
          log "Room '${roomName}' has zero ${hvacMode} efficiency rate, temp=${roomTemp}°C from ${tempSource}", 2
        }
        
        rateAndTemp[ventId] = [ rate: rate, temp: roomTemp, active: isActive, name: roomName ]
      } catch (err) {
        logError err
      }
    }
  }
  return rateAndTemp
}

def calculateOpenPercentageForAllVents(rateAndTempPerVentId, String hvacMode, BigDecimal setpoint, longestTime, boolean closeInactive = true) {
  def percentOpenMap = [:]
  rateAndTempPerVentId.each { ventId, stateVal ->
    try {
      def percentageOpen = MIN_PERCENTAGE_OPEN
      if (closeInactive && !stateVal.active) {
        log "Closing vent on inactive room: ${stateVal.name}", 3
      } else if (stateVal.rate < MIN_TEMP_CHANGE_RATE) {
        log "Opening vent at max since change rate is too low: ${stateVal.name}", 3
        percentageOpen = MAX_PERCENTAGE_OPEN
      } else {
        percentageOpen = calculateVentOpenPercentage(stateVal.name, stateVal.temp, setpoint, hvacMode, stateVal.rate, longestTime)
      }
      percentOpenMap[ventId] = percentageOpen
    } catch (err) {
      logError err
    }
  }
  return percentOpenMap
}

def calculateVentOpenPercentage(String roomName, BigDecimal startTemp, BigDecimal setpoint, String hvacMode, BigDecimal maxRate, BigDecimal longestTime) {
  if (hasRoomReachedSetpoint(hvacMode, setpoint, startTemp)) {
    def msg = hvacMode == COOLING ? 'cooler' : 'warmer'
    log "'${roomName}' is already ${msg} (${startTemp}) than setpoint (${setpoint})", 3
    return MIN_PERCENTAGE_OPEN
  }
  BigDecimal percentageOpen = MAX_PERCENTAGE_OPEN
  if (maxRate > 0 && longestTime > 0) {
    BigDecimal BASE_CONST = 0.0991
    BigDecimal EXP_CONST = 2.3

    // Calculate the target rate: the average temperature change required per minute.
    def targetRate = Math.abs(setpoint - startTemp) / longestTime
    percentageOpen = BASE_CONST * Math.exp((targetRate / maxRate) * EXP_CONST)
    percentageOpen = roundBigDecimal(percentageOpen * 100, 3)

    // Ensure percentageOpen stays within defined limits.
    percentageOpen = percentageOpen < MIN_PERCENTAGE_OPEN ? MIN_PERCENTAGE_OPEN :
                           (percentageOpen > MAX_PERCENTAGE_OPEN ? MAX_PERCENTAGE_OPEN : percentageOpen)
    log "changing percentage open for ${roomName} to ${percentageOpen}% (maxRate=${roundBigDecimal(maxRate)})", 3
  }
  return percentageOpen
}


def calculateLongestMinutesToTarget(rateAndTempPerVentId, String hvacMode, BigDecimal setpoint, maxRunningTime, boolean closeInactive = true) {
  def longestTime = -1
  rateAndTempPerVentId.each { ventId, stateVal ->
    try {
      def minutesToTarget = -1
      if (closeInactive && !stateVal.active) {
        log "'${stateVal.name}' is inactive", 3
      } else if (hasRoomReachedSetpoint(hvacMode, setpoint, stateVal.temp)) {
        log "'${stateVal.name}' has already reached setpoint", 3
      } else if (stateVal.rate > 0) {
        minutesToTarget = Math.abs(setpoint - stateVal.temp) / stateVal.rate
        // Check for unrealistic time estimates due to minimal temperature change
        if (minutesToTarget > maxRunningTime * 2) {
          logWarn "'${stateVal.name}' shows minimal temperature change (rate: ${roundBigDecimal(stateVal.rate)}°C/min). " +
                  "Estimated time ${roundBigDecimal(minutesToTarget)} minutes is unrealistic."
          minutesToTarget = maxRunningTime  // Cap at max running time
        }
      } else if (stateVal.rate == 0) {
        minutesToTarget = 0
        logWarn "'${stateVal.name}' shows no temperature change with vent open"
      }
      if (minutesToTarget > maxRunningTime) {
        logWarn "'${stateVal.name}' is estimated to take ${roundBigDecimal(minutesToTarget)} minutes to reach target temp, which is longer than the average ${roundBigDecimal(maxRunningTime)} minutes"
        minutesToTarget = maxRunningTime
      }
      longestTime = Math.max(longestTime, minutesToTarget.doubleValue())
      log "Room '${stateVal.name}' temp: ${stateVal.temp}", 3
    } catch (err) {
      logError err
    }
  }
  return longestTime
}

// Overloaded method for backward compatibility with tests
def calculateRoomChangeRate(def lastStartTemp, def currentTemp, def totalMinutes, def percentOpen, def currentRate) {
  // Null safety checks
  if (lastStartTemp == null || currentTemp == null || totalMinutes == null || percentOpen == null || currentRate == null) {
    log "calculateRoomChangeRate: null parameter detected", 3
    return -1
  }
  
  try {
    return calculateRoomChangeRate(
      lastStartTemp as BigDecimal, 
      currentTemp as BigDecimal, 
      totalMinutes as BigDecimal, 
      percentOpen as int, 
      currentRate as BigDecimal
    )
  } catch (Exception e) {
    log "calculateRoomChangeRate casting error: ${e.message}", 3
    return -1
  }
}

def calculateRoomChangeRate(BigDecimal lastStartTemp, BigDecimal currentTemp, BigDecimal totalMinutes, int percentOpen, BigDecimal currentRate) {
  if (totalMinutes < MIN_MINUTES_TO_SETPOINT) {
    log "Insufficient number of minutes required to calculate change rate (${totalMinutes} should be greater than ${MIN_MINUTES_TO_SETPOINT})", 3
    return -1
  }
  
  // Skip rate calculation if HVAC hasn't run long enough for meaningful temperature changes
  if (totalMinutes < MIN_RUNTIME_FOR_RATE_CALC) {
    log "HVAC runtime too short for rate calculation: ${totalMinutes} minutes < ${MIN_RUNTIME_FOR_RATE_CALC} minutes minimum", 3
    return -1
  }
  
  if (percentOpen <= MIN_PERCENTAGE_OPEN) {
    log "Vent was opened less than ${MIN_PERCENTAGE_OPEN}% (${percentOpen}), therefore it is being excluded", 3
    return -1
  }
  
  BigDecimal diffTemps = Math.abs(lastStartTemp - currentTemp)
  
  // Check if temperature change is within sensor noise/accuracy range
  if (diffTemps < MIN_DETECTABLE_TEMP_CHANGE) {
    log "Temperature change (${diffTemps}°C) is below minimum detectable threshold (${MIN_DETECTABLE_TEMP_CHANGE}°C) - likely sensor noise", 2
    
    // If no meaningful temperature change but vent was significantly open, assign minimum efficiency
    if (percentOpen >= 30) {
      log "Vent was ${percentOpen}% open but no meaningful temperature change detected - assigning minimum efficiency", 2
      return MIN_TEMP_CHANGE_RATE
    }
    return -1
  }
  
  // Account for sensor accuracy when detecting minimal changes
  if (diffTemps < TEMP_SENSOR_ACCURACY) {
    log "Temperature change (${diffTemps}°C) is within sensor accuracy range (±${TEMP_SENSOR_ACCURACY}°C) - adjusting calculation", 2
    // Use a minimum reliable change for calculation to avoid division by near-zero
    diffTemps = Math.max(diffTemps, MIN_DETECTABLE_TEMP_CHANGE)
  }
  
  BigDecimal rate = diffTemps / totalMinutes
  BigDecimal pOpen = percentOpen / 100
  BigDecimal maxRate = Math.max(rate.doubleValue(), currentRate.doubleValue())
  BigDecimal approxRate = maxRate != 0 ? (rate / maxRate) / pOpen : 0
  if (approxRate > MAX_TEMP_CHANGE_RATE) {
    log "Change rate (${roundBigDecimal(approxRate)}) is greater than ${MAX_TEMP_CHANGE_RATE}, therefore it is being excluded", 3
    return -1
  } else if (approxRate < MIN_TEMP_CHANGE_RATE) {
    log "Change rate (${roundBigDecimal(approxRate)}) is lower than ${MIN_TEMP_CHANGE_RATE}, adjusting to minimum (startTemp=${lastStartTemp}, currentTemp=${currentTemp}, percentOpen=${percentOpen}%)", 3
    // Return minimum rate instead of excluding to prevent zero efficiency
    return MIN_TEMP_CHANGE_RATE
  }
  return approxRate
}

// ------------------------------
// Dynamic Polling Control
// ------------------------------

def updateDevicePollingInterval(Integer intervalMinutes) {
  log "Updating device polling interval to ${intervalMinutes} minutes", 3
  
  // Update all child vents
  getChildDevices()?.findAll { it.typeName == 'Flair vents' }?.each { device ->
    try {
      device.updateParentPollingInterval(intervalMinutes)
    } catch (Exception e) {
      log "Error updating polling interval for vent ${device.getLabel()}: ${e.message}", 2
    }
  }
  
  // Update all child pucks  
  getChildDevices()?.findAll { it.typeName == 'Flair pucks' }?.each { device ->
    try {
      device.updateParentPollingInterval(intervalMinutes)
    } catch (Exception e) {
      log "Error updating polling interval for puck ${device.getLabel()}: ${e.message}", 2
    }
  }
  
  atomicState.currentPollingInterval = intervalMinutes
  log "Updated polling interval for ${getChildDevices()?.size() ?: 0} devices", 3
}

// ------------------------------
// End of Core Functions
// ------------------------------
