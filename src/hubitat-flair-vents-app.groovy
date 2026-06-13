import groovy.transform.Field
import groovy.json.JsonOutput
#include bot.flair.FlairVentsDabv2

/**
 *  Hubitat Flair Vents Integration
 *  Version 0.236
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

// Reachable learned-rate (regime) gate (Task 2.5, F-04 / DC-4; R11.7).
// A learned/regime rate is selected over the baseline EMA once it has at least
// this many positive-rate samples. This is the *reachable* gate that lets a
// learned rate actually diverge from baseline, replacing the Reference's
// unreachable normalized-weight gate (which is NOT present in this Integration).
// This constant is the contract carried forward to the pure Learning_Model
// (task 4.3), where it gates the per-regime cell rate the same way.
@Field static final Integer REGIME_MIN_N = 3

// Temperature sensor accuracy and noise filtering
@Field static final BigDecimal TEMP_SENSOR_ACCURACY = 0.5  // ±0.5°C typical sensor accuracy
@Field static final BigDecimal MIN_DETECTABLE_TEMP_CHANGE = 0.1  // Minimum change to consider real
@Field static final Integer MIN_RUNTIME_FOR_RATE_CALC = 5  // Minimum minutes before calculating rate

// Minimum combined vent airflow percentage across all vents (to ensure proper HVAC operation).
@Field static final BigDecimal MIN_COMBINED_VENT_FLOW = 30.0

// Anti-chatter defaults (Task 2.3, F-03 / DC-2). These gate ORDINARY balancing
// moves ("padding above the floor") to preserve battery and quiet operation;
// a move strictly required to reach the airflow-safety floor bypasses them so a
// needed safety open is never suppressed (R6.2, R10.4, R10.5).
@Field static final Long VENT_MOVE_MIN_INTERVAL_MS = 180000   // 3 min minimum adjustment interval (cooldown)
@Field static final BigDecimal VENT_MOVE_MIN_PERCENT = 5.0    // minimum adjustment percent (position deadband)

// Per-evaluation batch limit (R10.4): at most this many ORDINARY room-group
// moves are dispatched per evaluation, to spread vent traffic and respect the
// Flair rate limit. Floor-required moves (R6.2) are NEVER counted against or
// throttled by this cap — a needed safety open always goes out.
@Field static final Integer VENT_MOVE_MAX_PER_CYCLE = 8

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

// Minimum smart-vent aperture (in %) for a satisfied room to be worth a
// mid-cycle rebalance. Task 2.6 / review-findings.md F-05 (L3, DC-5): this
// threshold previously reused STANDARD_VENT_DEFAULT_OPEN (a conventional-vent
// default) for an unrelated purpose, conflating two semantically distinct
// constants. It is broken out here with the same value (behaviour-preserving)
// so the rebalance-eligibility intent is explicit and can be tuned independently.
@Field static final Integer REBALANCING_MIN_OPEN_PERCENT = 50

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
@Field static final Integer API_CALL_DELAY_MS = 1000 * 3

// Maximum concurrent HTTP requests to prevent API overload.
@Field static final Integer MAX_CONCURRENT_REQUESTS = 8

// Maximum number of retry attempts for async API calls.
@Field static final Integer MAX_API_RETRY_ATTEMPTS = 5

// === DAB v2 topology + configuration validation (Task 9.1; R4, R5.6, R19) ===

// Control-strategy values (R5.6 / R20.3). `balance` is the DAB v2 synchronized-
// convergence strategy; `dab` is the retained legacy strategy fallback.
@Field static final String STRATEGY_BALANCE = 'balance'
@Field static final String STRATEGY_LEGACY = 'dab'

// Parity evidence gate (task 8.3 / R17.5). The off-device parity suite
// (`ParityScenarioSpec`, Correctness Property 17: ±1-point match + zero floor
// violations against the Python Reference) passing is the PRECONDITION for
// making `balance` the default control strategy on new installs (R20.1). This
// flag is the single in-app record of that gate: it is flipped to `true` only
// once the parity suite is green, and IF parity ever regresses it is set back to
// `false` so new installs fall back to the legacy `dab` strategy until the
// discrepancy is resolved or explicitly waived (R17.5). It NEVER changes an
// existing user's explicitly configured strategy (R20.2) — see
// resolveDabV2StrategyDefault / applyDabV2StrategyDefault.
@Field static final boolean DABV2_PARITY_GATE_PASSED = true

// Resolved DAB v2 conditioning action for an idle / fan-only / economizer /
// unknown thermostat state — no balancing commands are issued (R4.4, R10.6).
@Field static final String DABV2_ACTION_IDLE = 'idle'
// Supported topology ranges (R4.1): no device identities are hard-coded.
@Field static final Integer DABV2_MIN_ROOMS = 1
@Field static final Integer DABV2_MAX_ROOMS = 100
@Field static final Integer DABV2_MIN_SMART_VENTS = 1
@Field static final Integer DABV2_MAX_SMART_VENTS = 200
@Field static final Integer DABV2_MIN_CONVENTIONAL_VENTS = 0
@Field static final Integer DABV2_MAX_CONVENTIONAL_VENTS = 200
@Field static final Integer DABV2_MIN_THERMOSTATS = 1
@Field static final Integer DABV2_MAX_THERMOSTATS = 20

// Documented disposition for a smart vent not assigned to any room (R4.7):
// excluded from balancing AND from the combined-flow computation.
@Field static final String DABV2_UNASSIGNED_VENT_RULE = 'excluded-from-balancing-and-combined-flow'

// `state` key under which the compact schema-v2 learned model is persisted
// (R12.1). The app owns this read/write seam; the pure Dabv2ModelIo module does
// the encode/decode/migrate/bound math.
@Field static final String DABV2_MODEL_STATE_KEY = 'dabv2LearnedModel'

// Numeric config defaults + safe ranges (R19.2; design Configuration table R19).
@Field static final BigDecimal SAFETY_FLOOR_DEFAULT = 40.0
@Field static final BigDecimal SAFETY_FLOOR_MIN = 20.0
@Field static final BigDecimal SAFETY_FLOOR_MAX = 90.0
@Field static final BigDecimal SPREAD_GUARDRAIL_DEFAULT = 1.0
@Field static final BigDecimal SPREAD_GUARDRAIL_MIN = 0.2
@Field static final BigDecimal SPREAD_GUARDRAIL_MAX = 5.0
@Field static final BigDecimal SPREAD_DEADBAND_DEFAULT = 0.3
@Field static final BigDecimal SPREAD_DEADBAND_MIN = 0.0
@Field static final BigDecimal SPREAD_DEADBAND_MAX = 2.0
@Field static final BigDecimal AIRFLOW_LIMITED_MARGIN_DEFAULT = 5.0
@Field static final BigDecimal AIRFLOW_LIMITED_MARGIN_MIN = 0.0
@Field static final BigDecimal AIRFLOW_LIMITED_MARGIN_MAX = 20.0
@Field static final BigDecimal AIRFLOW_LIMITED_ERROR_DEFAULT = 0.5
@Field static final BigDecimal AIRFLOW_LIMITED_ERROR_MIN = 0.1
@Field static final BigDecimal AIRFLOW_LIMITED_ERROR_MAX = 3.0

// === DAB v2 observability surfaces (Task 9.8; R13/R14) ===
// Per-room diagnostic child devices group each room's own values VERTICALLY
// (one device per room), and a single system-summary device carries the
// zone-wide rollups. The whole surface is OPT-IN (R14.6): when disabled nothing
// is created/emitted and control is never affected.
@Field static final String DABV2_DIAG_ROOM_DNI_PREFIX = 'dabv2-diag-room-'
@Field static final String DABV2_DIAG_SUMMARY_DNI = 'dabv2-diag-summary'
// Driver types created on-hub for the diagnostic surfaces. These are component
// drivers carrying the per-room / summary attributes; on a hub they must be
// installed alongside the app (the surface is opt-in, so their absence simply
// means no diagnostics).
@Field static final String DABV2_DIAG_ROOM_DRIVER = 'Flair Vents Room Diagnostics'
@Field static final String DABV2_DIAG_SUMMARY_DRIVER = 'Flair Vents Zone Summary'
// Repeated identical error notifications are coalesced within this window so a
// transient repeated failure does not spam the user (R14.5).
@Field static final long DABV2_ERROR_COALESCE_MS = 1_800_000L
// Rolling window for the 24 h recalculation / hold counters (R14.1).
@Field static final long DABV2_COUNTER_WINDOW_MS = 86_400_000L
@Field static final Integer CONVENTIONAL_VENTS_DEFAULT = 0
@Field static final BigDecimal CONVENTIONAL_OPEN_DEFAULT = 100.0
@Field static final BigDecimal CONVENTIONAL_OPEN_MIN = 0.0
@Field static final BigDecimal CONVENTIONAL_OPEN_MAX = 100.0
@Field static final Integer ACTIVE_INTERVAL_DEFAULT = 3
@Field static final Integer ACTIVE_INTERVAL_MIN = 1
@Field static final Integer ACTIVE_INTERVAL_MAX = 30
@Field static final Integer IDLE_INTERVAL_DEFAULT = 10
@Field static final Integer IDLE_INTERVAL_MIN = 1
@Field static final Integer IDLE_INTERVAL_MAX = 60
@Field static final Integer SHORT_CYCLE_GAP_DEFAULT = 10
@Field static final Integer SHORT_CYCLE_GAP_MIN = 0
@Field static final Integer SHORT_CYCLE_GAP_MAX = 60
// Single-flight evaluate guard staleness TTL (R21.7). A held guard older than
// this is assumed to belong to a crashed / watchdog-killed prior run and is
// reclaimed, so the evaluate path can never wedge permanently.
@Field static final Long EVAL_GUARD_TTL_MS = 120000L
@Field static final Integer PRE_ADJUST_DWELL_DEFAULT = 5
@Field static final Integer PRE_ADJUST_DWELL_MIN = 1
@Field static final Integer PRE_ADJUST_DWELL_MAX = 120
@Field static final BigDecimal PRE_ADJUST_TRIGGER_DEFAULT = 1.0
@Field static final BigDecimal PRE_ADJUST_TRIGGER_MIN = 0.1
@Field static final BigDecimal PRE_ADJUST_TRIGGER_MAX = 5.0

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
  page(name: 'efficiencyDataPage')
}

def mainPage() {
  dynamicPage(name: 'mainPage', title: 'Setup', install: true, uninstall: true) {
    section('OAuth Setup') {
      input name: 'clientId', type: 'text', title: 'Client Id (OAuth 2.0)', required: true, submitOnChange: true
      input name: 'clientSecret', type: 'password', title: 'Client Secret OAuth 2.0', required: true, submitOnChange: true
      paragraph '<small><b>Obtain your client Id and secret from ' +
                "<a href='https://forms.gle/VohiQjWNv9CAP2ASA' target='_blank'>here</a></b></small>"
      
      if (settings?.clientId && settings?.clientSecret) {
        if (!state.flairAccessToken && !state.authInProgress) {
          state.authInProgress = true
          state.remove('authError')  // Clear any previous error when starting new auth
          runIn(2, 'autoAuthenticate')
        }
        
        if (state.flairAccessToken && !state.authError) {
          paragraph "<span style='color: green;'>✓ Authenticated successfully</span>"
        } else if (state.authError && !state.authInProgress) {
          section {
            paragraph "<span style='color: red;'>${state.authError}</span>"
            input name: 'retryAuth', type: 'button', title: 'Retry Authentication', submitOnChange: true
            paragraph "<small>If authentication continues to fail, verify your credentials are correct and try again.</small>"
          }
        } else if (state.authInProgress) {
          paragraph "<span style='color: orange;'>⏳ Authenticating... Please wait.</span>"
          paragraph "<small>This may take 10-15 seconds. The page will refresh automatically when complete.</small>"
        } else {
          paragraph "<span style='color: orange;'>Ready to authenticate...</span>"
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

          input name: 'controlStrategy', type: 'enum', title: 'Control Strategy',
                options: dabV2ControlStrategyOptions(),
                defaultValue: dabV2NewInstallDefaultStrategy(), submitOnChange: true
          input name: 'safetyFloorPct', type: 'number', title: 'Airflow safety floor (%)', defaultValue: 40
          input name: 'spreadGuardrailC', type: 'decimal', title: 'Spread guardrail (C)', defaultValue: 1.0
          input name: 'spreadImprovementDeadbandC', type: 'decimal', title: 'Spread improvement deadband (C)', defaultValue: 0.3
          input name: 'crosscouplingEnabled', type: 'bool', title: 'Enable duct cross-coupling', defaultValue: true
          input name: 'airflowLimitedMarginPct', type: 'number', title: 'Airflow-limited margin (%)', defaultValue: 5
          input name: 'airflowLimitedErrorC', type: 'decimal', title: 'Airflow-limited error threshold (C)', defaultValue: 0.5
          input name: 'conventionalOpenPct', type: 'number', title: 'Conventional vent assumed open (%)', defaultValue: 100
          input name: 'activeIntervalMin', type: 'number', title: 'Active evaluation interval (min)', defaultValue: 3
          input name: 'idleIntervalMin', type: 'number', title: 'Idle evaluation interval (min)', defaultValue: 10
          input name: 'shortCycleGapMin', type: 'number', title: 'Short-cycle idle gap (min)', defaultValue: 10
          input name: 'preAdjustDwellMin', type: 'number', title: 'Pre-adjust minimum idle dwell (min)', defaultValue: 5
          input name: 'preAdjustTriggerC', type: 'decimal', title: 'Pre-adjust trigger threshold (C)', defaultValue: 1.0
          input name: 'outdoorTempSource', type: 'capability.temperatureMeasurement', title: 'Outdoor temp source (optional)', required: false
          input name: 'doorSensor', type: 'capability.contactSensor', title: 'Whole-home door sensor (optional, fallback)', required: false
          input name: 'occupancySource', type: 'capability.presenceSensor', title: 'Occupancy source (optional, fallback)', required: false
          // Per-room door sensors (R11.6): an open door only affects its OWN
          // room's conditioning rate, so each discovered room may map its own
          // contact sensor. Rooms with no per-room sensor fall back to the
          // whole-home door sensor above. Occupancy is taken per-room from the
          // Flair puck automatically (the room-occupied attribute), with the
          // occupancy source above as the whole-home fallback.
          List dabRooms = dabV2RoomList()
          if (dabRooms.isEmpty()) {
            paragraph '<small>Per-room door sensors appear here after device discovery.</small>'
          } else {
            paragraph '<b>Per-room door sensors (optional)</b><br>' +
                      '<small>Map a contact sensor to a room so an open door only slows that room\u2019s ' +
                      'airflow estimate. Unmapped rooms use the whole-home door sensor above.</small>'
            dabRooms.each { room ->
              input name: "roomDoorSensor_${room.id}", type: 'capability.contactSensor',
                    title: "Door sensor \u2014 ${room.name}", required: false
            }
          }

          input name: 'dabV2DiagnosticsEnabled', type: 'bool',
                title: 'Create diagnostic devices (per-room + zone summary)', defaultValue: false
          if (settings.thermostat1AdditionalStandardVents < 0) {
            app.updateSetting('thermostat1AdditionalStandardVents', 0)
          } else if (settings.thermostat1AdditionalStandardVents > MAX_STANDARD_VENTS) {
            app.updateSetting('thermostat1AdditionalStandardVents', MAX_STANDARD_VENTS)
          }

          if (!getThermostat1Mode() || getThermostat1Mode() == 'auto') {
            patchStructureData([mode: 'manual'])
            atomicState?.putAt('thermostat1Mode', 'manual')
          }
          
          // Efficiency Data Management Link
          section {
            href name: 'efficiencyDataLink', title: '🔄 Backup & Restore Efficiency Data', 
                 description: 'Save your learned room efficiency data to restore after app updates', 
                 page: 'efficiencyDataPage'
            
            // Show current status summary
            def vents = getChildDevices().findAll { it.hasAttribute('percent-open') }
            if (vents.size() > 0) {
              def roomsWithData = vents.findAll { 
                (it.currentValue('room-cooling-rate') ?: 0) > 0 || 
                (it.currentValue('room-heating-rate') ?: 0) > 0 
              }
              paragraph "<small><b>Current Status:</b> ${roomsWithData.size()} of ${vents.size()} rooms have learned efficiency data</small>"
            }
            // DAB v2 diagnostics summary (R13/R14): the same values surfaced on
            // the diagnostic child devices, rendered here for visibility. Renders
            // to empty when the opt-in diagnostics surface is disabled.
            paragraph renderDabV2DiagnosticsStatus()
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
  // Existing install: record the default-strategy decision once, preserving the
  // user's explicit choice (or legacy on a pre-DAB-v2 upgrade) (R20.2).
  applyDabV2StrategyDefault()
  initialize()
}

def installed() {
  log.debug 'Hubitat Flair App installed'
  // Genuinely new install: adopt the parity-gated default strategy (R20.1/R17.5).
  state.dabV2FreshInstall = true
  applyDabV2StrategyDefault()
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
  
  // Clean up any existing BigDecimal precision issues
  cleanupExistingDecimalPrecision()

  // Load the DAB v2 learned model from persisted `state`, migrating any legacy /
  // pre-v2 payload on load with no data loss and back-filling new metric/counter
  // fields with documented defaults (R12.1, R12.3, R13.5).
  loadDabV2Model()
  
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
    def temp = settings.thermostat1?.currentValue('temperature') ?: 0
    def coolingSetpoint = settings.thermostat1?.currentValue('coolingSetpoint') ?: 0
    def heatingSetpoint = settings.thermostat1?.currentValue('heatingSetpoint') ?: 0
    String hvacMode = calculateHvacMode(temp, coolingSetpoint, heatingSetpoint)
    runInMillis(INITIALIZATION_DELAY_MS, 'initializeRoomStates', [data: hvacMode])
    
    // Set initial polling based on current thermostat state
    def currentThermostatState = settings.thermostat1?.currentValue('thermostatOperatingState')
    def initialInterval = (currentThermostatState in ['cooling', 'heating']) ? 
        POLLING_INTERVAL_ACTIVE : POLLING_INTERVAL_IDLE
    
    log "Setting initial polling interval to ${initialInterval} minutes based on thermostat state: ${currentThermostatState}", 3
    updateDevicePollingInterval(initialInterval)

    // Adaptive evaluation cadence (R22): start the single self-managed evaluate
    // job at the interval that matches the thermostat's current state, so the
    // cadence is live even if the app initializes mid-cycle.
    scheduleDabV2EvaluateCadence(currentThermostatState in ['cooling', 'heating'])
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
      log "WARNING: Temperature device ${tempDevice?.getLabel() ?: 'Unknown'} for room '${roomName}' is not reporting temperature!", 2
      // Fall back to room temperature
      def roomTemp = vent.currentValue('room-current-temperature-c') ?: 0
      log "Falling back to room temperature for '${roomName}': ${roomTemp}°C", 2
      return roomTemp
    }
    if (settings.thermostat1TempUnit == '2') {
      temp = convertFahrenheitToCentigrade(temp)
    }
    log "Got temp from ${tempDevice?.getLabel() ?: 'Unknown'} for '${roomName}': ${temp}°C", 2
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
      ((settings?.thermostat1?.currentValue('coolingSetpoint') ?: 0) - SETPOINT_OFFSET) :
      ((settings?.thermostat1?.currentValue('heatingSetpoint') ?: 0) + SETPOINT_OFFSET)
  setpoint = setpoint ?: settings?.thermostat1?.currentValue('thermostatSetpoint')
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

// Function to round values to specific decimal places for JSON export
def roundToDecimalPlaces(def value, int decimalPlaces) {
  if (value == null || value == 0) return 0
  
  try {
    // Convert to double
    def doubleValue = value as Double
    
    // Use basic math to round to decimal places - this definitely works in Hubitat
    def multiplier = Math.pow(10, decimalPlaces)
    def rounded = Math.round(doubleValue * multiplier) / multiplier
    
    // Return as Double to ensure proper JSON serialization
    return rounded as Double
  } catch (Exception e) {
    log "Error rounding value ${value}: ${e.message}", 2
    return 0
  }
}

// Function to clean decimal values for JSON serialization
// Enhanced version to handle Hubitat's BigDecimal precision issues
def cleanDecimalForJson(def value) {
  if (value == null || value == 0) return 0
  
  try {
    // Convert to String first to break BigDecimal precision chain
    def stringValue = value.toString()
    def doubleValue = Double.parseDouble(stringValue)
    
    // Handle edge cases
    if (!Double.isFinite(doubleValue)) {
      return 0.0d
    }
    
    // Apply aggressive rounding to exactly 10 decimal places
    def multiplier = 1000000000.0d  // 10^9 for 10 decimal places
    def rounded = Math.round(doubleValue * multiplier) / multiplier
    
    // Ensure we return a clean Double, not BigDecimal
    return Double.valueOf(rounded)
  } catch (Exception e) {
    log "Error cleaning decimal for JSON: ${e.message}", 2
    return 0.0d
  }
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

def convertCentigradeToFahrenheit(BigDecimal tempValue) {
  (tempValue * (9 / 5)) + 32
}

// ------------------------------
// DAB v2 topology + configuration validation (Task 9.1; R4, R5.6, R19)
//
// App-orchestration (non-pure) helpers. Internal control math is in Celsius;
// conversion happens only here at the device boundary (R19.3). All numeric
// inputs are validated/clamped to the documented ranges with documented
// defaults (R19.2). No device identity is hard-coded — topology comes entirely
// from user configuration / the live room-vent mapping (R4.1/R4.2).
// ------------------------------

// Coerce + clamp a raw setting to [minVal, maxVal]; non-numeric/missing -> default.
private BigDecimal clampDecimal(rawValue, BigDecimal minVal, BigDecimal maxVal, BigDecimal defaultVal) {
  String text = rawValue == null ? null : rawValue.toString().trim()
  if (!text || !text.isNumber()) {
    return defaultVal
  }
  BigDecimal value = text.toBigDecimal()
  if (value < minVal) {
    return minVal
  }
  if (value > maxVal) {
    return maxVal
  }
  return value
}

private Integer clampInt(rawValue, Integer minVal, Integer maxVal, Integer defaultVal) {
  BigDecimal value = clampDecimal(rawValue, minVal as BigDecimal, maxVal as BigDecimal, defaultVal as BigDecimal)
  return Math.round(value as double) as Integer
}

private boolean coerceBoolean(rawValue, boolean defaultVal) {
  if (rawValue == null) {
    return defaultVal
  }
  String text = rawValue.toString().trim().toLowerCase()
  if (text in ['true', '1', 'yes', 'on']) {
    return true
  }
  if (text in ['false', '0', 'no', 'off']) {
    return false
  }
  return defaultVal
}

String getDabV2ControlStrategy() {
  String raw = settings?.controlStrategy
  if (raw in [STRATEGY_BALANCE, STRATEGY_LEGACY]) {
    return raw
  }
  return STRATEGY_BALANCE
}

// Registered control-strategy values and their user-facing UI strings (R5.6 /
// R20.3). Single source of truth so the `controlStrategy` input, the resolver
// (getDabV2ControlStrategy), and the evaluate-loop selection
// (selectAndRunDabV2Evaluate) cannot drift apart. `balance` is the DAB v2
// synchronized-convergence strategy; `dab` is the retained legacy fallback.
Map dabV2ControlStrategyOptions() {
  return [(STRATEGY_BALANCE): 'Balance (DAB v2)', (STRATEGY_LEGACY): 'Legacy DAB']
}

// True iff the registered strategy is the DAB v2 `balance` strategy. Used by the
// evaluate loop to pick the target-computation path (R5.6).
boolean isDabV2BalanceStrategy() {
  return getDabV2ControlStrategy() == STRATEGY_BALANCE
}

// Pure parity-gated default selector (R17.5 / R20.1). The new-install default is
// `balance` ONLY when the parity suite has passed; otherwise the legacy `dab`
// strategy remains the default so an unvalidated `balance` never controls a
// house. Kept pure (boolean in, value out) so it is exercised off-device.
String dabV2DefaultStrategyForParity(boolean parityPassed) {
  return parityPassed ? STRATEGY_BALANCE : STRATEGY_LEGACY
}

// The new-install default control strategy, gated on the live parity flag
// (R17.5 / R20.1). Drives the `controlStrategy` input default and the fresh
// install seed in applyDabV2StrategyDefault.
String dabV2NewInstallDefaultStrategy() {
  return dabV2DefaultStrategyForParity(DABV2_PARITY_GATE_PASSED)
}

// Pure resolver for the default-strategy decision (R20.1 / R20.2). Given the
// currently configured value, whether this is a genuinely fresh install, and the
// parity-gate status, decide the strategy to record:
//   - an explicitly configured, valid strategy is ALWAYS preserved verbatim — an
//     existing user is never silently overridden (R20.2);
//   - a fresh install with no explicit choice adopts the parity-gated default
//     (`balance` iff parity passed, else `dab`) (R20.1 / R17.5);
//   - an existing user upgrading from a version without this input (no explicit
//     choice, not a fresh install) keeps the legacy `dab` strategy so they are
//     never silently flipped to `balance` (R20.2).
String resolveDabV2StrategyDefault(String existing, boolean freshInstall, boolean parityPassed) {
  if (existing in [STRATEGY_BALANCE, STRATEGY_LEGACY]) {
    return existing
  }
  if (freshInstall) {
    return dabV2DefaultStrategyForParity(parityPassed)
  }
  return STRATEGY_LEGACY
}

// Lifecycle applier (R20.1 / R20.2). Records the parity-gated default-strategy
// decision exactly once and seeds the `controlStrategy` setting accordingly so
// the selection is explicit and observable. Idempotent: once the decision has
// been recorded it is never redone, so a user who later changes strategy is not
// re-flipped. `installed()` marks `state.dabV2FreshInstall` before calling this
// (genuinely new install); `updated()` does not, so existing users are treated
// as upgrades and preserved. Returns the strategy now in effect.
String applyDabV2StrategyDefault() {
  if (state?.dabV2StrategyDefaultApplied == true) {
    return getDabV2ControlStrategy()
  }
  boolean freshInstall = (state?.dabV2FreshInstall == true)
  String resolved = resolveDabV2StrategyDefault(
    settings?.controlStrategy as String, freshInstall, DABV2_PARITY_GATE_PASSED)
  // `app` is unavailable in the off-device harness; on a real hub it is always
  // present, so the seed is persisted as before.
  app?.updateSetting('controlStrategy', resolved)
  state.dabV2StrategyDefaultApplied = true
  return resolved
}

BigDecimal getDabV2SafetyFloorPct() {
  clampDecimal(settings?.safetyFloorPct, SAFETY_FLOOR_MIN, SAFETY_FLOOR_MAX, SAFETY_FLOOR_DEFAULT)
}

BigDecimal getDabV2SpreadGuardrailC() {
  clampDecimal(settings?.spreadGuardrailC, SPREAD_GUARDRAIL_MIN, SPREAD_GUARDRAIL_MAX, SPREAD_GUARDRAIL_DEFAULT)
}

BigDecimal getDabV2SpreadImprovementDeadbandC() {
  clampDecimal(settings?.spreadImprovementDeadbandC, SPREAD_DEADBAND_MIN, SPREAD_DEADBAND_MAX, SPREAD_DEADBAND_DEFAULT)
}

boolean getDabV2CrosscouplingEnabled() {
  coerceBoolean(settings?.crosscouplingEnabled, true)
}

BigDecimal getDabV2AirflowLimitedMarginPct() {
  clampDecimal(settings?.airflowLimitedMarginPct, AIRFLOW_LIMITED_MARGIN_MIN, AIRFLOW_LIMITED_MARGIN_MAX, AIRFLOW_LIMITED_MARGIN_DEFAULT)
}

BigDecimal getDabV2AirflowLimitedErrorC() {
  clampDecimal(settings?.airflowLimitedErrorC, AIRFLOW_LIMITED_ERROR_MIN, AIRFLOW_LIMITED_ERROR_MAX, AIRFLOW_LIMITED_ERROR_DEFAULT)
}

Integer getDabV2ConventionalVentCount() {
  clampInt(settings?.thermostat1AdditionalStandardVents, DABV2_MIN_CONVENTIONAL_VENTS, DABV2_MAX_CONVENTIONAL_VENTS, CONVENTIONAL_VENTS_DEFAULT)
}

BigDecimal getDabV2ConventionalOpenPct() {
  clampDecimal(settings?.conventionalOpenPct, CONVENTIONAL_OPEN_MIN, CONVENTIONAL_OPEN_MAX, CONVENTIONAL_OPEN_DEFAULT)
}

Integer getDabV2ActiveIntervalMin() {
  clampInt(settings?.activeIntervalMin, ACTIVE_INTERVAL_MIN, ACTIVE_INTERVAL_MAX, ACTIVE_INTERVAL_DEFAULT)
}

Integer getDabV2IdleIntervalMin() {
  clampInt(settings?.idleIntervalMin, IDLE_INTERVAL_MIN, IDLE_INTERVAL_MAX, IDLE_INTERVAL_DEFAULT)
}

Integer getDabV2ShortCycleGapMin() {
  clampInt(settings?.shortCycleGapMin, SHORT_CYCLE_GAP_MIN, SHORT_CYCLE_GAP_MAX, SHORT_CYCLE_GAP_DEFAULT)
}

Integer getDabV2PreAdjustDwellMin() {
  clampInt(settings?.preAdjustDwellMin, PRE_ADJUST_DWELL_MIN, PRE_ADJUST_DWELL_MAX, PRE_ADJUST_DWELL_DEFAULT)
}

BigDecimal getDabV2PreAdjustTriggerC() {
  clampDecimal(settings?.preAdjustTriggerC, PRE_ADJUST_TRIGGER_MIN, PRE_ADJUST_TRIGGER_MAX, PRE_ADJUST_TRIGGER_DEFAULT)
}

// Whether inactive rooms are auto-closed during balancing (R16.4). The default
// is the user's CURRENT setting (`thermostat1CloseInactiveRooms`, which the UI
// defaults to true) — DAB v2 introduces no new default that would silently
// override what the user already chose. When false, inactive rooms are left
// untouched (never auto-closed or repositioned by balancing, R16.1).
boolean getDabV2CloseInactiveRooms() {
  coerceBoolean(settings?.thermostat1CloseInactiveRooms, true)
}

// Resolve the full validated DAB v2 configuration (R19.1/R19.2). Consumed by the
// evaluate loop / Allocator settings in later tasks.
Map getDabV2Config() {
  return [
    controlStrategy           : getDabV2ControlStrategy(),
    safetyFloorPct            : getDabV2SafetyFloorPct(),
    spreadGuardrailC          : getDabV2SpreadGuardrailC(),
    spreadImprovementDeadbandC: getDabV2SpreadImprovementDeadbandC(),
    crosscouplingEnabled      : getDabV2CrosscouplingEnabled(),
    airflowLimitedMarginPct   : getDabV2AirflowLimitedMarginPct(),
    airflowLimitedErrorC      : getDabV2AirflowLimitedErrorC(),
    conventionalVents         : getDabV2ConventionalVentCount(),
    conventionalOpenPct       : getDabV2ConventionalOpenPct(),
    activeIntervalMin         : getDabV2ActiveIntervalMin(),
    idleIntervalMin           : getDabV2IdleIntervalMin(),
    shortCycleGapMin          : getDabV2ShortCycleGapMin(),
    preAdjustDwellMin         : getDabV2PreAdjustDwellMin(),
    preAdjustTriggerC         : getDabV2PreAdjustTriggerC(),
    closeInactiveRooms        : getDabV2CloseInactiveRooms()
  ]
}

// The single documented rule for a smart vent not assigned to any room (R4.7).
String dabV2UnassignedVentRule() {
  return DABV2_UNASSIGNED_VENT_RULE
}

// ===========================================================================
// DAB v2 persistence wiring + legacy-state migration on load (task 9.7;
// R12.1, R12.3, R13.5)
//
// The pure Dabv2ModelIo module performs ALL encode/decode/migrate/bound math;
// the app owns ONLY the `state` read/write seam below (design "Persistence":
// "the app performs the actual state read/write"). Keeping the app thin here
// means the same migration/bounding rules are exercised off-device by the
// model-io unit/property tests (tasks 7.1/7.2).
// ===========================================================================

// Load the learned model from persisted `state`, migrating any legacy / pre-v2
// payload on load with NO data loss and back-filling new metric/counter fields
// with documented defaults (R12.1, R12.3, R13.5). On a fresh upgrade with no
// persisted v2 payload, the legacy efficiency data still held in child-device
// attributes is used as the migration source so learning is never lost.
def loadDabV2Model() {
  Object persisted = state?.get(DABV2_MODEL_STATE_KEY)
  boolean alreadyV2 = persisted instanceof Map &&
    ((Map) persisted).get('v') instanceof Number &&
    (((Map) persisted).get('v') as int) >= MIO_SCHEMA_VERSION

  // No persisted model yet → seed migration from the legacy device-attribute
  // efficiency data (the same shape the backup/restore export produces).
  Object source = persisted != null ? persisted : exportEfficiencyData()

  def model = mioMigrate(source)

  // Persist the migrated model back as schema v2 so the next load is a
  // straight-through decode. A payload already at v2 is left as-is, keeping
  // migration idempotent (migrating twice is a no-op).
  if (!alreadyV2) {
    saveDabV2Model(model)
  }
  return model
}

// Encode and size-bound the in-memory learned model via Dabv2ModelIo, then
// write the compact schema-v2 payload to `state` (R12.1, R12.2). Bounding is
// applied strictly in order and is always observable — never a silent
// truncation — so an over-budget persist is logged with the steps applied.
void saveDabV2Model(Map model) {
  def bounded = mioBound(model)
  state[DABV2_MODEL_STATE_KEY] = bounded.encoded
  if (!bounded.withinBudget) {
    log "DAB v2 model persisted at ${bounded.finalBytes} bytes; over budget even " +
        "after bounding steps ${bounded.stepsApplied}", 2
  } else if (!bounded.stepsApplied.isEmpty()) {
    log "DAB v2 model persisted at ${bounded.finalBytes} bytes after bounding " +
        "steps ${bounded.stepsApplied}", 3
  }
}

private Map dabV2TopologyError(String entry, String reason) {
  return [valid: false, offendingEntry: entry, reason: reason]
}

private String dabV2RangeMsg(String label, int value, int minVal, int maxVal) {
  return "${label} count ${value} is outside ${minVal}-${maxVal}"
}

// Smart vents whose room assignment is null/blank are unassigned and excluded
// (R4.7) — they are NOT a configuration error.
private List dabV2UnassignedVentIds(Map assignments) {
  List result = []
  (assignments ?: [:]).each { ventId, roomId ->
    String room = roomId == null ? null : roomId.toString().trim()
    if (!room) {
      result << ventId.toString()
    }
  }
  return result
}

// First vent assigned to a room that is not in the known-room set, or null.
private String dabV2FirstVentWithUnknownRoom(Map assignments, Set knownRooms) {
  if (!assignments || knownRooms == null) {
    return null
  }
  String found = null
  assignments.each { ventId, roomId ->
    String room = roomId == null ? null : roomId.toString().trim()
    if (found == null && room && !knownRooms.contains(roomId)) {
      found = ventId.toString()
    }
  }
  return found
}

// Validate a topology against the supported ranges (R4.1) and membership rules.
// Returns [valid, offendingEntry, reason, excludedUnassignedVents]; the offending
// entry is indicated so the caller can reject and preserve last-valid (R4.3).
Map validateDabV2Topology(Map topology) {
  Map t = topology ?: [:]
  List roomIds = t.roomIds == null ? null : (t.roomIds as List)
  int rooms = roomIds == null ? ((t.rooms ?: 0) as int) : roomIds.size()
  Map assignments = (t.ventRoomAssignments ?: [:]) as Map
  int smartVents = assignments ? assignments.size() : ((t.smartVents ?: 0) as int)
  int conventional = (t.conventionalVents ?: 0) as int
  int thermostats = (t.thermostats ?: 0) as int

  if (rooms < DABV2_MIN_ROOMS || rooms > DABV2_MAX_ROOMS) {
    return dabV2TopologyError('rooms', dabV2RangeMsg('room', rooms, DABV2_MIN_ROOMS, DABV2_MAX_ROOMS))
  }
  if (smartVents < DABV2_MIN_SMART_VENTS || smartVents > DABV2_MAX_SMART_VENTS) {
    return dabV2TopologyError('smartVents', dabV2RangeMsg('smart vent', smartVents, DABV2_MIN_SMART_VENTS, DABV2_MAX_SMART_VENTS))
  }
  if (conventional < DABV2_MIN_CONVENTIONAL_VENTS || conventional > DABV2_MAX_CONVENTIONAL_VENTS) {
    return dabV2TopologyError('conventionalVents', dabV2RangeMsg('conventional vent', conventional, DABV2_MIN_CONVENTIONAL_VENTS, DABV2_MAX_CONVENTIONAL_VENTS))
  }
  if (thermostats < DABV2_MIN_THERMOSTATS || thermostats > DABV2_MAX_THERMOSTATS) {
    return dabV2TopologyError('thermostats', dabV2RangeMsg('thermostat', thermostats, DABV2_MIN_THERMOSTATS, DABV2_MAX_THERMOSTATS))
  }
  Set knownRooms = roomIds == null ? null : (roomIds as Set)
  String badVent = dabV2FirstVentWithUnknownRoom(assignments, knownRooms)
  if (badVent) {
    return dabV2TopologyError("vent:${badVent}", "vent ${badVent} is assigned to a non-existent room")
  }
  return [valid: true, offendingEntry: null, reason: null,
          excludedUnassignedVents: dabV2UnassignedVentIds(assignments)]
}

// Validate and commit a topology. On success the topology becomes the last-valid
// configuration; on failure the last-valid configuration is preserved and the
// offending entry is logged (R4.3).
Map applyDabV2Topology(Map topology) {
  Map result = validateDabV2Topology(topology)
  if (result.valid) {
    state.dabv2LastValidTopology = topology
    return result
  }
  logError "Invalid DAB v2 topology (${result.offendingEntry}): ${result.reason}; preserving last valid configuration"
  return result + [lastValidTopology: state?.dabv2LastValidTopology]
}

// A thermostat with zero smart vents balances nothing (R4.8) — no error.
boolean isDabV2ThermostatBalancingNoOp(rawSmartVentCount) {
  int count = (rawSmartVentCount ?: 0) as int
  return count <= 0
}

// Per-thermostat plan: zero smart vents is a balancing no-op, but the thermostat
// still contributes its conventional vents to combined-flow reporting (R4.8).
Map dabV2ThermostatPlan(rawSmartVentCount, rawConventionalCount) {
  int smart = (rawSmartVentCount ?: 0) as int
  return [
    balancingNoOp        : smart <= 0,
    countsForCombinedFlow: true,
    conventionalVents    : clampInt(rawConventionalCount, DABV2_MIN_CONVENTIONAL_VENTS, DABV2_MAX_CONVENTIONAL_VENTS, CONVENTIONAL_VENTS_DEFAULT)
  ]
}

// Build the current topology from the live room-vent mapping + settings. No
// device identity is hard-coded (R4.2); membership is read from configuration.
Map deriveDabV2Topology() {
  Map ventsByRoom = (atomicState?.ventsByRoomId ?: [:]) as Map
  Map assignments = [:]
  Set roomIds = [] as Set
  ventsByRoom.each { roomId, ventIds ->
    roomIds << roomId
    (ventIds ?: []).each { ventId -> assignments[ventId] = roomId }
  }
  return [
    roomIds            : (roomIds as List),
    ventRoomAssignments: assignments,
    conventionalVents  : getDabV2ConventionalVentCount(),
    thermostats        : settings?.thermostat1 ? 1 : 0
  ]
}

private boolean isFahrenheitBoundary() {
  return settings?.thermostat1TempUnit == '2'
}

// Convert an inbound boundary temperature to the internal Celsius unit (R19.3).
BigDecimal dabV2BoundaryToCelsius(rawValue) {
  if (rawValue == null) {
    return null
  }
  BigDecimal value = rawValue as BigDecimal
  return isFahrenheitBoundary() ? convertFahrenheitToCentigrade(value) : value
}

// Convert an internal Celsius temperature back to the boundary display unit.
BigDecimal dabV2CelsiusToBoundary(rawValue) {
  if (rawValue == null) {
    return null
  }
  BigDecimal value = rawValue as BigDecimal
  return isFahrenheitBoundary() ? convertCentigradeToFahrenheit(value) : value
}

// ------------------------------
// DAB v2 `balance` evaluate loop (Task 9.2; R4.4/4.5/4.6, R5.4, R8.6/8.7/8.8,
// R16.4, R21.2, R18.7)
//
// Thin orchestration over the PURE modules — the app gathers device state into
// the pure-input POGOs, resolves the conditioning mode, then chains
// Context_Mapper -> Learning_Model -> Allocator -> group-normalize ->
// Safety_Floor (the single airflow-floor choke point). No control math lives
// here: the inline per-vent sizing now lives in `libraries/dabv2-*.groovy`
// (R18.7); this loop only wires it together. The legacy DAB path
// (`initializeRoomStates`) is retained as a selectable fallback (R20.3) and is
// unchanged; strategy selection between the two is wired in task 10.1, so the
// balance loop below has no externally observable effect until then.
// ------------------------------

// Resolve Hubitat's `thermostatOperatingState` to the DAB v2 conditioning mode
// (R4.4). `heating`/`pending heat` -> heating; `cooling`/`pending cool` ->
// cooling; everything else (`idle`, `fan only`, `vent economizer`, unknown,
// null) -> idle (no balancing commands except the pre-adjust path [task 9.5]
// and any move strictly required to reach the floor). Already-resolved values
// (`heating`/`cooling`/`idle`) pass through unchanged so the loop can hand its
// own action back in.
String resolveDabV2HvacAction(rawOperatingState) {
  String s = (rawOperatingState == null ? '' : rawOperatingState.toString()).trim().toLowerCase()
  switch (s) {
    case 'heating':
    case 'pending heat':
    case PENDING_HEAT:
      return HEATING
    case 'cooling':
    case 'pending cool':
    case PENDING_COOL:
      return COOLING
    default:
      return DABV2_ACTION_IDLE
  }
}

// Whether a resolved action drives a balancing allocation (heating or cooling).
// Idle / fan-only issue no balancing commands (R10.6).
boolean isDabV2BalancingAction(String action) {
  return action == HEATING || action == COOLING
}

// === DAB v2 adaptive evaluation cadence (Task 9.6; R22) ===
//
// Exactly ONE self-managed evaluate job per app. The job (`dabV2EvaluateTick`)
// runs the evaluate work and then RE-ARMS ITSELF through a same-named `runIn`,
// whose reschedule overwrites the prior pending job — so there is never more than
// one pending cadence job (design "one self-managed job"; R22.3). The interval is
// chosen adaptively: the configured active interval (default 3 min) while ANY
// thermostat reports heating/cooling, the idle interval (default 10 min)
// otherwise (R22.1/R22.2). Both intervals are already clamped to the
// scheduler/rate-limit-safe ranges by the 9.1 getters (R22.4). A
// `thermostatOperatingState` transition re-arms the single job at the new
// interval, switching cadence (R22.3).

// Whether any of the supplied raw thermostatOperatingState values represents an
// active conditioning state (heating/cooling, incl. pending). The states are
// passed in — never read from the platform here — so this stays unit-testable.
boolean dabV2AnyThermostatActive(Collection rawStates) {
  if (!rawStates) { return false }
  return rawStates.any { isDabV2BalancingAction(resolveDabV2HvacAction(it)) }
}

// The clamped cadence interval (minutes): the active interval while
// conditioning, the idle interval otherwise (R22.1/R22.2). Both getters clamp to
// the scheduler/rate-limit-safe ranges (R22.4).
Integer dabV2CadenceIntervalMin(boolean active) {
  return active ? getDabV2ActiveIntervalMin() : getDabV2IdleIntervalMin()
}

// The live raw thermostatOperatingState value(s) across the configured
// thermostat(s). Isolated so the cadence helpers above stay platform-light and
// directly unit-testable.
private List currentThermostatOperatingStates() {
  List states = []
  def t = settings?.thermostat1
  if (t != null) { states << t.currentValue('thermostatOperatingState') }
  return states
}

// (Re)arm the single self-managed evaluate job at the cadence implied by
// `active`. The same-named `runIn` reschedule OVERWRITES any pending tick, so
// exactly one cadence job is ever scheduled (R22.3). The chosen interval is the
// clamped active/idle minutes converted to seconds (R22.4). The resolved cadence
// is recorded so callers can observe the current state without re-deriving it.
void scheduleDabV2EvaluateCadence(boolean active) {
  Integer intervalMin = dabV2CadenceIntervalMin(active)
  // overwrite:true (the platform default) makes the single-job intent explicit:
  // a same-named reschedule replaces the prior pending tick rather than adding one.
  runIn((intervalMin * 60) as Integer, 'dabV2EvaluateTick', [overwrite: true])
  if (atomicState != null) {
    atomicState.dabV2Cadence = [active: active, intervalMin: intervalMin]
  }
  log "DAB v2 cadence ${active ? 'active' : 'idle'}: evaluate every ${intervalMin} min", 3
}

// Re-arm the cadence from the live thermostat state(s): active iff some
// thermostat is heating/cooling (R22.1/R22.2). Used by the self-managed tick to
// reschedule itself and by the operating-state subscription to switch cadence on
// a transition (R22.3).
void rescheduleDabV2EvaluateCadence() {
  scheduleDabV2EvaluateCadence(dabV2AnyThermostatActive(currentThermostatOperatingStates()))
}

// The single self-managed cadence job (R22). RE-ARMS itself FIRST — so a
// failure in the evaluate work can never strand the cadence (the next tick is
// already pending) — then runs the evaluate work. The same-named runIn overwrite
// keeps this to exactly one pending job.
def dabV2EvaluateTick() {
  rescheduleDabV2EvaluateCadence()
  selectAndRunDabV2Evaluate()
}

// Strategy selection for the periodic evaluate loop (Task 10.1; R5.6, R20.3,
// R20.4). The registered control strategy decides which target-computation path
// runs: `balance` runs the DAB v2 synchronized-convergence pipeline (gather ->
// evaluateDabV2Zone -> Safety_Floor -> dispatchDabV2Targets), `dab` runs the
// retained legacy rebalance path. BOTH are selectable. The code-review fixes
// (Task 2) live in the SHARED sizing/dispatch helpers used by each path
// (overshoot-close in calculateOpenPercentageForAllVents and in the pure
// Allocator; anti-chatter via shouldApplyVentMove; the single Safety_Floor choke
// point), so those fixes apply under EITHER strategy (R20.4). The resolved
// strategy is recorded so the selection is observable without re-deriving it.
def selectAndRunDabV2Evaluate() {
  String strategy = getDabV2ControlStrategy()
  if (atomicState != null) { atomicState.dabV2EvaluateStrategy = strategy }
  if (strategy == STRATEGY_BALANCE) {
    runDabV2BalanceEvaluate()
  } else {
    evaluateRebalancingVents()
  }
}

// Production `balance`-strategy evaluate (Task 10.1; R5.6). Gathers the
// thermostat zone's per-room state from the child vents, runs the PURE balance
// pipeline via `evaluateDabV2Zone` under the single-flight re-entrancy guard
// (R21.7), persists the resolved cycle anchor, and dispatches the floor-safe
// targets through the shared async dispatch path. The zone is scoped to the
// configured thermostat (per-thermostat scoping, R4/R6/R7). A missing topology
// (no managed rooms) is a safe no-op, never an error (R4.8). Wrapped in
// try/catch so a transient evaluate failure can never strand the cadence tick
// that called it.
def runDabV2BalanceEvaluate() {
  if (!settings.dabEnabled) { return }
  def ventsByRoomId = atomicState?.ventsByRoomId
  if (!ventsByRoomId) { return }
  try {
    def action = settings?.thermostat1?.currentValue('thermostatOperatingState')
    String mode = resolveDabV2HvacAction(action)
    // Setpoint is always read in the active conditioning direction; idle/fan
    // zones still need it for the bounded pre-adjust trigger (R10.8).
    String setpointMode = (mode == HEATING) ? HEATING : COOLING
    BigDecimal setpointC = getThermostatSetpoint(setpointMode)
    if (setpointC == null) { return }

    List roomData = gatherDabV2ZoneRoomData(ventsByRoomId, mode)
    Map prevCycle = (atomicState?.dabV2Cycle in Map) ? (Map) atomicState.dabV2Cycle : null

    Map ctxInputs = buildDabV2ContextInputs()
    return withDabV2EvalGuard(atomicState?.cycleSeq) {
      Map zoneResult = evaluateDabV2Zone(roomData, setpointC, action, null, ctxInputs, prevCycle)
      if (atomicState != null) {
        atomicState.cycleSeq = zoneResult.cycleId
        atomicState.dabV2Cycle = [mode: zoneResult.mode, cycleId: zoneResult.cycleId,
            lastActiveMode: zoneResult.lastActiveMode, anchorTargets: zoneResult.anchorTargets,
            idleSinceMs: zoneResult.idleSinceMs, predictedSpreadC: zoneResult.predictedSpreadC]
      }
      dispatchDabV2Targets(zoneResult, roomData)
      return zoneResult
    }
  } catch (err) {
    logError err
  }
}

// Build the PURE per-room input maps for the `balance` pipeline from the managed
// child vents (Task 10.1). One entry PER ROOM (R15 grouping): all vents in a
// room share the room-level temperature / rate / active attributes, so the first
// readable vent supplies them and every vent id is carried in `ventIds`.
// Temperatures and setpoints are Celsius at this layer (the device boundary
// already converted, R19.3). Defensive per room so one bad device never aborts
// the zone gather.
List gatherDabV2ZoneRoomData(ventsByRoomId, String mode) {
  List roomData = []
  if (!ventsByRoomId) { return roomData }
  boolean heating = (mode == HEATING)
  ventsByRoomId.each { roomId, ventIds ->
    try {
      List ids = (ventIds in List) ? (List) ventIds : [ventIds]
      def vent = null
      for (vid in ids) {
        def cand = getChildDevice(vid)
        if (cand != null) { vent = cand; break }
      }
      if (vent == null) { return }
      BigDecimal tempC = getRoomTemp(vent)
      def rate = heating ? vent.currentValue('room-heating-rate') : vent.currentValue('room-cooling-rate')
      boolean active = vent.currentValue('room-active') == 'true'
      def currentOpen = vent.currentValue('percent-open') ?: 0
      roomData << [roomId      : (roomId == null ? null : roomId.toString()),
                   tempC       : tempC,
                   active      : active,
                   coolingRate : heating ? null : rate,
                   heatingRate : heating ? rate : null,
                   currentOpen : currentOpen,
                   ventIds     : ids,
                   // Per-room context (R11.6): an open door / occupancy only
                   // affects this room. doorsOpen comes from the room's mapped
                   // contact sensor (null => fall back to the whole-home sensor);
                   // occupied comes from the Flair puck's room-occupied attribute
                   // (null => fall back to the whole-home occupancy source).
                   doorsOpen   : dabV2RoomDoorsOpen(roomId),
                   occupied    : dabV2RoomOccupied(vent)]
    } catch (err) {
      logError err
    }
  }
  return roomData
}

// Resolve a room's own door state from its mapped contact sensor (R11.6).
// Returns true/false when a per-room sensor is configured and readable, or null
// (defer to the whole-home fallback) when no per-room sensor is mapped.
private Boolean dabV2RoomDoorsOpen(roomId) {
  if (roomId == null) { return null }
  def sensor = settings["roomDoorSensor_${roomId}"]
  if (sensor == null) { return null }
  def contact = sensor.currentValue('contact')
  return contact == null ? null : (contact == 'open')
}

// Resolve a room's occupancy from the Flair puck's room-occupied attribute,
// tri-state: null when the attribute is absent (defer to the fallback).
private Boolean dabV2RoomOccupied(vent) {
  def raw = vent?.currentValue('room-occupied')
  if (raw == null) { return null }
  return raw == true || raw == 'true' || raw == 'present'
}

// Discovered rooms (id + display name) for the per-room door-sensor UI. Keyed by
// the same room ids the evaluate loop uses (atomicState.ventsByRoomId), with the
// room name read from the first readable vent in each room.
private List dabV2RoomList() {
  def byRoom = atomicState?.ventsByRoomId
  if (!(byRoom instanceof Map)) { return [] }
  List out = []
  byRoom.each { roomId, ventIds ->
    if (roomId == null) { return }
    List ids = (ventIds in List) ? (List) ventIds : [ventIds]
    String name = roomId.toString()
    for (vid in ids) {
      def v = getChildDevice(vid)
      if (v != null) { name = (v.currentValue('room-name') ?: name).toString(); break }
    }
    out << [id: roomId.toString(), name: name]
  }
  return out.sort { it.name }
}

// Whole-home ambient context inputs (R11.5/11.6) read from the optional global
// sensors; per-room door/occupancy overrides are merged later, per room, in
// gatherDabV2RoomInputs. Only the occupancy/door fallbacks are populated here
// (the only signals the app's context multiplier consumes today); hour/outdoor/
// sun remain unset until the regime-aware learning path consumes them.
private Map buildDabV2ContextInputs() {
  Map ci = [:]
  def occ = settings?.occupancySource?.currentValue('presence')
  if (occ != null) { ci.occupied = (occ == 'present') }
  def door = settings?.doorSensor?.currentValue('contact')
  if (door != null) { ci.doorsOpen = (door == 'open') }
  return ci
}

// A usable numeric temperature? Missing (null), non-numeric, or non-finite
// (NaN / Infinity) readings are NOT usable and cause the room to be excluded
// from allocation/spread (R21.2) rather than crashing the loop.
private boolean isDabV2UsableNumber(rawValue) {
  if (rawValue == null) { return false }
  String s = rawValue.toString().trim()
  if (s == 'NaN' || s == 'Infinity' || s == '-Infinity') { return false }
  return s.isNumber()
}

// === DAB v2 cycle lifecycle + re-entrancy / stale-cycle guard (Task 9.4; R21.7, R10.7) ===
//
// Hubitat `runIn`/`schedule` callbacks can interleave with in-flight `asynchttp*`
// callbacks, so the evaluate->allocate->dispatch path must be guarded so that
// concurrent executions cannot corrupt cycle/learned state, issue conflicting
// vent commands, or let an old cycle's delayed callback clobber a newer cycle.
// The allocation math itself is pure/stateless and inherently concurrency-safe;
// only these surrounding state mutations need guarding.

// Single-flight evaluation guard (R21.7). Try to acquire the guard, returning
// true iff the caller may proceed. A re-entrant call while a NON-stale guard is
// held is denied (returns false) so the caller returns immediately. A stale
// guard (held longer than EVAL_GUARD_TTL_MS — e.g. a crashed prior run) is
// reclaimed so the path can never wedge. `atomicState` (not `state`) is used
// because it is read/written immediately during execution, which is what makes
// the guard effective across overlapping callbacks.
boolean acquireDabV2EvalGuard(Long nowMs = now(), cycleId = null) {
  def g = atomicState?.evalInFlight
  if (g in Map && g.ts != null) {
    Long age = (nowMs as Long) - (g.ts as Long)
    if (age >= 0 && age < EVAL_GUARD_TTL_MS) {
      return false   // another evaluation is in flight and not stale
    }
  }
  if (atomicState != null) {
    atomicState.evalInFlight = [ts: (nowMs as Long), cycleId: cycleId]
  }
  return true
}

// Release the single-flight evaluation guard. Safe to call unconditionally.
void releaseDabV2EvalGuard() {
  if (atomicState != null) { atomicState.remove('evalInFlight') }
}

// Run `body` under the single-flight evaluation guard. If another evaluation is
// already in flight (and not stale), the body is NOT run and `[skipped:true]` is
// returned. The guard is always released after the body completes.
def withDabV2EvalGuard(cycleId, Closure body) {
  Long nowMs = now()
  if (!acquireDabV2EvalGuard(nowMs, cycleId)) {
    log "evaluate re-entrancy guard: skipping overlapping invocation (cycle ${cycleId})", 2
    return [skipped: true]
  }
  try {
    return body.call()
  } finally {
    releaseDabV2EvalGuard()
  }
}

// Cycle-identity check (R21.7, builds on 2.4's cycleSeq). True iff `cycleId` is
// still the current cycle. Any callback that mutates cycle/learned state or
// issues vent commands calls this FIRST and drops itself if the cycle has been
// superseded by a newer one (a stale finalize/dispatch from a finished cycle
// must never clear or fight the newer cycle's state). A null id (no cycle was
// stamped) or an unknown current cycle is treated as current (not gated).
boolean dabV2IsCurrentCycle(cycleId) {
  if (cycleId == null) { return true }
  def cur = atomicState?.cycleSeq
  if (cur == null) { return true }
  try {
    return (cycleId as Long) == (cur as Long)
  } catch (ignored) {
    return true
  }
}

// Compose the idempotent dispatch key (cycleId, ventId, target) (R21.7). A
// repeated dispatch for the same key within a cycle is a no-op; a genuinely new
// target (different value) or a new cycle produces a different key and so still
// dispatches.
String dabV2DispatchKey(cycleId, ventId, target) {
  return "${cycleId == null ? 'na' : cycleId}|${ventId}|${target}"
}

// Short-cycle reuse decision (R10.7). PURE: time and the threshold are supplied
// by the caller. Returns true iff a re-activation should REUSE the prior cycle's
// anchored allocation rather than recomputing from scratch. Reuse requires that
// the prior cycle went idle (idleSinceMs stamped), the re-activation is in the
// SAME conditioning mode (a flip is a new anchor per R8.8), prior anchored
// targets exist, and the idle gap is in [0, shortCycleGapMin) minutes. A
// threshold of 0 disables reuse.
boolean dabV2IsShortCycleReuse(Map prevCycle, String newMode, Long nowMs, int shortCycleGapMin) {
  if (shortCycleGapMin <= 0 || prevCycle == null || nowMs == null) { return false }
  if (prevCycle.idleSinceMs == null) { return false }
  String lastMode = prevCycle.lastActiveMode
  if (lastMode == null || newMode == null || lastMode != newMode) { return false }
  Map anchor = (prevCycle.anchorTargets in Map) ? (Map) prevCycle.anchorTargets : null
  if (anchor == null || anchor.isEmpty()) { return false }
  long gapMs = (nowMs as Long) - (prevCycle.idleSinceMs as Long)
  long thresholdMs = (shortCycleGapMin as long) * 60_000L
  return gapMs >= 0 && gapMs < thresholdMs
}

// Resolve the cycle anchor for a balancing evaluation (R8.8). A fresh start (no
// previous mode) and a conditioning-mode flip (cooling<->heating) each begin a
// NEW cycle anchor with a bumped monotonic id; an unchanged mode keeps the
// prior anchor. Returns [mode, cycleId, newAnchor].
Map resolveDabV2CycleAnchor(prevMode, String newMode, prevCycleId) {
  long prevId = (prevCycleId ?: 0L) as long
  boolean fresh = prevMode == null
  boolean flip = prevMode != null && newMode != null && prevMode != newMode
  boolean newAnchor = fresh || flip
  long cycleId = newAnchor ? prevId + 1L : prevId
  return [mode: newMode, cycleId: cycleId, newAnchor: newAnchor]
}

// Gather the per-room PURE allocation inputs for ONE thermostat zone from
// gathered room-state maps. Internal units are Celsius (R19.3): temperatures
// are expected already converted at the device boundary. Rooms whose
// temperature is missing/unavailable are EXCLUDED (never added) so they enter
// neither allocation nor the spread objective and never crash the loop (R21.2).
// `mode` selects the per-room heat/cool rate; the optional `ctx` applies the
// Context_Mapper's bounded secondary multipliers to the learned rate.
List gatherDabV2RoomInputs(List roomData, String mode, BigDecimal setpointC, ctx = null) {
  List inputs = []
  if (!roomData) { return inputs }
  boolean heating = (mode == HEATING)
  BigDecimal sp = (setpointC ?: 0) as BigDecimal
  roomData.each { rd ->
    if (rd == null) { return }
    if (!isDabV2UsableNumber(rd.tempC)) { return }   // R21.2 — exclude, no crash
    BigDecimal roomTempC = rd.tempC as BigDecimal
    String rid = rd.roomId == null ? null : rd.roomId.toString()
    def effRate = clampLearnedRate(heating ? rd.heatingRate : rd.coolingRate)
    Map roomCtx = dabV2RoomContext(ctx, rd)
    if (roomCtx != null) {
      effRate = ctxApplyMultipliers(effRate, roomCtx, mode)
    }
    def leakFraction = isDabV2UsableNumber(rd.leak) ?
      (rd.leak as BigDecimal) : LRN_LEAK_DEFAULT
    inputs << [
      roomId: rid,
      tempC: roomTempC,
      active: coerceBoolean(rd.active, false),
      efficiency: effRate,
      leak: leakFraction,
      currentOpen: isDabV2UsableNumber(rd.currentOpen) ? (rd.currentOpen as BigDecimal) : 0,
      ventIds: (rd.ventIds ?: [rid]),
      // Signed error to setpoint: > 0 needs conditioning, <= 0 satisfied (R6.4 bias).
      signedErrorC: heating ? (sp - roomTempC) : (roomTempC - sp),
      curve: lrnSeedLinear(leakFraction)]
  }
  return inputs
}

// Resolve the PURE AllocSettings from the validated config plus the zone's
// inactive-vent airflow contribution (R6.7). granularity comes from the
// existing vent-granularity setting so balance rounds on the same grid as the
// legacy path.
def dabV2AllocSettings(Map config, Map inactive) {
  Map cfg = config ?: getDabV2Config()
  int gran = settings?.ventGranularity ? settings.ventGranularity.toInteger() : 5
  return dabv2NewAllocSettings([
    safetyFloorPct: (cfg.safetyFloorPct ?: SAFETY_FLOOR_DEFAULT),
    conventionalVents: (cfg.conventionalVents ?: 0) as int,
    conventionalOpenPct: (cfg.conventionalOpenPct ?: CONVENTIONAL_OPEN_DEFAULT),
    inactiveOpenPctSum: (inactive?.sum ?: 0),
    inactiveCount: (inactive?.count ?: 0) as int,
    granularity: gran,
    crosscoupling: coerceBoolean(cfg.crosscouplingEnabled, true),
    airflowLimitedMarginPct: (cfg.airflowLimitedMarginPct ?: AIRFLOW_LIMITED_MARGIN_DEFAULT),
    airflowLimitedErrorC: (cfg.airflowLimitedErrorC ?: AIRFLOW_LIMITED_ERROR_DEFAULT),
    spreadGuardrailC: (cfg.spreadGuardrailC ?: SPREAD_GUARDRAIL_DEFAULT),
    spreadImprovementDeadbandC: (cfg.spreadImprovementDeadbandC ?: SPREAD_DEADBAND_DEFAULT)])
}

// Group-normalize per-room targets (R15): round each room's commanded aperture
// to the configured granularity so every physical vent in the room-group is
// commanded the same value (independent rounding can never split a group).
Map dabV2GroupNormalize(Map perRoomTargets, int granularity) {
  Map out = [:]
  int g = granularity > 0 ? granularity : 5
  (perRoomTargets ?: [:]).each { roomId, pct ->
    BigDecimal v = (pct == null ? 0 : pct) as BigDecimal
    int rounded = (Math.round(v / g) as int) * g
    if (rounded < 0) { rounded = 0 }
    if (rounded > 100) { rounded = 100 }
    out[roomId] = Double.valueOf(rounded)
  }
  return out
}

// Combined open % over the zone's per-room targets, expanded to one entry per
// physical vent so each vent counts individually in the floor math (R6.1/R15.4).
// Mirrors the Safety_Floor's own expansion: an inactive room counts as a device
// only while actually held open (R6.7).
def dabV2CombinedOpenPct(Map perRoomTargets, List rooms, settings) {
  Map roomById = [:]
  (rooms ?: []).each { r -> if (r != null && r.roomId != null) { roomById[r.roomId] = r } }
  Map perVent = [:]
  (perRoomTargets ?: [:]).each { roomId, pct ->
    def r = roomById[roomId]
    BigDecimal v = (pct == null ? 0 : pct) as BigDecimal
    if (r == null) {
      perVent[roomId] = v.doubleValue()
      return
    }
    if (!r.active && v <= 0) { return }   // closed inactive damper is not a device
    def vents = r.ventIds
    if (vents) {
      vents.each { vid -> perVent[vid] = v.doubleValue() }
    } else {
      perVent[roomId] = v.doubleValue()
    }
  }
  return sfCombinedOpenPct(perVent, settings)
}

// Merge a room's per-room door/occupancy over the ambient context (R11.6) so an
// open door / occupancy only biases ITS room's rate. Returns null only when
// there is no context at all (no ambient and no per-room signal), so a room with
// its own sensor is honored even when the whole-home context is empty.
private Map dabV2RoomContext(ambient, Map rd) {
  Boolean roomOcc = (rd != null && rd.occupied != null) ? (rd.occupied as Boolean) : null
  Boolean roomDoor = (rd != null && rd.doorsOpen != null) ? (rd.doorsOpen as Boolean) : null
  Map a = (ambient instanceof Map) ? (Map) ambient : null
  if (a == null && roomOcc == null && roomDoor == null) { return null }
  if (a == null) { a = [:] }
  return [
    hour       : (a.hour ?: 0),
    isDaytime  : (a.isDaytime ?: false),
    outdoorBand: (a.containsKey('outdoorBand') ? a.outdoorBand : 1),
    occupied   : (roomOcc != null ? roomOcc : a.occupied),
    doorsOpen  : (roomDoor != null ? roomDoor : a.doorsOpen)
  ]
}

// Resolve the ambient Context from already-resolved primitives (R11.5/11.6).
private dabV2ContextFrom(Map ctxInputs) {
  Map ci = ctxInputs ?: [:]
  return ctxBuild(
    (ci.hour ?: 0) as int,
    isDabV2UsableNumber(ci.outdoorTempC) ? (ci.outdoorTempC as BigDecimal).doubleValue() : null,
    (ci.containsKey('occupied') ? (ci.occupied as Boolean) : null),
    (ci.containsKey('doorsOpen') ? (ci.doorsOpen as Boolean) : null),
    (ci.sunState == null ? null : ci.sunState.toString()))
}

// Inactive-vent airflow contribution toward the floor (R6.7): only inactive
// rooms CURRENTLY held open count, weighted by their vent count.
private Map dabV2InactiveAirflow(List rooms) {
  BigDecimal sum = 0
  int count = 0
  (rooms ?: []).each { r ->
    if (!r.active && r.currentOpen > 0) {
      int vents = (r.ventIds ? r.ventIds.size() : 1)
      sum += (r.currentOpen as BigDecimal) * vents
      count += vents
    }
  }
  return [sum: sum, count: count]
}

// Inactive rooms to auto-close this evaluation, honoring the user's setting
// (R16.4). Empty when auto-close is off — inactive rooms are then left untouched
// (never auto-closed/repositioned by balancing, R16.1).
private List dabV2InactiveClosed(List roomData, boolean closeInactive) {
  List closed = []
  if (!closeInactive) { return closed }
  (roomData ?: []).each { rd ->
    if (rd != null && !coerceBoolean(rd.active, false)) {
      closed << (rd.roomId == null ? null : rd.roomId.toString())
    }
  }
  return closed
}

// Result for an idle / fan-only zone — no balancing commands issued (R10.6).
// Carries forward the cycle identity, the last ACTIVE conditioning mode, and the
// prior anchored targets so a short-cycle re-activation can reuse the anchor
// (R10.7). `idleSinceMs` is stamped the FIRST time the zone is observed idle
// after an active cycle and then preserved, so the idle gap can grow.
private Map dabV2IdleZoneResult(Map prevCycle, Long nowMs = null) {
  String lastActiveMode = prevCycle?.lastActiveMode ?: prevCycle?.mode
  Long idleSince
  if (prevCycle?.idleSinceMs != null) {
    idleSince = prevCycle.idleSinceMs as Long
  } else if (lastActiveMode != null) {
    idleSince = (nowMs != null ? nowMs : now()) as Long
  } else {
    idleSince = null
  }
  Map anchor = (prevCycle?.anchorTargets in Map) ? (Map) prevCycle.anchorTargets
             : (prevCycle?.targets in Map ? (Map) prevCycle.targets : null)
  return [balancing: false, action: DABV2_ACTION_IDLE, mode: null, targets: [:],
          floorBinding: false, airflowLimited: ([] as Set), predictedSpreadC: 0,
          combinedOpenPct: 0, cycleId: (prevCycle?.cycleId ?: 0L), newAnchor: false,
          reusedAnchor: false, lastActiveMode: lastActiveMode, idleSinceMs: idleSince,
          anchorTargets: anchor,
          floorRequiredRooms: ([] as Set),
          inactiveClosed: []]
}

// === DAB v2 pre-adjust path (Task 9.5; R10.6, R10.8) ===
//
// While idle/fan, the app may pre-position vents toward the NEXT cycle's
// predicted allocation, but only within two bounds so brief fan->idle->active
// bounces never cause moves (R10.8):
//   - Dwell gate: the zone has been idle at least `preAdjustDwellMin`.
//   - Trigger gate: at least one active room is within `preAdjustTriggerC` of the
//     predicted activation trigger (the setpoint, in the last active direction).
// The pre-adjust REUSES the anchored allocation (no separate control law) and
// never closes combined airflow below the Safety_Floor. When the gates are NOT
// met, the only command permitted while idle is a move strictly required to
// reach the floor (R10.6); otherwise the idle zone is left untouched.

// Dwell gate (R10.8): true once the zone has been continuously idle for at least
// `dwellMin` minutes. Requires the idle-bookkeeping timestamp the idle path
// stamps when a zone first goes idle after an active cycle.
boolean dabV2PreAdjustDwellMet(Map prevCycle, Long nowMs, int dwellMin) {
  if (prevCycle == null || nowMs == null) { return false }
  if (prevCycle.idleSinceMs == null) { return false }
  Long idleSince = prevCycle.idleSinceMs as Long
  Long needed = (dwellMin <= 0 ? 0L : (dwellMin as long) * 60_000L)
  return (nowMs - idleSince) >= needed
}

// Trigger gate (R10.8): true when at least one ACTIVE room with a usable
// temperature has drifted back within `triggerC` of the predicted activation
// trigger. The trigger is the setpoint in the last active conditioning
// direction: while cooling, the room re-activates as it warms back toward the
// setpoint (temp >= setpoint - triggerC); while heating, as it cools back toward
// the setpoint (temp <= setpoint + triggerC).
boolean dabV2WithinPreAdjustTrigger(List roomData, BigDecimal setpointC, String mode, BigDecimal triggerC) {
  if (!roomData) { return false }
  BigDecimal sp = (setpointC ?: 0) as BigDecimal
  BigDecimal tol = (triggerC ?: 0) as BigDecimal
  boolean heating = (mode == HEATING)
  return roomData.any { rd ->
    if (rd == null || !coerceBoolean(rd.active, false)) { return false }
    if (!isDabV2UsableNumber(rd.tempC)) { return false }
    BigDecimal t = rd.tempC as BigDecimal
    return heating ? (t <= sp + tol) : (t >= sp - tol)
  }
}

// Combined pre-adjust gate (R10.8): the prior cycle must have an active
// conditioning mode AND an anchored allocation to pre-position toward, and BOTH
// the dwell and trigger gates must be met.
boolean dabV2ShouldPreAdjust(Map prevCycle, List roomData, BigDecimal setpointC, Map cfg, Long nowMs) {
  if (prevCycle == null) { return false }
  String mode = prevCycle.lastActiveMode ?: prevCycle.mode
  if (mode != HEATING && mode != COOLING) { return false }
  if (!(prevCycle.anchorTargets in Map) || ((Map) prevCycle.anchorTargets).isEmpty()) { return false }
  Map c = cfg ?: getDabV2Config()
  int dwellMin = (c?.preAdjustDwellMin != null ? c.preAdjustDwellMin : PRE_ADJUST_DWELL_DEFAULT) as int
  BigDecimal triggerC = (c?.preAdjustTriggerC != null ? c.preAdjustTriggerC : PRE_ADJUST_TRIGGER_DEFAULT) as BigDecimal
  if (!dabV2PreAdjustDwellMet(prevCycle, nowMs, dwellMin)) { return false }
  return dabV2WithinPreAdjustTrigger(roomData, setpointC, mode, triggerC)
}

// Dispatch the idle/fan evaluation: pre-adjust if the gates are met (R10.8),
// else a floor-required-only correction if the current apertures violate the
// floor (R10.6), else the untouched idle result.
private Map dabV2IdleOrPreAdjustResult(Map prevCycle, List roomData,
    BigDecimal setpointC, Map cfg, Map ctxInputs, Long nowMs) {
  Map idleResult = dabV2IdleZoneResult(prevCycle, nowMs)
  if (dabV2ShouldPreAdjust(prevCycle, roomData, setpointC, cfg, nowMs)) {
    String mode = prevCycle.lastActiveMode ?: prevCycle.mode
    return dabV2PreAdjustResult(prevCycle, mode, roomData, setpointC, cfg, ctxInputs, idleResult)
  }
  Map floorOnly = dabV2IdleFloorRequiredResult(roomData, setpointC,
      (prevCycle?.lastActiveMode ?: prevCycle?.mode), cfg, ctxInputs, idleResult)
  return floorOnly != null ? floorOnly : idleResult
}

// Pre-adjust result (R10.8). REUSES the prior cycle's ANCHORED targets — the
// allocator is NOT re-run ("does not run a separate control law") — and re-runs
// the Safety_Floor so combined airflow can never be pre-positioned below the
// floor (R10.6). The cycle identity and idle bookkeeping are preserved: a
// pre-adjust is NOT a new active cycle.
private Map dabV2PreAdjustResult(Map prevCycle, String mode, List roomData,
    BigDecimal setpointC, Map cfg, Map ctxInputs, Map idleResult) {
  Map anchorTargets = (prevCycle?.anchorTargets in Map) ? (Map) prevCycle.anchorTargets : [:]
  def ctx = dabV2ContextFrom(ctxInputs)
  List rooms = gatherDabV2RoomInputs(roomData, mode, setpointC, ctx)
  def settings = dabV2AllocSettings(cfg, dabV2InactiveAirflow(rooms))
  def floored = sfApply(anchorTargets, rooms, settings)
  Map safeTargets = floored[0] as Map
  Set floorRequiredRooms = computeFloorRequiredVentIds(anchorTargets, safeTargets)
  return idleResult + [balancing: true, preAdjust: true, mode: mode,
          targets: safeTargets, floorBinding: floored[1] as boolean,
          floorRequiredRooms: floorRequiredRooms,
          combinedOpenPct: dabV2CombinedOpenPct(safeTargets, rooms, settings),
          anchorTargets: anchorTargets]
}

// Floor-required-only idle correction (R10.6). While idle and NOT pre-adjusting,
// the ONLY command permitted is a move strictly required to reach the floor. A
// fully-rested zone (combined airflow at 0) is left untouched; a partially-open
// state below the floor is raised to meet it, and ONLY the floor-forced opens
// are commanded (no comfort/balancing targets). Returns null when no floor move
// is required so the caller falls back to the untouched idle result.
private Map dabV2IdleFloorRequiredResult(List roomData, BigDecimal setpointC,
    String mode, Map cfg, Map ctxInputs, Map idleResult) {
  String m = (mode == HEATING || mode == COOLING) ? mode : COOLING
  def ctx = dabV2ContextFrom(ctxInputs)
  List rooms = gatherDabV2RoomInputs(roomData, m, setpointC, ctx)
  def settings = dabV2AllocSettings(cfg, dabV2InactiveAirflow(rooms))
  Map currentTargets = [:]
  rooms.each { r ->
    if (r.active && r.roomId != null) {
      currentTargets[r.roomId] = (isDabV2UsableNumber(r.currentOpen) ? (r.currentOpen as BigDecimal).doubleValue() : 0.0d)
    }
  }
  if (currentTargets.isEmpty()) { return null }
  BigDecimal combinedNow = dabV2CombinedOpenPct(currentTargets, rooms, settings)
  BigDecimal floorPct = (settings.safetyFloorPct ?: SAFETY_FLOOR_DEFAULT) as BigDecimal
  // Only a partial-open state below the floor is a "strictly required" floor
  // move; a fully-rested zone has no airflow to protect while idle.
  if (!(combinedNow > 0 && combinedNow < floorPct)) { return null }
  def floored = sfApply(currentTargets, rooms, settings)
  Map safeTargets = floored[0] as Map
  Set floorRequiredRooms = computeFloorRequiredVentIds(currentTargets, safeTargets)
  if (floorRequiredRooms.isEmpty()) { return null }
  Map onlyRequired = [:]
  safeTargets.each { rid, val -> if (floorRequiredRooms.contains(rid)) { onlyRequired[rid] = val } }
  return idleResult + [balancing: true, preAdjust: false, idleFloorOnly: true,
          mode: m, targets: onlyRequired, floorBinding: true,
          floorRequiredRooms: floorRequiredRooms,
          combinedOpenPct: dabV2CombinedOpenPct(safeTargets, rooms, settings)]
}

// Per-thermostat evaluate (one zone). Resolves the conditioning mode from the
// thermostat action, gathers the zone's room states, and — only while actively
// heating/cooling — runs the balance chain Context_Mapper -> Learning_Model ->
// Allocator -> group-normalize -> Safety_Floor. Returns the floor-safe per-room
// targets plus the metadata the dispatch/observability tasks consume. PURE
// allocation math is delegated entirely to the libraries (R18.7); this method
// is orchestration only.
//
// Scope is exactly the rooms passed in: each thermostat zone is evaluated from
// its own rooms with its own air budget and floor, so rooms on one thermostat
// never compete with another's (per-thermostat scoping, R4/R6/R7).
Map evaluateDabV2Zone(List roomData, BigDecimal setpointC, action,
    Map config = null, Map ctxInputs = null, Map prevCycle = null, Long nowMs = null) {
  Map cfg = config ?: getDabV2Config()
  String mode = resolveDabV2HvacAction(action)
  Long evalNow = (nowMs != null ? nowMs : now()) as Long
  if (!isDabV2BalancingAction(mode)) {
    // Idle / fan-only: no balancing recompute. The bounded pre-adjust path
    // (R10.8) and a move strictly required to reach the floor (R10.6) are the
    // ONLY commands permitted here.
    return dabV2IdleOrPreAdjustResult(prevCycle, roomData, setpointC, cfg, ctxInputs, evalNow)
  }

  // Short-cycle reuse (R10.7): a re-activation in the SAME mode after an idle gap
  // shorter than shortCycleGapMin reuses the prior cycle's anchored allocation
  // rather than recomputing from scratch.
  int shortGap = (cfg?.shortCycleGapMin != null ? cfg.shortCycleGapMin : SHORT_CYCLE_GAP_DEFAULT) as int
  if (dabV2IsShortCycleReuse(prevCycle, mode, evalNow, shortGap)) {
    return dabV2ReusedAnchorResult(prevCycle, mode, roomData, setpointC, cfg, ctxInputs)
  }

  def ctx = dabV2ContextFrom(ctxInputs)
  List rooms = gatherDabV2RoomInputs(roomData, mode, setpointC, ctx)
  def settings = dabV2AllocSettings(cfg, dabV2InactiveAirflow(rooms))

  // Allocator -> group-normalize -> Safety_Floor (single choke point, R6.5).
  def alloc = allocAllocate(rooms, (setpointC ?: 0), mode, settings, null)
  Map normalized = dabV2GroupNormalize(alloc.targets, settings.granularity)
  def floored = sfApply(normalized, rooms, settings)
  Map safeTargets = floored[0] as Map

  // Per-room "must open to MEET the floor" set: a room whose floor-padded target
  // exceeds its pre-floor (balance) target had airflow added solely to satisfy
  // the floor, so its move bypasses anti-chatter on dispatch (R6.2/R10.5).
  Set floorRequiredRooms = computeFloorRequiredVentIds(normalized, safeTargets)

  // Cycle anchor — a mode flip (cooling<->heating) starts a new anchor (R8.8).
  Map anchor = resolveDabV2CycleAnchor(prevCycle?.mode, mode, prevCycle?.cycleId)

  return [balancing: true, action: mode, mode: mode, targets: safeTargets,
          floorBinding: floored[1] as boolean, airflowLimited: alloc.airflowLimited,
          floorRequiredRooms: floorRequiredRooms,
          predictedSpreadC: alloc.predictedSpreadC,
          combinedOpenPct: dabV2CombinedOpenPct(safeTargets, rooms, settings),
          cycleId: anchor.cycleId, newAnchor: anchor.newAnchor, reusedAnchor: false,
          // Anchor bookkeeping the next idle/short-cycle re-activation consumes
          // to decide reuse (R10.7).
          lastActiveMode: mode, idleSinceMs: null, anchorTargets: safeTargets,
          inactiveClosed: dabV2InactiveClosed(roomData, coerceBoolean(cfg.closeInactiveRooms, true))]
}

// Short-cycle reuse result (R10.7). Returns the prior cycle's ANCHORED targets —
// the allocator is NOT re-run ("rather than recomputing from scratch"). The
// Safety_Floor is re-verified (cheap, not the allocator) so the inviolable floor
// still holds against any changed inactive airflow; since the anchored targets
// were already floor-safe, this is idempotent when the topology is unchanged.
private Map dabV2ReusedAnchorResult(Map prevCycle, String mode, List roomData,
    BigDecimal setpointC, Map cfg, Map ctxInputs) {
  Map anchorTargets = (Map) prevCycle.anchorTargets
  def ctx = dabV2ContextFrom(ctxInputs)
  List rooms = gatherDabV2RoomInputs(roomData, mode, setpointC, ctx)
  def settings = dabV2AllocSettings(cfg, dabV2InactiveAirflow(rooms))
  def floored = sfApply(anchorTargets, rooms, settings)
  Map safeTargets = floored[0] as Map
  Set floorRequiredRooms = computeFloorRequiredVentIds(anchorTargets, safeTargets)
  return [balancing: true, action: mode, mode: mode, targets: safeTargets,
          floorBinding: floored[1] as boolean, airflowLimited: ([] as Set),
          floorRequiredRooms: floorRequiredRooms,
          predictedSpreadC: (prevCycle?.predictedSpreadC ?: 0),
          combinedOpenPct: dabV2CombinedOpenPct(safeTargets, rooms, settings),
          cycleId: (prevCycle?.cycleId ?: 0L), newAnchor: false, reusedAnchor: true,
          lastActiveMode: mode, idleSinceMs: null, anchorTargets: anchorTargets,
          inactiveClosed: dabV2InactiveClosed(roomData, coerceBoolean(cfg.closeInactiveRooms, true))]
}

// === DAB v2 vent dispatch (Task 9.3; R6.11, R10.4, R15.1/15.2/15.3, R21.3/21.4) ===
//
// PURE group-dispatch planner. Turns the zone's floor-safe PER-ROOM targets into
// one decision PER ROOM-GROUP, so every physical vent in a room is commanded the
// SAME target (R15.1/15.3) and the anti-chatter/cooldown decision is evaluated
// ONCE per group using the group's most-recent command time (R15.2). A move
// strictly required to MEET the airflow-safety floor bypasses the cooldown and
// the position deadband (R6.2/R10.5). Ordinary moves (padding above the floor)
// honor the cooldown + position deadband AND a per-evaluation batch cap (R10.4);
// floor-required moves are never throttled by the batch cap.
//
// Time and the per-group last-move map are SUPPLIED by the caller — never read
// from the platform here — so this is exercised identically off-device. Returns
// a list of decision maps ordered floor-required-first then largest-move-first:
//   [roomId, target, currentOpen, ventIds, mustOpen, apply]
// where every vent in `ventIds` receives `target` when `apply` is true.
List dabV2PlanGroupDispatch(Map safeTargets, Set floorRequiredRooms, List rooms,
    Map lastGroupMoveMs, Long nowMs, Map opts = [:]) {
  Map byRoom = [:]
  (rooms ?: []).each { r ->
    def rid = r?.roomId
    if (rid != null) { byRoom[rid as String] = r }
  }
  Set floorReq = (floorRequiredRooms ?: ([] as Set))
  Map lastMove = (lastGroupMoveMs ?: [:])
  int maxMoves = (opts?.maxMovesPerCycle != null ? opts.maxMovesPerCycle : VENT_MOVE_MAX_PER_CYCLE) as int
  Long cooldownMs = (opts?.cooldownMs != null ? opts.cooldownMs : VENT_MOVE_MIN_INTERVAL_MS) as Long
  BigDecimal minPercent = (opts?.minPercent != null ? opts.minPercent : VENT_MOVE_MIN_PERCENT) as BigDecimal

  // Build a candidate decision per room-group.
  List candidates = []
  (safeTargets ?: [:]).each { roomId, pct ->
    String rid = roomId as String
    def r = byRoom[rid]
    List ventIds = (r?.ventIds) ?: [rid]
    BigDecimal current = 0
    def co = r?.currentOpen
    if (isDabV2UsableNumber(co)) { current = co as BigDecimal }
    BigDecimal target = (pct == null ? 0 : pct) as BigDecimal
    boolean mustOpen = floorReq.contains(rid) || floorReq.contains(roomId)
    candidates << [roomId: rid, target: target, currentOpen: current,
                   ventIds: ventIds, mustOpen: mustOpen, apply: false]
  }

  // Floor-required groups first, then by largest |target-current| move so the
  // batch cap spends its budget on the most impactful ordinary moves.
  candidates.sort { a, b ->
    if (a.mustOpen != b.mustOpen) { return a.mustOpen ? -1 : 1 }
    BigDecimal da = (a.target - a.currentOpen).abs()
    BigDecimal db = (b.target - b.currentOpen).abs()
    return db <=> da
  }

  int ordinaryApplied = 0
  candidates.each { d ->
    boolean gate = shouldApplyVentMove(currentOpen: d.currentOpen, proposedOpen: d.target,
        mustOpenToMeetFloor: d.mustOpen, nowMs: nowMs, lastMoveMs: lastMove[d.roomId],
        cooldownMs: cooldownMs, minPercent: minPercent)
    if (!gate) { d.apply = false; return }
    if (d.mustOpen) { d.apply = true; return }   // safety move — never batch-capped
    if (ordinaryApplied < maxMoves) { d.apply = true; ordinaryApplied++ }
    else { d.apply = false }
  }
  return candidates
}

// Expand a group-dispatch plan into a per-vent commanded-target map (only groups
// that are APPLIED contribute). By construction every vent in a room shares the
// one group target, so this map can never split a group (R15.3) — the grouping
// property (Property 16) asserts exactly this.
Map dabV2ExpandGroupTargets(List plan) {
  Map out = [:]
  (plan ?: []).each { d ->
    if (!d?.apply) { return }
    (d.ventIds ?: []).each { vid -> out[vid] = (d.target as double) }
  }
  return out
}

// Build the dispatch-time per-room view: resolve each room-group's CONFIRMED
// current aperture from the child vents (the driver's `percent-open` reflects
// the last confirmed Flair state, R6.11) so the anti-chatter decision and the
// next allocation use confirmed — never unconfirmed — airflow. Falls back to the
// caller-supplied room currentOpen when no device is present (off-device tests).
List dabV2DispatchRoomView(Map safeTargets, List roomData) {
  Map byRoom = [:]
  (roomData ?: []).each { rd -> if (rd?.roomId != null) { byRoom[rd.roomId as String] = rd } }
  List view = []
  (safeTargets ?: [:]).each { roomId, pct ->
    String rid = roomId as String
    def rd = byRoom[rid]
    List ventIds = (rd?.ventIds ?: [rid])
    BigDecimal confirmed = null
    ventIds.each { vid ->
      def vent = getChildDevice(vid)
      if (vent != null) {
        BigDecimal vOpen = (vent.currentValue('percent-open') ?: 0) as BigDecimal
        // Group current = the MORE-OPEN of the vents (fail toward more-open):
        // an unconfirmed close on one vent never makes the group look closed.
        if (confirmed == null || vOpen > confirmed) { confirmed = vOpen }
      }
    }
    if (confirmed == null) {
      confirmed = isDabV2UsableNumber(rd?.currentOpen) ? (rd.currentOpen as BigDecimal) : 0
    }
    view << [roomId: rid, ventIds: ventIds, currentOpen: confirmed]
  }
  return view
}

// Dispatch a zone's floor-safe per-room targets to the Flair vents via async
// HTTP. One anti-chatter/cooldown decision per room-group (R15.2); every vent in
// a group gets the identical target (R15.1/15.3); floor-required opens bypass the
// cooldown (R6.2). Rate-limit compliance and the fail-open retry live in
// patchVentDevice/handleVentPatch (R21.3/21.4, R6.11). Credentials are never
// logged (R14.4). The per-group last-move clock is persisted in atomicState.
def dispatchDabV2Targets(Map zoneResult, List roomData = null, Map opts = [:]) {
  if (!zoneResult || !coerceBoolean(zoneResult.balancing, false)) { return }
  // Stale-cycle drop (R21.7): a delayed dispatch for a cycle that has since been
  // superseded must not issue conflicting vent commands.
  if (!dabV2IsCurrentCycle(zoneResult.cycleId)) {
    log "Dropping stale dispatch for superseded cycle ${zoneResult.cycleId} (current ${atomicState?.cycleSeq})", 2
    return
  }
  Map safeTargets = (zoneResult.targets ?: [:])
  if (safeTargets.isEmpty()) { return }
  Set floorReq = (zoneResult.floorRequiredRooms ?: ([] as Set)) as Set
  def cycleId = zoneResult.cycleId
  Long nowMs = now()
  Map lastGroup = new HashMap(atomicState?.lastGroupMoveMs ?: [:])
  // Idempotent-dispatch log keyed by (cycleId, ventId, target) (R21.7). Reset
  // when the cycle changes so a new cycle always re-dispatches.
  Map dispatchLog = (atomicState?.dabV2DispatchLog in Map) ? new HashMap(atomicState.dabV2DispatchLog) : [:]
  if (dispatchLog.cycleId != cycleId) { dispatchLog = [cycleId: cycleId, keys: []] }
  List dispatchedKeys = (dispatchLog.keys in List) ? dispatchLog.keys : []
  List rooms = dabV2DispatchRoomView(safeTargets, roomData)
  List plan = dabV2PlanGroupDispatch(safeTargets, floorReq, rooms, lastGroup, nowMs, opts)

  plan.each { d ->
    if (!d.apply) {
      log "Anti-chatter: holding room-group '${d.roomId}' at ${d.currentOpen}% (proposed ${d.target}%)", 3
      return
    }
    int target = roundToNearestMultiple(d.target)
    boolean anyDispatched = false
    (d.ventIds ?: []).each { vid ->
      String key = dabV2DispatchKey(cycleId, vid, target)
      if (dispatchedKeys.contains(key)) {
        log "Idempotent dispatch: skipping duplicate command for vent '${vid}' -> ${target}% (cycle ${cycleId})", 3
        return
      }
      def vent = getChildDevice(vid)
      if (vent != null) { patchVent(vent, target, [floorRequired: d.mustOpen]) }
      dispatchedKeys << key
      anyDispatched = true
    }
    if (anyDispatched) { lastGroup[d.roomId] = nowMs }
  }
  if (atomicState != null) {
    atomicState.lastGroupMoveMs = lastGroup
    atomicState.dabV2DispatchLog = [cycleId: cycleId, keys: dispatchedKeys]
  }
}

// === DAB v2 observability surfaces (Task 9.8; R13/R14) =======================
//
// Diagnostics are surfaced through child devices (a dashboard can lay rooms out
// side by side and read each room's metrics VERTICALLY) plus a single system-
// summary device for the zone-wide rollups. Nothing here is in the control path:
// the whole surface is opt-in and its absence/failure never affects balancing
// (R14.6). Raw per-vent open % stays on the existing vent child devices.

// Opt-in gate (R14.6): diagnostics default OFF; when off, publish is a no-op.
boolean dabV2DiagnosticsEnabled() {
  return coerceBoolean(settings?.dabV2DiagnosticsEnabled, false)
}

// Build the per-room diagnostic attribute maps. Each room carries ONLY its own
// values — temperature, signed error-to-setpoint, commanded vent open %,
// airflow-limited indicator, learned cooling/heating efficiency, and that room's
// vent leak + knee — so rooms read vertically without cross-room cramming
// (R14.6, R13.3, R9.7, R11.12). The signed error is taken in the conditioning
// direction (cooling: temp - setpoint; heating: setpoint - temp), so a positive
// value means "still needs conditioning" and a negative value surfaces
// overcooling / overheating (R13.3).
Map gatherDabV2RoomDiagnostics(Map zoneResult, List roomData, model, BigDecimal setpointC) {
  Map out = [:]
  if (!roomData) { return out }
  String mode = resolveDabV2HvacAction(zoneResult?.action ?: zoneResult?.mode)
  boolean heating = (mode == HEATING)
  Map targets = (zoneResult?.targets in Map) ? (Map) zoneResult.targets : [:]
  Set limited = (zoneResult?.airflowLimited in Set) ? (Set) zoneResult.airflowLimited : ([] as Set)
  roomData.each { rd ->
    if (rd == null || rd.roomId == null) { return }
    String roomId = rd.roomId as String
    Map attrs = [:]
    if (isDabV2UsableNumber(rd.tempC)) {
      BigDecimal t = (rd.tempC as BigDecimal)
      attrs.temperature = roundBigDecimal(t, 2)
      if (setpointC != null) {
        BigDecimal signed = heating ? ((setpointC as BigDecimal) - t) : (t - (setpointC as BigDecimal))
        attrs.signedErrorC = roundBigDecimal(signed, 2)
      }
    }
    if (targets.containsKey(roomId)) {
      attrs.commandedOpenPct = roundBigDecimal((targets[roomId] as BigDecimal), 1)
    }
    attrs.airflowLimited = limited.contains(roomId)
    dabV2AppendRoomLearned(attrs, roomId, rd, model, heating)
    out[roomId] = attrs
  }
  return out
}

// Append the learned per-room efficiency and that room's vent leak/knee (R11.12,
// R9.7). Efficiency is the dual cooling/heating baseline; leak/knee are taken
// from the room's vents' learned curves (group mean leak, smallest knee — the
// binding aperture). Missing model entries are simply omitted (never crash).
private void dabV2AppendRoomLearned(Map attrs, String roomId, rd, model, boolean heating) {
  def rm = model?.roomEff?.get(roomId)
  if (rm != null) {
    if (rm.cooling?.baseline != null) {
      attrs.coolingEfficiency = roundBigDecimal((rm.cooling.baseline as BigDecimal), 6)
    }
    if (rm.heating?.baseline != null) {
      attrs.heatingEfficiency = roundBigDecimal((rm.heating.baseline as BigDecimal), 6)
    }
  }
  List ventIds = (rd?.ventIds in List) ? (List) rd.ventIds : []
  List leaks = []
  List knees = []
  ventIds.each { vid ->
    def ve = model?.ventEff?.get(vid as String)
    if (ve == null) { return }
    def vm = heating ? ve.heating : ve.cooling
    if (vm == null) { vm = ve.cooling }
    if (vm != null) {
      leaks << (vm.leak as double)
      knees << (vm.knee as int)
    }
  }
  if (!leaks.isEmpty()) {
    BigDecimal leakSum = 0
    leaks.each { leakSum += (it as BigDecimal) }
    attrs.ventLeak = roundBigDecimal((leakSum / leaks.size()), 4)
    attrs.ventKnee = knees.min()
  }
}

// Build the SYSTEM-SUMMARY attribute map: zone-wide rollups only (spread, max
// active-room error, hold/recalculating/idle status, 24 h counters, and the
// active strategy + its comparison metrics). No per-room rows are crammed here
// (R14.1, R14.2, R13.1, R13.2). `roomDiag` is the already-built per-room map,
// reused only to derive the max active-room |signed error|.
Map gatherDabV2SystemSummary(Map zoneResult, Map roomDiag, Map counters, Map metrics) {
  Map out = [
    spreadC  : roundBigDecimal(((zoneResult?.predictedSpreadC ?: 0) as BigDecimal), 2),
    maxErrorC: roundBigDecimal((dabV2MaxAbsError(roomDiag) as BigDecimal), 2),
    status   : dabV2DiagnosticStatus(zoneResult),
    recalc24h: (counters?.recalc24h ?: 0) as int,
    hold24h  : (counters?.hold24h ?: 0) as int,
    strategy : getDabV2ControlStrategy()
  ]
  Map strat = (metrics in Map && metrics[out.strategy] in Map) ? (Map) metrics[out.strategy] : null
  if (strat != null) {
    if (strat.avgSpreadC != null) { out.avgSpreadC = roundBigDecimal((strat.avgSpreadC as BigDecimal), 2) }
    if (strat.maxSpreadC != null) { out.maxSpreadC = roundBigDecimal((strat.maxSpreadC as BigDecimal), 2) }
    if (strat.avgAdjustments != null) { out.avgAdjustments = roundBigDecimal((strat.avgAdjustments as BigDecimal), 2) }
    if (strat.avgMovement != null) { out.avgMovement = roundBigDecimal((strat.avgMovement as BigDecimal), 2) }
    if (strat.avgErrorC != null) { out.avgErrorC = roundBigDecimal((strat.avgErrorC as BigDecimal), 2) }
  }
  return out
}

// Max active-room |signed error| across the per-room diagnostics (R14.1).
private BigDecimal dabV2MaxAbsError(Map roomDiag) {
  if (!roomDiag) { return 0 }
  BigDecimal worst = 0
  roomDiag.each { rid, attrs ->
    if (attrs?.signedErrorC != null) {
      BigDecimal e = (attrs.signedErrorC as BigDecimal).abs()
      if (e > worst) { worst = e }
    }
  }
  return worst
}

// Map an evaluation result to the hold/recalculating/idle status enum (R14.1):
// idle when not balancing; recalculating when a move was made (a floor-required
// open or a fresh cycle anchor); otherwise hold.
private String dabV2DiagnosticStatus(Map zoneResult) {
  if (!coerceBoolean(zoneResult?.balancing, false)) { return 'idle' }
  Set fr = (zoneResult?.floorRequiredRooms in Set) ? (Set) zoneResult.floorRequiredRooms : ([] as Set)
  boolean moved = (!fr.isEmpty()) || coerceBoolean(zoneResult?.newAnchor, false)
  return moved ? 'recalculating' : 'hold'
}

// Publish the diagnostic surfaces for one evaluation. OPT-IN and side-effect
// free with respect to control (R14.6): when disabled this is a no-op. Creates
// one per-room device per managed room (removing any whose room has left the
// topology), a single summary device, and emits each surface's own values via
// `sendEvent`. Failures are swallowed by `safeSendEvent` so diagnostics can
// never break balancing.
void publishDabV2Diagnostics(Map zoneResult, List roomData, model, BigDecimal setpointC,
    Map metrics = null, Long nowMs = null) {
  if (!dabV2DiagnosticsEnabled()) { return }
  Map roomDiag = gatherDabV2RoomDiagnostics(zoneResult, roomData, model, setpointC)
  dabV2SyncRoomDiagnosticDevices(roomDiag.keySet())
  roomDiag.each { roomId, attrs ->
    def dev = getChildDevice(DABV2_DIAG_ROOM_DNI_PREFIX + roomId)
    if (dev == null) { return }
    attrs.each { name, value -> safeSendEvent(dev, [name: name, value: value]) }
  }
  // Maintain the 24 h counters from this observation, then publish the summary.
  String status = dabV2DiagnosticStatus(zoneResult)
  dabV2RecordObservation(status, (nowMs != null ? nowMs : now()) as Long)
  def summaryDev = dabV2EnsureSummaryDevice()
  Map sum = gatherDabV2SystemSummary(zoneResult, roomDiag, dabV2Counters(), metrics)
  if (summaryDev != null) {
    sum.each { name, value -> safeSendEvent(summaryDev, [name: name, value: value]) }
  }
  // Mirror the same values into `state` so the app status page can render them
  // even where a dashboard / child device is not used (design R13/R14 surface 1).
  if (state != null) {
    state.dabV2Diagnostics = [summary: sum, rooms: roomDiag, updatedMs: (nowMs != null ? nowMs : now())]
  }
}

// Create per-room diagnostic devices for the managed rooms and delete any stale
// ones whose room has left the topology (R14.6). Idempotent: an existing device
// is reused.
private void dabV2SyncRoomDiagnosticDevices(Set roomIds) {
  Set wanted = (roomIds ?: ([] as Set)).collect { DABV2_DIAG_ROOM_DNI_PREFIX + it } as Set
  // Remove stale per-room devices.
  getChildDevices().each { dev ->
    String dni = dev?.getDeviceNetworkId()
    if (dni != null && dni.startsWith(DABV2_DIAG_ROOM_DNI_PREFIX) && !wanted.contains(dni)) {
      dabV2SafeDeleteChildDevice(dni)
    }
  }
  // Create any missing per-room devices.
  roomIds.each { roomId ->
    String dni = DABV2_DIAG_ROOM_DNI_PREFIX + roomId
    if (getChildDevice(dni) == null) {
      dabV2SafeAddChildDevice(DABV2_DIAG_ROOM_DRIVER, dni, "DAB Diagnostics ${roomId}".toString())
    }
  }
}

// Ensure the single system-summary device exists; returns it (or null on a
// creation failure, which is non-fatal — diagnostics are opt-in).
private dabV2EnsureSummaryDevice() {
  def dev = getChildDevice(DABV2_DIAG_SUMMARY_DNI)
  if (dev == null) {
    dev = dabV2SafeAddChildDevice(DABV2_DIAG_SUMMARY_DRIVER, DABV2_DIAG_SUMMARY_DNI, 'DAB Zone Summary')
  }
  return dev
}

private dabV2SafeAddChildDevice(String driver, String dni, String label) {
  def created = null
  try {
    created = addChildDevice('bot.flair', driver, dni, [name: label, label: label])
  } catch (Exception e) {
    log "DAB v2 diagnostics: could not create child device ${dni}: ${e.message}", 2
  }
  return created
}

private void dabV2SafeDeleteChildDevice(String dni) {
  try {
    deleteChildDevice(dni)
  } catch (Exception e) {
    log "DAB v2 diagnostics: could not delete child device ${dni}: ${e.message}", 2
  }
}

// --- Error-notification coalescing (R14.5) ----------------------------------
// Returns true if an error keyed by `key` should be surfaced now, false if an
// identical error was surfaced within DABV2_ERROR_COALESCE_MS (so transient
// repeated failures do not spam the user). The last-surfaced timestamp per key
// is kept in `state`.
boolean dabV2ShouldNotifyError(String key, Long nowMs = null) {
  Long t = (nowMs != null ? nowMs : now()) as Long
  Map errLog = (state?.dabV2ErrorLog in Map) ? new HashMap(state.dabV2ErrorLog) : [:]
  Long last = (errLog[key] != null) ? (errLog[key] as Long) : null
  if (last != null && (t - last) < DABV2_ERROR_COALESCE_MS) {
    return false
  }
  errLog[key] = t
  if (state != null) { state.dabV2ErrorLog = errLog }
  return true
}

// --- 24 h status counters (R14.1) -------------------------------------------
// Rolling recalculation / hold counts over a 24 h window. `dabV2RecordObservation`
// increments the counter for the observed status, rolling the window over when
// it has elapsed; `dabV2Counters` returns the current bag.
void dabV2RecordObservation(String status, Long nowMs = null) {
  Long t = (nowMs != null ? nowMs : now()) as Long
  Map c = dabV2Counters()
  Long windowStart = (c.windowStartMs != null) ? (c.windowStartMs as Long) : null
  if (windowStart == null || (t - windowStart) >= DABV2_COUNTER_WINDOW_MS) {
    c = [recalc24h: 0, hold24h: 0, windowStartMs: t]
  }
  if (status == 'recalculating') {
    c.recalc24h = ((c.recalc24h ?: 0) as int) + 1
  } else if (status == 'hold') {
    c.hold24h = ((c.hold24h ?: 0) as int) + 1
  }
  if (state != null) { state.dabV2Counters = c }
}

Map dabV2Counters() {
  Map c = (state?.dabV2Counters in Map) ? new HashMap(state.dabV2Counters) : [:]
  if (c.recalc24h == null) { c.recalc24h = 0 }
  if (c.hold24h == null) { c.hold24h = 0 }
  return c
}

// Render the latest diagnostic surfaces (mirrored into `state` by
// publishDabV2Diagnostics) as a compact status paragraph for the app page
// (R13/R14 surface 1). Returns a friendly message when nothing has published
// yet so the page never shows a blank/raw value.
String renderDabV2DiagnosticsStatus() {
  if (!dabV2DiagnosticsEnabled()) { return '' }
  Map diag = (state?.dabV2Diagnostics in Map) ? (Map) state.dabV2Diagnostics : null
  if (diag == null || !(diag.summary in Map)) {
    return '<small><b>DAB v2 diagnostics:</b> no evaluation published yet.</small>'
  }
  Map s = (Map) diag.summary
  int roomCount = (diag.rooms in Map) ? ((Map) diag.rooms).size() : 0
  return "<small><b>DAB v2 diagnostics:</b> status=${s.status}, spread=${s.spreadC}\u00B0C, " +
         "max error=${s.maxErrorC}\u00B0C, 24h recalcs=${s.recalc24h}, holds=${s.hold24h}, " +
         "strategy=${s.strategy}, rooms=${roomCount}</small>"
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

// Reachable learned-rate (regime) gate — Task 2.5 (F-04 / DC-4; R11.7, R2.3/2.4, R20.4).
//
// Selects the effective change rate used by allocation. The learned (regime/per-room)
// rate is an EMA (see rollingAverage / finalizeRoomStates) that drifts away from the
// baseline as observations accumulate. This gate lets that learned rate ACTUALLY
// diverge from the baseline once it is backed by enough evidence:
//   - sampleCount >= REGIME_MIN_N AND the learned rate is finite and positive
//       -> use the (diverged) learned rate, clamped to [MIN,MAX]_TEMP_CHANGE_RATE;
//   - otherwise -> fall back to the clamped baseline EMA (no premature divergence).
//
// This is the reachable replacement for the Reference's unreachable normalized-weight
// gate (which clamped the learned rate back to baseline and is NOT present here). The
// REGIME_MIN_N threshold is the contract carried forward to the pure Learning_Model
// (task 4.3), where each contextual regime cell is gated identically.
BigDecimal effectiveLearnedRate(def baselineRate, def learnedRate, def sampleCount) {
  BigDecimal baseline = clampLearnedRate(baselineRate)
  int n = (sampleCount ?: 0) as int
  BigDecimal learned = sanitizeLearnedRate(learnedRate)
  if (n >= REGIME_MIN_N && learned != null && learned > 0) {
    return clampLearnedRate(learned)
  }
  return baseline
}

// Returns the rate as a BigDecimal, or null when it is missing / non-finite (NaN,
// Infinity) so callers can treat it as "no usable learned sample". Non-finite
// samples are detected by their textual form so they are rejected before any
// (throwing) BigDecimal conversion is attempted.
private BigDecimal sanitizeLearnedRate(def rate) {
  if (rate == null) { return null }
  String s = String.valueOf(rate)
  if (s == 'NaN' || s == 'Infinity' || s == '-Infinity') { return null }
  return rate as BigDecimal
}

// Clamps a rate into the physical [MIN_TEMP_CHANGE_RATE, MAX_TEMP_CHANGE_RATE] band,
// degrading gracefully to MIN on null / non-finite input (R11.9 robustness).
BigDecimal clampLearnedRate(def rate) {
  BigDecimal v = sanitizeLearnedRate(rate)
  if (v == null) { return MIN_TEMP_CHANGE_RATE }
  if (v < MIN_TEMP_CHANGE_RATE) { return MIN_TEMP_CHANGE_RATE }
  if (v > MAX_TEMP_CHANGE_RATE) { return MAX_TEMP_CHANGE_RATE }
  return v
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


// Safe sendEvent wrapper for test compatibility
private safeSendEvent(device, Map eventData) {
  try {
    sendEvent(device, eventData)
  } catch (Exception e) {
    // In test environment, sendEvent might not be available
    log "Warning: Could not send event ${eventData} to device ${device}: ${e.message}", 2
  }
}

// Clean up existing BigDecimal precision issues in stored data
def cleanupExistingDecimalPrecision() {
  try {
    log "Cleaning up existing decimal precision issues", 2
    
    // Clean up global rates in atomicState
    if (atomicState.maxCoolingRate) {
      def cleanedCooling = cleanDecimalForJson(atomicState.maxCoolingRate)
      if (cleanedCooling != atomicState.maxCoolingRate) {
        atomicState.maxCoolingRate = cleanedCooling
        log "Cleaned maxCoolingRate: ${atomicState.maxCoolingRate}", 2
      }
    }
    
    if (atomicState.maxHeatingRate) {
      def cleanedHeating = cleanDecimalForJson(atomicState.maxHeatingRate)
      if (cleanedHeating != atomicState.maxHeatingRate) {
        atomicState.maxHeatingRate = cleanedHeating
        log "Cleaned maxHeatingRate: ${atomicState.maxHeatingRate}", 2
      }
    }
    
    // Clean up device attributes for existing vents
    def devicesUpdated = 0
    getChildDevices().findAll { it.hasAttribute('percent-open') }.each { device ->
      try {
        def coolingRate = device.currentValue('room-cooling-rate')
        def heatingRate = device.currentValue('room-heating-rate')
        
        if (coolingRate && coolingRate != 0) {
          def cleanedCooling = cleanDecimalForJson(coolingRate)
          if (cleanedCooling != coolingRate) {
            sendEvent(device, [name: 'room-cooling-rate', value: cleanedCooling])
            devicesUpdated++
          }
        }
        
        if (heatingRate && heatingRate != 0) {
          def cleanedHeating = cleanDecimalForJson(heatingRate)
          if (cleanedHeating != heatingRate) {
            sendEvent(device, [name: 'room-heating-rate', value: cleanedHeating])
            devicesUpdated++
          }
        }
      } catch (Exception e) {
        log "Error cleaning device precision for ${device.getLabel()}: ${e.message}", 2
      }
    }
    
    if (devicesUpdated > 0) {
      log "Updated decimal precision for ${devicesUpdated} device attributes", 2
    }
    
  } catch (Exception e) {
    log "Error during decimal precision cleanup: ${e.message}", 2
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
  if (atomicState.activeRequests == null) {
    atomicState.activeRequests = 0
  }
}

// Check if we can make a request (under concurrent limit).
//
// M3 (Task 2.6, review-findings.md F-13, DC-5): this used to RESET the in-flight
// counter to 0 and return true whenever the cap was reached - a throttle that
// disabled itself precisely when it should engage, letting the concurrency cap be
// exceeded and masking a real request leak. The contradictory inline reset has
// been removed: this is now a pure, side-effect-free predicate. Stuck-counter
// recovery lives solely in the periodic cleanupPendingRequests() (runEvery5Minutes),
// and callers retry a false result via runInMillis, so no request is dropped - it
// is deferred. (Deterministic in-flight accounting is the larger follow-up owned
// by tasks 9.3/9.4; see docs/future-work.md.)
def canMakeRequest() {
  initRequestTracking()
  def currentActiveRequests = atomicState.activeRequests ?: 0
  return currentActiveRequests < MAX_CONCURRENT_REQUESTS
}

// Increment active request counter
def incrementActiveRequests() {
  initRequestTracking()
  atomicState.activeRequests = (atomicState.activeRequests ?: 0) + 1
}

// Decrement active request counter
def decrementActiveRequests() {
  initRequestTracking()
  def currentCount = atomicState.activeRequests ?: 0
  atomicState.activeRequests = Math.max(0, currentCount - 1)
  log "Decremented active requests from ${currentCount} to ${atomicState.activeRequests}", 1
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
    // Check if this is an actual HTTP response object (has hasError method)
    if (resp.hasProperty('hasError') && resp.hasError()) {
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
    
    // If it's not an HTTP response object, check if it's a hub load exception
    if (resp instanceof Exception || resp.toString().contains('LimitExceededException')) {
      log "Hub load exception detected in response validation", 1
      return false
    }
    
  } catch (err) {
    log "HTTP response validation error: ${err.message ?: err.toString()}", 1
    return false
  }
  return true
}

// Updated getDataAsync to accept a String callback name with simple throttling.
def getDataAsync(String uri, String callback, data = null, int retryCount = 0) {
  if (canMakeRequest()) {
    incrementActiveRequests()
    def headers = [ Authorization: "Bearer ${state.flairAccessToken}" ]
    def httpParams = [ uri: uri, headers: headers, contentType: CONTENT_TYPE, timeout: HTTP_TIMEOUT_SECS ]
    
    try {
      asynchttpGet(callback, httpParams, data)
    } catch (Exception e) {
      log "HTTP GET exception: ${e.message}", 2
      // Decrement on exception since the request didn't actually happen
      decrementActiveRequests()
      return
    }
  } else {
    if (retryCount < MAX_API_RETRY_ATTEMPTS) {
      def retryData = [uri: uri, callback: callback, retryCount: retryCount + 1]
      if (data?.device && uri.contains('/room')) {
        retryData.data = [deviceId: data.device.getDeviceNetworkId()]
      } else {
        retryData.data = data
      }
      runInMillis(API_CALL_DELAY_MS, 'retryGetDataAsyncWrapper', [data: retryData])
    } else {
      logError "getDataAsync failed after ${MAX_API_RETRY_ATTEMPTS} retries for URI: ${uri}"
    }
  }
}

// Wrapper method for getDataAsync retry
def retryGetDataAsyncWrapper(data) {
  if (!data || !data.uri) {
    logError "retryGetDataAsyncWrapper called with invalid data: ${data}"
    return
  }
  
  // Check if this is a room data request that should go through cache
  if (data.uri.contains('/room') && data.callback == 'handleRoomGetWithCache' && data.data?.deviceId) {
    // When retry data is passed through runInMillis, device objects become serialized
    // So we need to look up the device by ID instead
    def deviceId = data.data.deviceId
    def device = getChildDevice(deviceId)
    
    if (!device) {
      logError "retryGetDataAsyncWrapper: Could not find device with ID ${deviceId}"
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
    getDataAsync(data.uri, data.callback, data.data, data.retryCount)
  }
}

// Updated patchDataAsync to accept a String callback name with simple throttling.
// If callback is null, we use a no-op callback.
def patchDataAsync(String uri, String callback, body, data = null, int retryCount = 0) {
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
    
    try {
      asynchttpPatch(callback, httpParams, data)
    } catch (Exception e) {
      log "HTTP PATCH exception: ${e.message}", 2
      // Decrement on exception since the request didn't actually happen
      decrementActiveRequests()
      return
    }
  } else {
    if (retryCount < MAX_API_RETRY_ATTEMPTS) {
      def retryData = [uri: uri, callback: callback, body: body, data: data, retryCount: retryCount + 1]
      runInMillis(API_CALL_DELAY_MS, 'retryPatchDataAsyncWrapper', [data: retryData])
    } else {
      logError "patchDataAsync failed after ${MAX_API_RETRY_ATTEMPTS} retries for URI: ${uri}"
    }
  }
}

// Wrapper method for patchDataAsync retry
def retryPatchDataAsyncWrapper(data) {
  if (!data || !data.uri || !data.callback) {
    logError "retryPatchDataAsyncWrapper called with invalid data: ${data}"
    return
  }
  
  patchDataAsync(data.uri, data.callback, data.body, data.data, data.retryCount)
}

def noOpHandler(resp, data) {
  log 'noOpHandler called', 3
}

def login() {
  authenticate()
  getStructureData()
}

def authenticate(int retryCount = 0) {
  log 'Getting access_token from Flair using async method', 2
  state.authInProgress = true
  state.remove('authError')  // Clear any previous error state
  
  def uri = "${BASE_URL}/oauth2/token"
  def body = "client_id=${settings?.clientId}&client_secret=${settings?.clientSecret}" +
    '&scope=vents.view+vents.edit+structures.view+structures.edit+pucks.view+pucks.edit&grant_type=client_credentials'
  
  def params = [
    uri: uri, 
    body: body, 
    timeout: HTTP_TIMEOUT_SECS,
    contentType: 'application/x-www-form-urlencoded'
  ]
  
  if (canMakeRequest()) {
    incrementActiveRequests()
    try {
      asynchttpPost('handleAuthResponse', params, [retryCount: retryCount])
    } catch (Exception e) {
      def err = "Authentication request failed: ${e.message}"
      logError err
      state.authError = err
      state.authInProgress = false
      decrementActiveRequests()  // Decrement on exception
      return err
    }
  } else {
    // If we can't make request now, reschedule authentication
    state.authInProgress = false
    if (retryCount < MAX_API_RETRY_ATTEMPTS) {
      runInMillis(API_CALL_DELAY_MS, 'retryAuthenticateWrapper', [data: [retryCount: retryCount + 1]])
    } else {
      def err = "Authentication failed after ${MAX_API_RETRY_ATTEMPTS} retries"
      logError err
      state.authError = err
    }
  }
  return ''
}

// Wrapper method for authenticate retry
def retryAuthenticateWrapper(data) {
  authenticate(data?.retryCount ?: 0)
}

def handleAuthResponse(resp, data) {
  decrementActiveRequests()  // Always decrement when response comes back
  try {
    log "handleAuthResponse called with resp status: ${resp?.getStatus()}", 2
    state.authInProgress = false
    
    if (!resp) {
      state.authError = "Authentication failed: No response from Flair API"
      logError state.authError
      return
    }
    
    if (resp.hasError()) {
      def status = resp.getStatus()
      def errorMsg = "Authentication failed with HTTP ${status}"
      if (status == 401) {
        errorMsg += ": Invalid credentials. Please verify your Client ID and Client Secret."
      } else if (status == 403) {
        errorMsg += ": Access forbidden. Please verify your OAuth credentials have proper permissions."
      } else if (status == 429) {
        errorMsg += ": Rate limited. Please wait a few minutes and try again."
      } else {
        errorMsg += ": ${resp.getErrorMessage() ?: 'Unknown error'}"
      }
      state.authError = errorMsg
      logError state.authError
      return
    }
    
    def respJson = resp.getJson()
    
    if (respJson?.access_token) {
      state.flairAccessToken = respJson.access_token
      state.remove('authError')
      log 'Authentication successful', 2
      
      // Call getStructureData async after successful auth
      runIn(2, 'getStructureDataAsync')
    } else {
      def errorDetails = respJson?.error_description ?: respJson?.error ?: 'No access token in response'
      state.authError = "Authentication failed: ${errorDetails}. " +
                        "Please verify your OAuth 2.0 credentials are correct."
      logError state.authError
    }
  } catch (Exception e) {
    state.authInProgress = false
    state.authError = "Authentication processing failed: ${e.message}"
    logError "handleAuthResponse exception: ${e.message}"
    log "Exception stack trace: ${e.getStackTrace()}", 1
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
    case 'exportEfficiencyData':
      handleExportEfficiencyData()
      break
    case 'importEfficiencyData':
      handleImportEfficiencyData()
      break
    case 'clearExportData':
      handleClearExportData()
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
  // L2 (Task 2.6, review-findings.md F-05, DC-5) - DEFERRED, not changed here.
  // This fans out to four endpoints (vents, pucks, rooms?include=pucks, /api/pucks)
  // and three different handlers each create pucks, with inconsistent puck naming
  // ("Puck-${id}" here vs "${room} Puck" in the rooms handler). Consolidating the
  // redundant fan-out and unifying puck naming touches the device-discovery/creation
  // flow and is owned by the later app-wiring work (tasks 9.x); it is recorded as
  // deferred future work (task 10.4) rather than repaired now because it is not
  // safety-bearing (no airflow impact) and a piecemeal change here would risk
  // regressing device identity. makeRealDevice is idempotent on network id, so the
  // duplicate fan-out is currently harmless (re-discovers the same devices).
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
  decrementActiveRequests()  // Always decrement when response comes back
  try {
    log "handleAllPucks called", 2
    if (!isValidResponse(resp)) {
      log "handleAllPucks: Invalid response status: ${resp?.getStatus()}", 2
      return
    }
    def respJson = resp?.getJson()
    log "All pucks endpoint response: has data=${respJson?.data != null}, count=${respJson?.data?.size() ?: 0}", 2

    if (respJson?.data) {
      def puckCount = 0
      respJson.data.each { puckData ->
        try {
          if (puckData?.id) {
            puckCount++
            def puckId = puckData?.id?.toString()?.trim()
            def puckName = puckData?.attributes?.name?.toString()?.trim() ?: "Puck-${puckId}"

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
      // L1 (Task 2.6, review-findings.md F-05, DC-5): this discovery summary
      // fires ONCE after the loop completes (not once per puck). It was
      // previously buried under inconsistent indentation that made the brace
      // nesting hard to follow; the structure is now explicit and unambiguous.
      if (puckCount > 0) {
        log "Discovered ${puckCount} pucks from all pucks endpoint", 3
      }
    }
  } catch (Exception e) {
    log "Error in handleAllPucks: ${e.message}", 1
  }
}

def handleRoomsWithPucks(resp, data) {
  decrementActiveRequests()  // Always decrement when response comes back
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
  decrementActiveRequests()  // Always decrement when response comes back
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
  def respJson = resp?.getJson()
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
    if (it?.type == 'vents' || it?.type == 'pucks') {
      if (it.type == 'vents') {
        ventCount++
      } else if (it.type == 'pucks') {
        puckCount++
      }
      def device = [
        id   : it?.id,
        type : it?.type,
        label: it?.attributes?.name
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
  decrementActiveRequests()  // Always decrement when response comes back
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
  
  // First, check if the active request counter is stuck
  def currentActiveRequests = atomicState.activeRequests ?: 0
  if (currentActiveRequests >= MAX_CONCURRENT_REQUESTS) {
    log "CRITICAL: Active request counter is stuck at ${currentActiveRequests}/${MAX_CONCURRENT_REQUESTS} - resetting to 0", 1
    atomicState.activeRequests = 0
    log "Reset active request counter to 0", 1
  }
  
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
  decrementActiveRequests()  // Always decrement when response comes back
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
      // Handle hub load exceptions specifically
      if (resp instanceof Exception || resp.toString().contains('LimitExceededException')) {
        logWarn "Device reading request failed due to hub load: ${resp.toString()}"
      } else {
        log "Device reading request failed for ${cacheKey}, status: ${resp?.getStatus()}", 2
      }
    }
  } catch (Exception e) {
    logWarn "Error in handleDeviceGetWithCache: ${e.message}"
  } finally {
    // Always clear the pending flag
    if (cacheKey) {
      clearDeviceRequestPending(cacheKey)
      log "Cleared pending device request for ${cacheKey}", 1
    }
  }
}

def handlePuckGet(resp, data) {
  decrementActiveRequests()  // Always decrement when response comes back
  if (!isValidResponse(resp) || !data?.device) { return }
  def respJson = resp.getJson()
  if (respJson?.data) {
    def puckData = respJson.data
    // Extract puck attributes
    if (puckData?.attributes?.'current-temperature-c' != null) {
      def tempC = puckData.attributes['current-temperature-c']
      def tempF = (tempC * 9/5) + 32
      sendEvent(data.device, [name: 'temperature', value: tempF, unit: '°F'])
      log "Puck temperature: ${tempF}°F", 2
    }
    if (puckData?.attributes?.'current-humidity' != null) {
      sendEvent(data.device, [name: 'humidity', value: puckData.attributes['current-humidity'], unit: '%'])
    }
    if (puckData?.attributes?.voltage != null) {
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
  decrementActiveRequests()  // Always decrement when response comes back
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
        def voltage = reading.attributes['system-voltage']
        // Map system-voltage to voltage attribute for Rule Machine compatibility
        sendEvent(data.device, [name: 'voltage', value: voltage, unit: 'V'])
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
   
   // Map system-voltage to voltage attribute for Rule Machine compatibility
   if (details?.data?.attributes?.'system-voltage' != null) {
     def voltage = details.data.attributes['system-voltage']
     sendEvent(device, [name: 'voltage', value: voltage, unit: 'V'])
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
  decrementActiveRequests()  // Always decrement when response comes back
  if (!data) { return }
  
  // Don't log 404 errors for missing sensors - this is expected
  if (resp?.hasError() && resp.getStatus() == 404) {
    log "No remote sensor data available for ${data?.device?.getLabel() ?: 'unknown device'}", 1
    return
  }
  
  if (!isValidResponse(resp)) { return }
  
  // Additional validation before parsing JSON
  try {
    def details = resp.getJson()
    if (!details?.data?.first()) { return }
    def propValue = details.data.first().attributes['occupied']
    sendEvent(data.device, [name: 'room-occupied', value: propValue])
  } catch (Exception e) {
    log "Error parsing remote sensor JSON: ${e.message}", 2
    return
  }
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

def getStructureDataAsync(int retryCount = 0) {
  log 'Getting structure data asynchronously', 2
  def uri = "${BASE_URL}/api/structures"
  def headers = [ Authorization: "Bearer ${state.flairAccessToken}" ]
  def httpParams = [ 
    uri: uri, 
    headers: headers, 
    contentType: CONTENT_TYPE, 
    timeout: HTTP_TIMEOUT_SECS 
  ]
  
  if (canMakeRequest()) {
    incrementActiveRequests()
    try {
      asynchttpGet('handleStructureResponse', httpParams)
    } catch (Exception e) {
      logError "Structure data request failed: ${e.message}"
      decrementActiveRequests()  // Decrement on exception
    }
  } else {
    // If we can't make request now, retry later
    if (retryCount < MAX_API_RETRY_ATTEMPTS) {
      runInMillis(API_CALL_DELAY_MS, 'retryGetStructureDataAsyncWrapper', [data: [retryCount: retryCount + 1]])
    } else {
      logError "getStructureDataAsync failed after ${MAX_API_RETRY_ATTEMPTS} retries"
    }
  }
}

// Wrapper method for getStructureDataAsync retry
def retryGetStructureDataAsyncWrapper(data) {
  getStructureDataAsync(data?.retryCount ?: 0)
}

def handleStructureResponse(resp, data) {
  decrementActiveRequests()  // Always decrement when response comes back
  try {
    if (!isValidResponse(resp)) { 
      logError "Structure data request failed"
      return 
    }
    
    def response = resp.getJson()
    if (!response?.data?.first()) {
      logError 'No structure data available'
      return
    }
    
    def myStruct = response.data.first()
    if (myStruct?.id) {
      app.updateSetting('structureId', myStruct.id)
      log "Structure loaded: id=${myStruct.id}, name=${myStruct.attributes?.name}", 2
    }
  } catch (Exception e) {
    logError "Structure data processing failed: ${e.message}"
  }
}

def getStructureData(int retryCount = 0) {
  log 'getStructureData', 1
  // M5 (Task 2.6, review-findings.md F-05, DC-5) - DEFERRED, not changed here.
  // This path uses a BLOCKING synchronous httpGet (below), unlike the rest of the
  // integration which is async (asynchttpGet). Converting it sync -> async changes
  // the auth/initialization control flow (callers at initialize()/handleAuthResponse
  // assume structureId is resolved inline) and the platform execution model, so it is
  // not a low-risk, safety-neutral change. An async variant (getStructureDataAsync,
  // used post-auth) already exists; unifying on it is owned by the rate-limit/dispatch
  // hardening (task 9.3) and recorded as deferred future work (task 10.4). It is not
  // safety-bearing (no airflow impact) and the concurrency gate + HTTP_TIMEOUT_SECS
  // bound the blocking window.

  // Check concurrent request limit first
  if (!canMakeRequest()) {
    if (retryCount < MAX_API_RETRY_ATTEMPTS) {
      log "Structure data request delayed due to concurrent limit (attempt ${retryCount + 1}/${MAX_API_RETRY_ATTEMPTS})", 2
      // Schedule retry asynchronously to avoid blocking
      runInMillis(API_CALL_DELAY_MS, 'retryGetStructureDataWrapper', [data: [retryCount: retryCount + 1]])
      return
    } else {
      logError "getStructureData failed after ${MAX_API_RETRY_ATTEMPTS} attempts due to concurrent limits"
      return
    }
  }
  
  def uri = "${BASE_URL}/api/structures"
  def headers = [ Authorization: "Bearer ${state.flairAccessToken}" ]
  def httpParams = [ uri: uri, headers: headers, contentType: CONTENT_TYPE, timeout: HTTP_TIMEOUT_SECS ]
  
  incrementActiveRequests()
  
  try {
    httpGet(httpParams) { resp ->
      decrementActiveRequests()
      
      if (!resp.success) { 
        throw new Exception("HTTP request failed with status: ${resp.status}")
      }
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
  } catch (Exception e) {
    decrementActiveRequests()
    
    if (retryCount < MAX_API_RETRY_ATTEMPTS) {
      log "Structure data request failed (attempt ${retryCount + 1}/${MAX_API_RETRY_ATTEMPTS}): ${e.message}", 2
      // Schedule retry asynchronously
      runInMillis(API_CALL_DELAY_MS, 'retryGetStructureDataWrapper', [data: [retryCount: retryCount + 1]])
    } else {
      logError "getStructureData failed after ${MAX_API_RETRY_ATTEMPTS} attempts: ${e.message}"
    }
  }
}

// Wrapper method for synchronous getStructureData retry
def retryGetStructureDataWrapper(data) {
  getStructureData(data?.retryCount ?: 0)
}

def patchVentDevice(device, percentOpen, Map extra = [:]) {
  def pOpen = Math.min(100, Math.max(0, percentOpen as int))
  def currentOpen = (device?.currentValue('percent-open') ?: 0).toInteger()
  if (pOpen == currentOpen) {
    log "Keeping ${device} percent open unchanged at ${pOpen}%", 3
    return
  }
  log "Setting ${device} percent open from ${currentOpen} to ${pOpen}%", 3
  def deviceId = device.getDeviceNetworkId()
  def uri = "${BASE_URL}/api/vents/${deviceId}"
  def body = [ data: [ type: 'vents', attributes: [ 'percent-open': pOpen ] ] ]

  // Don't update local state until API call succeeds. `extra` carries dispatch
  // metadata (e.g. floorRequired) so the failure path can fail-open (R6.11).
  def callbackData = [device: device, targetOpen: pOpen]
  if (extra) { callbackData.putAll(extra) }
  patchDataAsync(uri, 'handleVentPatch', body, callbackData)
}

// Keep the old method name for backward compatibility
def patchVent(device, percentOpen, Map extra = [:]) {
  patchVentDevice(device, percentOpen, extra)
}

// --- Anti-chatter gate (Task 2.3, F-03 / DC-2) -----------------------------
//
// Distinguishes a move strictly required to reach the airflow-safety floor
// ("must open to MEET the floor": applied immediately, bypassing the cooldown
// and the position deadband) from padding a vent ABOVE the floor (an ordinary
// balancing move that honors the minimum-adjustment interval + position
// deadband). Suppressing a needed safety open is exactly the Reference DC-2
// bug, so the must-open path can never be gated.
//
// PURE: time is supplied by the caller (nowMs / lastMoveMs) — never read from
// the platform here — so this matches the future pure `shouldApply` gate
// (task 6.8, GateContext.floorRequiresMove / withinAntiChatterCooldown) and is
// exercised identically off-device. Applies to ALL strategies (R20.4).
//
// Requirements: 2.3, 2.4, 10.4, 20.4
boolean shouldApplyVentMove(Map args) {
  BigDecimal current = (args.currentOpen ?: 0) as BigDecimal
  BigDecimal proposed = (args.proposedOpen ?: 0) as BigDecimal
  // A no-op (already at the proposed position) is never re-issued.
  if (proposed == current) { return false }
  boolean mustOpenToMeetFloor = args.mustOpenToMeetFloor ? true : false
  // The floor only ever RAISES apertures (R6.9); a required open is immediate
  // and overrides the cooldown, the position deadband, and any anti-chatter.
  if (mustOpenToMeetFloor && proposed > current) { return true }

  // Otherwise this only pads above the floor: honor the position deadband...
  BigDecimal minPercent = (args.minPercent != null ? args.minPercent : VENT_MOVE_MIN_PERCENT) as BigDecimal
  if ((proposed - current).abs() < minPercent) { return false }
  // ...and the minimum-adjustment interval (cooldown), when we know the last
  // move time. A null lastMoveMs means no prior move is on record -> no cooldown.
  Long nowMs = args.nowMs != null ? (args.nowMs as Long) : null
  Long lastMoveMs = args.lastMoveMs != null ? (args.lastMoveMs as Long) : null
  Long cooldownMs = (args.cooldownMs != null ? args.cooldownMs : VENT_MOVE_MIN_INTERVAL_MS) as Long
  // Suppress only when a prior move is on record and is still inside the cooldown.
  return !(nowMs != null && lastMoveMs != null && (nowMs - lastMoveMs) < cooldownMs)
}

// Classifies which vents the Safety_Floor forced open relative to the base
// (comfort/balance) allocation: a vent whose floor-padded target exceeds its
// base target had airflow added solely to meet the floor, so its move is
// "must open to meet floor" and must be dispatched immediately. PURE.
// Requirements: 2.3, 2.4, 10.4, 20.4
Set computeFloorRequiredVentIds(Map baseTargets, Map paddedTargets) {
  def required = [] as Set
  paddedTargets?.each { ventId, paddedVal ->
    BigDecimal padded = (paddedVal ?: 0) as BigDecimal
    BigDecimal base = (baseTargets?.containsKey(ventId) ? (baseTargets[ventId] ?: 0) : 0) as BigDecimal
    if (padded > base) { required << ventId }
  }
  return required
}

def handleVentPatch(resp, data) {
  decrementActiveRequests()  // Always decrement when response comes back
  if (!isValidResponse(resp) || !data) {
    if (resp instanceof Exception || resp.toString().contains('LimitExceededException')) {
      log "Vent patch failed due to hub load: ${resp.toString()}", 2
    } else {
      log "Vent patch failed - invalid response or data", 2
    }
    // R6.11 fail-open: a floor-required OPEN that fails (error/timeout/unconfirmed)
    // is retried toward more-open so actual airflow never settles below the floor.
    // A failed/unconfirmed CLOSE is intentionally NOT retried and NOT recorded as
    // confirmed (percent-open is only updated on success below), so it is never
    // treated as reduced airflow in the next allocation.
    if (data?.floorRequired) {
      def dev = null
      if (data.device?.getDeviceNetworkId) { dev = data.device }
      else if (data.device?.deviceNetworkId) { dev = getChildDevice(data.device.deviceNetworkId) }
      int attempts = (data.failOpenRetries ?: 0) as int
      if (dev != null && attempts < MAX_API_RETRY_ATTEMPTS) {
        log "Fail-open: retrying floor-required open of ${dev} to ${data.targetOpen}% (attempt ${attempts + 1})", 2
        patchVentDevice(dev, data.targetOpen, [floorRequired: true, failOpenRetries: attempts + 1])
      } else if (dev != null) {
        logError "Fail-open: floor-required open of ${dev} failed after ${MAX_API_RETRY_ATTEMPTS} retries"
      }
    }
    return
  }
  
  // Get the actual device for processing (handle serialized device objects)
  def device = null
  if (data.device?.getDeviceNetworkId) {
    device = data.device
  } else if (data.device?.deviceNetworkId) {
    device = getChildDevice(data.device.deviceNetworkId)
  }
  
  if (!device) {
    log "Could not get device object for vent patch processing", 2
    return
  }
  
  // Process the API response
  def respJson = resp.getJson()
  traitExtract(device, [data: respJson.data], 'percent-open', 'percent-open', '%')
  traitExtract(device, [data: respJson.data], 'percent-open', 'level', '%')
  
  // Update local state ONLY after successful API response
  if (data.targetOpen != null) {
    try {
      safeSendEvent(device, [name: 'percent-open', value: data.targetOpen])
      safeSendEvent(device, [name: 'level', value: data.targetOpen])
      log "Updated ${device.getLabel()} to ${data.targetOpen}%", 3
    } catch (Exception e) {
      log "Error updating device state: ${e.message}", 2
    }
  }
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
  decrementActiveRequests()  // Always decrement when response comes back
  if (!isValidResponse(resp) || !data) { return }
  traitExtract(data.device, resp.getJson(), 'active', 'room-active')
}

def thermostat1ChangeTemp(evt) {
  log "Thermostat changed temp to: ${evt.value}", 2
  def temp = settings?.thermostat1?.currentValue('temperature')
  def coolingSetpoint = settings?.thermostat1?.currentValue('coolingSetpoint') ?: 0
  def heatingSetpoint = settings?.thermostat1?.currentValue('heatingSetpoint') ?: 0
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
      // New cycle: assign a unique, monotonic cycle id and cancel any finalize
      // still pending from the just-ended cycle. Together with the cycle-identity
      // guard in finalizeRoomStates this prevents a stale finalize from a
      // superseded cycle running during this one (R21.7, F-01 / DC-3).
      Long cycleId = (atomicState.cycleSeq ?: 0) + 1
      atomicState.cycleSeq = cycleId
      unschedule(finalizeRoomStates)
      atomicStateUpdate('thermostat1State', 'mode', hvacMode)
      atomicStateUpdate('thermostat1State', 'startedRunning', now())
      atomicStateUpdate('thermostat1State', 'cycleId', cycleId)
      unschedule(initializeRoomStates)
      runInMillis(POST_STATE_CHANGE_DELAY_MS, 'initializeRoomStates', [data: hvacMode])
      recordStartingTemperatures()
      runEvery30Minutes('reBalanceVents')

      // Adaptive evaluation cadence (R22): arm the single self-managed evaluate
      // job at the ACTIVE interval now that a thermostat is conditioning. The
      // tick re-arms itself thereafter; this transition switches the cadence.
      scheduleDabV2EvaluateCadence(true)

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
          hvacMode: atomicState.thermostat1State?.mode,
          cycleId: atomicState.thermostat1State?.cycleId
        ]
        runInMillis(TEMP_READINGS_DELAY_MS, 'finalizeRoomStates', [data: params])
        atomicState.remove('thermostat1State')
      }
      
      // Adaptive evaluation cadence (R22): switch the single self-managed
      // evaluate job to the IDLE interval now that no thermostat is conditioning.
      scheduleDabV2EvaluateCadence(false)

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
    hvacMode: atomicState.thermostat1State?.mode,
    cycleId: atomicState.thermostat1State?.cycleId
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
        // L3 (Task 2.6): use the dedicated rebalance-eligibility threshold rather
        // than the conventional-vent default constant (same value, explicit intent).
        if (currPercentOpen <= REBALANCING_MIN_OPEN_PERCENT) { continue }
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
  // Cycle-identity guard (R21.7, F-01 / DC-3): a finalize scheduled for a cycle
  // that has since been superseded by a newer cycle must be a no-op, so a stale
  // pending finalize (e.g. across a rapid active -> idle -> active transition
  // inside the TEMP_READINGS_DELAY_MS window) can never compute a learned rate
  // from the new cycle's starting temperatures and corrupt its state. The check
  // and all subsequent mutations read/write the same atomicState store (mutated
  // under Hubitat's atomicState lock), so they observe one consistent cycle id.
  if (data?.cycleId != null && atomicState.cycleSeq != null && data.cycleId != atomicState.cycleSeq) {
    log "Skipping stale finalizeRoomStates: cycleId ${data.cycleId} superseded by current cycle ${atomicState.cycleSeq}", 3
    return
  }

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
        def cleanedRate = cleanDecimalForJson(rate)
        sendEvent(vent, [name: ratePropName, value: cleanedRate])
        log "Updating ${roomName}'s ${ratePropName} to ${roundBigDecimal(cleanedRate)}", 3
        
        // Store the calculated rate for this room
        roomRates[roomName] = cleanedRate
        
        // Track maximum rates for baseline calculations
        if (cleanedRate > 0) {
          if (data.hvacMode == COOLING) {
            def maxCoolRate = atomicState.maxCoolingRate ?: 0
            if (cleanedRate > maxCoolRate) {
              atomicState.maxCoolingRate = cleanDecimalForJson(cleanedRate)
              log "Updated maximum cooling rate to ${cleanedRate}", 3
            }
          } else if (data.hvacMode == HEATING) {
            def maxHeatRate = atomicState.maxHeatingRate ?: 0
            if (cleanedRate > maxHeatRate) {
              atomicState.maxHeatingRate = cleanDecimalForJson(cleanedRate)
              log "Updated maximum heating rate to ${cleanedRate}", 3
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

  // Snapshot the pre-floor (comfort/balance) plan BEFORE the floor pads it, so
  // we can tell which vents the floor forced open (must-open-to-meet-floor) and
  // which are ordinary "padding above the floor" moves (Task 2.3, F-03 / DC-2).
  def basePercentOpen = [:]
  basePercentOpen.putAll(calcPercentOpen)

  calcPercentOpen = adjustVentOpeningsToEnsureMinimumAirflowTarget(rateAndTempPerVentId, hvacMode, calcPercentOpen, settings.thermostat1AdditionalStandardVents)

  def floorRequired = computeFloorRequiredVentIds(basePercentOpen, calcPercentOpen)
  Long nowMs = now()
  // lastVentMoveMs is always written back as a Map (or absent); copy defensively
  // so per-vent move timestamps survive across evaluations without aliasing state.
  def lastMoveMap = new HashMap(atomicState?.lastVentMoveMs ?: [:])

  calcPercentOpen.each { ventId, percentOpen ->
    def vent = getChildDevice(ventId)
    if (!vent) { return }
    int target = roundToNearestMultiple(percentOpen)
    BigDecimal currentOpen = (vent.currentValue('percent-open') ?: 0) as BigDecimal
    // A move required to reach the floor is dispatched immediately; an ordinary
    // move above the floor honors the cooldown + position deadband.
    if (!shouldApplyVentMove(currentOpen: currentOpen, proposedOpen: target as BigDecimal,
          mustOpenToMeetFloor: floorRequired.contains(ventId), nowMs: nowMs, lastMoveMs: lastMoveMap[ventId])) {
      log "Anti-chatter: holding '${vent}' at ${currentOpen}% (proposed ${target}%)", 3
      return
    }
    patchVent(vent, target)
    lastMoveMap[ventId] = nowMs
  }
  if (atomicState != null) { atomicState.lastVentMoveMs = lastMoveMap }
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
      } else if (hasRoomReachedSetpoint(hvacMode, setpoint, stateVal.temp)) {
        // Directional setpoint check takes precedence over the rate-too-low shortcut:
        // a room at/past setpoint in the conditioning direction must close (overshoot-close).
        def msg = hvacMode == COOLING ? 'cooler' : 'warmer'
        log "Closing vent: '${stateVal.name}' is already ${msg} (${stateVal.temp}) than setpoint (${setpoint})", 3
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
// Efficiency Data Export/Import Functions
// ------------------------------

def handleExportEfficiencyData() {
  try {
    log "Starting efficiency data export", 2
    
    // Collect efficiency data from all vents
    def efficiencyData = exportEfficiencyData()
    
    // Generate JSON format
    def jsonData = generateEfficiencyJSON(efficiencyData)
    
    // Set export status message
    def roomCount = efficiencyData.roomEfficiencies.size()
    state.exportStatus = "✓ Exported efficiency data for ${roomCount} rooms. Copy the JSON data below:"
    
    // Store the JSON data for display
    state.exportedJsonData = jsonData
    
    log "Export completed successfully for ${roomCount} rooms", 2
    
  } catch (Exception e) {
    def errorMsg = "Export failed: ${e.message}"
    logError errorMsg
    state.exportStatus = "✗ ${errorMsg}"
    state.exportedJsonData = null
  }
}

def handleImportEfficiencyData() {
  try {
    log "Starting efficiency data import", 2
    
    // Clear previous status
    state.remove('importStatus')
    state.remove('importSuccess')
    
    // Get JSON data from user input
    def jsonData = settings.importJsonData
    if (!jsonData?.trim()) {
      state.importStatus = "✗ No JSON data provided. Please paste the exported efficiency data."
      state.importSuccess = false
      return
    }
    
    // Import the data
    def result = importEfficiencyData(jsonData.trim())
    
    if (result.success) {
      def statusMsg = "✓ Import successful! Updated ${result.roomsUpdated} rooms"
      if (result.globalUpdated) {
        statusMsg += " and global efficiency rates"
      }
      if (result.roomsSkipped > 0) {
        statusMsg += ". Skipped ${result.roomsSkipped} rooms (not found)"
      }
      
      state.importStatus = statusMsg
      state.importSuccess = true
      
      // Clear the input field after successful import. Guard against a null
      // `app` reference so the import still reports success when the platform
      // app object is unavailable (e.g. off-device harness); on a real hub
      // `app` is always present, so the field is cleared as before.
      app?.updateSetting('importJsonData', '')
      
      log "Import completed: ${result.roomsUpdated} rooms updated, ${result.roomsSkipped} skipped", 2
      
    } else {
      state.importStatus = "✗ Import failed: ${result.error}"
      state.importSuccess = false
      logError "Import failed: ${result.error}"
    }
    
  } catch (Exception e) {
    def errorMsg = "Import failed: ${e.message}"
    logError errorMsg
    state.importStatus = "✗ ${errorMsg}"
    state.importSuccess = false
  }
}

def handleClearExportData() {
  try {
    log "Clearing export data", 2
    state.remove('exportStatus')
    state.remove('exportedJsonData')
    log "Export data cleared successfully", 2
  } catch (Exception e) {
    logError "Failed to clear export data: ${e.message}"
  }
}

def exportEfficiencyData() {
  def data = [
    globalRates: [
      maxCoolingRate: cleanDecimalForJson(atomicState.maxCoolingRate),
      maxHeatingRate: cleanDecimalForJson(atomicState.maxHeatingRate)
    ],
    roomEfficiencies: []
  ]
  
  // Only collect from vents (devices with percent-open attribute)
  getChildDevices().findAll { it.hasAttribute('percent-open') }.each { device ->
    def coolingRate = device.currentValue('room-cooling-rate') ?: 0
    def heatingRate = device.currentValue('room-heating-rate') ?: 0
    
    def roomData = [
      roomId: device.currentValue('room-id'),
      roomName: device.currentValue('room-name'),
      ventId: device.getDeviceNetworkId(),
      coolingRate: cleanDecimalForJson(coolingRate),
      heatingRate: cleanDecimalForJson(heatingRate)
    ]
    data.roomEfficiencies << roomData
  }
  
  return data
}

def generateEfficiencyJSON(data) {
  def exportData = [
    exportMetadata: [
      version: '0.23',
      exportDate: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'"),
      structureId: settings.structureId ?: 'Unknown'
    ],
    efficiencyData: data
  ]
  return JsonOutput.toJson(exportData)
}

def importEfficiencyData(jsonContent) {
  try {
    def jsonData = new groovy.json.JsonSlurper().parseText(jsonContent)
    
    if (!validateImportData(jsonData)) {
      return [success: false, error: 'Invalid data format. Please ensure you are using exported efficiency data.']
    }
    
    def results = applyImportedEfficiencies(jsonData.efficiencyData)
    
    return [
      success: true,
      globalUpdated: results.globalUpdated,
      roomsUpdated: results.roomsUpdated,
      roomsSkipped: results.roomsSkipped,
      errors: results.errors
    ]
  } catch (Exception e) {
    return [success: false, error: e.message]
  }
}

def validateImportData(jsonData) {
  // F-14: degrade gracefully on a malformed top-level value (e.g. parsed `null`)
  // rather than throwing an NPE when dereferencing exportMetadata below.
  if (!jsonData) { return false }
  // Check required structure
  if (!jsonData.exportMetadata || !jsonData.efficiencyData) { return false }
  if (!jsonData.efficiencyData.globalRates) { return false }
  // A *missing* roomEfficiencies key is malformed, but an *empty* list is a
  // valid global-only import. Use an explicit null check so an empty List
  // (which is falsy in Groovy) is not wrongly rejected.
  if (jsonData.efficiencyData.roomEfficiencies == null) { return false }
  
  // Validate global rates
  def globalRates = jsonData.efficiencyData.globalRates
  if (globalRates.maxCoolingRate == null || globalRates.maxHeatingRate == null) { return false }
  if (globalRates.maxCoolingRate < 0 || globalRates.maxHeatingRate < 0) { return false }
  if (globalRates.maxCoolingRate > 10 || globalRates.maxHeatingRate > 10) { return false }
  
  // Validate room efficiencies
  for (room in jsonData.efficiencyData.roomEfficiencies) {
    // A room is addressable by id OR name (an export may legitimately carry only
    // one of them); require a vent id plus at least one identifier instead of
    // discarding the whole import for a single partially-identified room (R12.4).
    if ((!room.roomId && !room.roomName) || !room.ventId) { return false }
    if (room.coolingRate == null || room.heatingRate == null) { return false }
    if (room.coolingRate < 0 || room.heatingRate < 0) { return false }
    if (room.coolingRate > 10 || room.heatingRate > 10) { return false }
  }
  
  return true
}

def applyImportedEfficiencies(efficiencyData) {
  def results = [
    globalUpdated: false,
    roomsUpdated: 0,
    roomsSkipped: 0,
    errors: []
  ]
  
  // Update global rates
  if (efficiencyData.globalRates) {
    atomicState.maxCoolingRate = efficiencyData.globalRates.maxCoolingRate
    atomicState.maxHeatingRate = efficiencyData.globalRates.maxHeatingRate
    results.globalUpdated = true
    log "Updated global rates: cooling=${efficiencyData.globalRates.maxCoolingRate}, heating=${efficiencyData.globalRates.maxHeatingRate}", 2
  }
  
  // Update room efficiencies
  efficiencyData.roomEfficiencies?.each { roomData ->
    def device = matchDeviceByRoomId(roomData.roomId) ?: matchDeviceByRoomName(roomData.roomName)
    
    if (device) {
      // F-14: route device events through the test-tolerant wrapper so a single
      // failed sendEvent cannot abort the whole import. On a real hub
      // safeSendEvent calls sendEvent directly, so production behavior is unchanged.
      safeSendEvent(device, [name: 'room-cooling-rate', value: roomData.coolingRate])
      safeSendEvent(device, [name: 'room-heating-rate', value: roomData.heatingRate])
      results.roomsUpdated++
      log "Updated efficiency for '${roomData.roomName}': cooling=${roomData.coolingRate}, heating=${roomData.heatingRate}", 2
    } else {
      results.roomsSkipped++
      results.errors << "Room not found: ${roomData.roomName} (${roomData.roomId})"
      log "Skipped room '${roomData.roomName}' - no matching device found", 2
    }
  }
  
  return results
}

def matchDeviceByRoomId(roomId) {
  return getChildDevices().find { device ->
    device.hasAttribute('percent-open') && device.currentValue('room-id') == roomId
  }
}

def matchDeviceByRoomName(roomName) {
  return getChildDevices().find { device ->
    device.hasAttribute('percent-open') && device.currentValue('room-name') == roomName
  }
}

def efficiencyDataPage() {
  // Auto-generate export data on page load
  def vents = getChildDevices().findAll { it.hasAttribute('percent-open') }
  def roomsWithData = vents.findAll { 
    (it.currentValue('room-cooling-rate') ?: 0) > 0 || 
    (it.currentValue('room-heating-rate') ?: 0) > 0 
  }
  
  // Automatically generate JSON data when page loads
  def exportJsonData = ""
  if (roomsWithData.size() > 0) {
    try {
      def efficiencyData = exportEfficiencyData()
      exportJsonData = generateEfficiencyJSON(efficiencyData)
    } catch (Exception e) {
      log "Error generating export data: ${e.message}", 2
    }
  }
  
  dynamicPage(name: 'efficiencyDataPage', title: '🔄 Backup & Restore Efficiency Data', install: false, uninstall: false) {
    section {
      paragraph '''
        <div style="background-color: #f0f8ff; padding: 15px; border-left: 4px solid #007bff; margin-bottom: 20px;">
          <h3 style="margin-top: 0; color: #0056b3;">📚 What is this?</h3>
          <p style="margin-bottom: 0;">Your Flair vents learn how efficiently each room heats and cools over time. This data helps the system optimize energy usage. 
          Use this page to backup your data before app updates or restore it after system resets.</p>
        </div>
      '''
    }
    
    // Show current status
    if (vents.size() > 0) {
      section("📊 Current Status") {
        if (roomsWithData.size() > 0) {
          paragraph "<div style='color: green; font-weight: bold;'>✓ Your system has learned efficiency data for ${roomsWithData.size()} out of ${vents.size()} rooms</div>"
        } else {
          paragraph "<div style='color: orange; font-weight: bold;'>⚠ Your system is still learning (${vents.size()} rooms found, but no efficiency data yet)</div>"
          paragraph "<small>Let your system run for a few heating/cooling cycles before backing up data.</small>"
        }
      }
    }
    
    // Export Section - Auto-generated
    if (roomsWithData.size() > 0 && exportJsonData) {
      section("💾 Save Your Data (Backup)") {
        // Create base64 encoded download link with current date
        def currentDate = new Date().format("yyyy-MM-dd")
        def fileName = "Flair-Backup-${currentDate}.json"
        def base64Data = exportJsonData.bytes.encodeBase64().toString()
        def downloadUrl = "data:application/json;charset=utf-8;base64,${base64Data}"
        
        paragraph "Your backup data is ready:"
        
        paragraph "<a href=\"${downloadUrl}\" download=\"${fileName}\">📥 Download ${fileName}</a>"
      }
    } else if (vents.size() > 0) {
      section("💾 Save Your Data (Backup)") {
        paragraph "System is still learning. Check back after a few heating/cooling cycles."
      }
    }
    
    // Import Section
    section("📥 Step 2: Restore Your Data (Import)") {
      paragraph '''
        <p><strong>When should I do this?</strong></p>
        <p>• After reinstalling this app<br>
        • After resetting your Hubitat hub<br>
        • After replacing hardware</p>
      '''
      
      paragraph '''
        <p><strong>How to restore your data:</strong></p>
        <p>1. Find your saved backup JSON file (e.g., "Flair-Backup-2025-06-26.json")<br>
        2. Open the JSON file in Notepad/TextEdit<br>
        3. Select all text (Ctrl+A) and copy (Ctrl+C)<br>
        4. Paste it in the box below (Ctrl+V)<br>
        5. Click "Restore My Data"</p>
        
        <p><small><strong>Note:</strong> Hubitat doesn't support file uploads, so we need to copy/paste the JSON content.</small></p>
      '''
      
      input name: 'importJsonData', type: 'textarea', title: 'Paste JSON Backup Data', 
            description: 'Open your backup JSON file and paste ALL the content here',
            required: false, rows: 8
      
      input name: 'importEfficiencyData', type: 'button', title: 'Restore My Data', 
            submitOnChange: true, width: 4
      
      if (state.importStatus) {
        def statusColor = state.importSuccess ? 'green' : 'red'
        def statusIcon = state.importSuccess ? '✓' : '✗'
        paragraph "<div style='color: ${statusColor}; font-weight: bold; margin-top: 15px; padding: 10px; background-color: ${state.importSuccess ? '#e8f5e8' : '#ffe8e8'}; border-radius: 5px;'>${statusIcon} ${state.importStatus}</div>"
        
        if (state.importSuccess) {
          paragraph '''
            <div style="background-color: #e8f5e8; padding: 15px; border-radius: 5px; margin-top: 10px;">
              <h4 style="margin-top: 0; color: #2d5a2d;">🎉 Success! What happens now?</h4>
              <p>Your room learning data has been restored. Your Flair vents will now use the saved efficiency information to:</p>
              <ul>
                <li>Optimize airflow to each room</li>
                <li>Reduce energy usage</li>
                <li>Maintain comfortable temperatures</li>
              </ul>
              <p style="margin-bottom: 0;"><strong>You're all set!</strong> The system will continue learning and improving from this restored baseline.</p>
            </div>
          '''
        }
      }
    }
    
    // Help & Tips Section
    section("❓ Need Help?") {
      paragraph '''
        <div style="background-color: #f8f9fa; padding: 15px; border-radius: 5px;">
          <h4 style="margin-top: 0;">💡 Tips for Success</h4>
          <ul style="margin-bottom: 10px;">
            <li><strong>Regular Backups:</strong> Save your data monthly or before any system changes</li>
            <li><strong>File Naming:</strong> Include the date in your backup filename (e.g., "Flair-Backup-2025-06-26")</li>
            <li><strong>Multiple Copies:</strong> Store backups in multiple places (email, cloud storage, USB drive)</li>
            <li><strong>When to Restore:</strong> Only restore data when setting up a new system or after data loss</li>
          </ul>
          
          <h4>🚨 Troubleshooting</h4>
          <ul style="margin-bottom: 0;">
            <li><strong>Import Failed:</strong> Make sure you copied ALL the text from your backup file</li>
            <li><strong>No Data to Export:</strong> Let your system run for a few heating/cooling cycles first</li>
            <li><strong>Room Not Found:</strong> Room names may have changed - the system will skip those rooms</li>
            <li><strong>Still Need Help:</strong> Check the Hubitat community forums or contact support</li>
          </ul>
        </div>
      '''
    }
    
    section {
      href name: 'backToMain', title: '← Back to Main Settings', description: 'Return to the main app configuration', page: 'mainPage'
    }
  }
}

// ------------------------------
// End of Core Functions
// ------------------------------
