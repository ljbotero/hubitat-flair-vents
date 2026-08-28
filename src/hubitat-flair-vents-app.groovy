import groovy.transform.Field
import groovy.json.JsonOutput
#include bot.flair.FlairVentsDabv2

/**
 *  Hubitat Flair Vents Integration
 *  Version 0.241
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

// HPM channel version mirrors (R7.2). These MUST stay consistent with
// packageManifest.json (`version` / `betaVersion`) and the bundles/ artifact
// filenames (flair-vents.v<version>.zip / flair-vents.v<betaVersion>.zip).
// The displayed version is DERIVED from these via dabv2DeriveDisplayVersion so
// a null/missing field can never render "vnullbeta" (R7.8) — it falls back to
// VERSION_FALLBACK_LABEL instead.
@Field static final String STABLE_VERSION = '0.235'
@Field static final String BETA_VERSION = '0.241'
// The channel this build ships on: the v0.241 bundle is the beta/early-release
// artifact, so it derives its displayed version from BETA_VERSION.
@Field static final String RELEASE_CHANNEL = 'beta'
// Documented default version label used only when a manifest field is
// null/missing/blank, so the UI never shows a null-derived "vnullbeta" (R7.8).
@Field static final String VERSION_FALLBACK_LABEL = '0.0.0-dev'

// App version string, surfaced in the App UI (config page header) so it can be
// reported in support requests (R7.1, R7.2, R7.5). Mirrors BETA_VERSION (this
// build ships on the beta channel). The channel-aware, fallback-safe derivation
// that guarantees no null-derived "vnullbeta" string is appDisplayVersion()
// (R7.8); this literal stays equal to that derived value.
@Field static final String APP_VERSION = '0.241'

// Base URL for Flair API endpoints.
@Field static final String BASE_URL = 'https://api.flair.co'

// Instance-based cache durations (reduced from 60s to 30s for better responsiveness)
@Field static final Long ROOM_CACHE_DURATION_MS = 30000 // 30 second cache duration
@Field static final Long DEVICE_CACHE_DURATION_MS = 30000 // 30 second cache duration for device readings
@Field static final Integer MAX_CACHE_SIZE = 50 // Maximum cache entries per instance

// Content-Type header for API requests.
@Field static final String CONTENT_TYPE = 'application/json'

// Channel-aware display version, derived from the manifest-mirroring constants
// via the pure dabv2DeriveDisplayVersion helper. A null/missing channel field
// falls back to the documented VERSION_FALLBACK_LABEL, so this NEVER returns a
// null-derived "vnullbeta" string (R7.2 / R7.8). Surfaced wherever the running
// version is reported.
String appDisplayVersion() {
  return dabv2DeriveDisplayVersion(
    [version: STABLE_VERSION, betaVersion: BETA_VERSION], RELEASE_CHANNEL, VERSION_FALLBACK_LABEL)
}

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

// R4.19 (Task 6.8): stuck concurrency-counter timeout. If the in-flight request
// counter sits wedged at MAX_CONCURRENT_REQUESTS for longer than this window with
// no progress (no increment/decrement updating atomicState.requestTrackingTs),
// initRequestTracking() resets it to 0 so the request path cannot wedge
// permanently. Chosen at 5 minutes: comfortably longer than the 5 s HTTP timeout
// (HTTP_TIMEOUT_SECS) so a legitimately busy burst is never reset prematurely,
// yet well under an hour so a real wedge recovers promptly.
@Field static final long REQUEST_TRACKING_STUCK_MS = 300_000L

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

// Zone-iterating evaluate chunking (R2.7, R8.26; design §5.3). The zoned
// evaluate loop processes a bounded number of zones per invocation and continues
// the remainder on a fresh scheduled invocation so a home with many zones never
// blocks past the Hubitat ~20s method budget.
@Field static final Integer DABV2_ZONES_PER_CHUNK = 4
@Field static final Long DABV2_ZONE_CHUNK_DELAY_MS = 100L

// `state` key under which the compact schema-v2 learned model is persisted
// (R12.1). The app owns this read/write seam; the pure Dabv2ModelIo module does
// the encode/decode/migrate/bound math.
@Field static final String DABV2_MODEL_STATE_KEY = 'dabv2LearnedModel'

// Sentinel scope value for the user-initiated DAB reset (R7.3/R7.14) that selects
// ALL zones rather than a single zone id; any other scope value is treated as a
// single zone id.
@Field static final String DABV2_RESET_SCOPE_ALL = 'all-zones'


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
// Per-room target/offset config-boundary bounds (R3.4/R3.5/R3.16). Mirror the
// pure resolver defaults so UI- and Rule-Machine-supplied values clamp identically.
@Field static final BigDecimal PER_ROOM_ABS_MIN_C = 10.0
@Field static final BigDecimal PER_ROOM_ABS_MAX_C = 32.0
@Field static final BigDecimal PER_ROOM_ABS_DEFAULT_C = 21.0
@Field static final BigDecimal PER_ROOM_OFFSET_MIN_C = -5.0
@Field static final BigDecimal PER_ROOM_OFFSET_MAX_C = 5.0
@Field static final BigDecimal PER_ROOM_OFFSET_DEFAULT_C = 0.0

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
// R6 circulation (fan-only) default open percentage (D4). Range/clamping is
// introduced in task 8.4; this default keeps the feature off by default (the
// `circulationEnabled` flag defaults false) while letting the orchestration read
// the percentage defensively.
@Field static final Integer CIRCULATION_OPEN_DEFAULT = 50
// R6.5/R6.6 circulation open percentage validated range (clamped to the nearest
// bound). The documented default above sits inside this range.
@Field static final Integer CIRCULATION_OPEN_MIN = 10
@Field static final Integer CIRCULATION_OPEN_MAX = 100
// R6.7/R6.8 circulation debounce (seconds): documented default 60 s, validated
// range 0–600 s (clamped to the nearest bound). Suppresses short-burst
// circulation thrash via shouldApplyCirculationChange.
@Field static final Integer CIRCULATION_DEBOUNCE_DEFAULT = 60
@Field static final Integer CIRCULATION_DEBOUNCE_MIN = 0
@Field static final Integer CIRCULATION_DEBOUNCE_MAX = 600
// R7.4 configurable minimum vent opening (D3 = global with optional per-vent).
// Documented default 0 % (feature neutral on upgrade — no vent is forced open),
// validated range 0–100 % (clamped to the nearest bound). Per-vent overrides
// live in the zone slice (state.zones[zoneId].minVentOpen) and win over this
// global value; applied before the final sfApply so the floor still wins.
@Field static final Integer MIN_VENT_OPEN_DEFAULT = 0
@Field static final Integer MIN_VENT_OPEN_MIN = 0
@Field static final Integer MIN_VENT_OPEN_MAX = 100
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

// mainPage is intentionally a thin orchestrator that delegates each section to
// its own helper method. Hubitat textually merges the #include'd library into
// this app and recompiles the whole unit on every save; the sandbox AST
// transform is disproportionately expensive for very large single methods, so
// keeping each page section small keeps the save/compile fast. The helper
// methods call section()/input()/paragraph() against the page builder exactly
// like renderZoneConfigSections() already does.
def mainPage() {
  // Issue #7 (GitHub): the OAuth section promises "the page will refresh
  // automatically" while background authentication runs, but the page had no
  // refreshInterval so it never did — users stared at a stale "Authenticating…"
  // paragraph after auth had already succeeded. Auto-refresh only while an auth
  // outcome is pending (credentials present, no token, no surfaced error yet).
  boolean awaitingAuth = settings?.clientId && settings?.clientSecret &&
    !state.flairAccessToken && !state.authError
  Map pageOpts = [name: 'mainPage', title: 'Setup', install: true, uninstall: true]
  if (awaitingAuth) { pageOpts.refreshInterval = 3 }
  dynamicPage(pageOpts) {
    section {
      paragraph "<small><b>Hubitat Integration for Flair Smart Vents</b> — version ${APP_VERSION}</small>"
    }
    oauthSetupSection()

    if (state.flairAccessToken) {
      deviceDiscoverySection()
      listDiscoveredDevices()
      dabBalancingSection()
      renderZoneConfigSections()
      ventOptionsSection()
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

private oauthSetupSection() {
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
}

private deviceDiscoverySection() {
  section('Device Discovery') {
    input name: 'discoverDevices', type: 'button', title: 'Discover', submitOnChange: true
    input name: 'structureId', type: 'text', title: 'Home Id (SID)', required: false, submitOnChange: true
  }
}

private dabBalancingSection() {
  section('<h2>Dynamic Airflow Balancing</h2>') {
    input name: 'dabEnabled', type: 'bool', title: 'Use Dynamic Airflow Balancing', defaultValue: false, submitOnChange: true
    if (dabEnabled) {
      renderDabBalancingOptions()
    }
    // Only show vents in DAB section, not pucks
    def vents = getChildDevices().findAll { it.hasAttribute('percent-open') }
    for (child in vents) {
      input name: "thermostat${child.getId()}", type: 'capability.temperatureMeasurement', title: "Choose Thermostat for ${child.getLabel()} (Optional)", multiple: false, required: false
    }
  }
}

private renderDabBalancingOptions() {
  input name: 'thermostat1', type: 'capability.thermostat', title: 'Choose Thermostat for Vents', multiple: false, required: true
  input name: 'thermostat1TempUnit', type: 'enum', title: 'Units used by Thermostat', defaultValue: 2,
        options: [1: 'Celsius (°C)', 2: 'Fahrenheit (°F)']
  input name: 'thermostat1AdditionalStandardVents', type: 'number', title: 'Count of conventional Vents', defaultValue: 0, submitOnChange: true
  paragraph '<small>Enter the total number of standard (non-Flair) adjustable vents in the home associated ' +
            'with the chosen thermostat, excluding Flair vents. This ensures the combined airflow does not drop ' +
            'below a specified percent to prevent HVAC issues.</small>'
  input name: 'thermostat1CloseInactiveRooms', type: 'bool', title: 'Close vents on inactive rooms', defaultValue: true, submitOnChange: true

  // R7.7 (R7.38/R7.40/R7.41): document the Manual-mode limitation in the
  // App UI. Verified by A8 — while Dynamic Airflow Balancing holds the
  // Flair structure in Manual mode (`patchStructureData([mode:
  // 'manual'])`), Flair disables all of its own automation, so the
  // local Puck dial can no longer set a room set point. There is no
  // Flair API toggle to retain local Puck control, so this is surfaced
  // as documentation only — no non-functional enable option is offered.
  paragraph '<small><b>Note — Manual mode and the local Puck dial:</b> while Dynamic Airflow ' +
            'Balancing is enabled it holds your Flair structure in <b>Manual mode</b>, which disables ' +
            'Flair\u2019s own automation. As a result, turning the dial on a Flair Puck no longer changes ' +
            'the room set point (local Puck setpoint control is unavailable while DAB-managed Manual mode ' +
            'is held). The Flair API provides no way to re-enable the local Puck dial in this state, so ' +
            'control the room set point from Hubitat instead.</small>'

  // R6 fan-only / circulation-mode awareness. OFF by default so existing
  // installs see no behavior change on upgrade (R6.9). The open percentage
  // (R6.5/R6.6, range 10–100 %) and debounce (R6.7/R6.8, range 0–600 s) are
  // clamped to the documented bounds by their accessors.
  input name: 'circulationEnabled', type: 'bool', title: 'Open vents during fan-only circulation', defaultValue: false, submitOnChange: true
  if (settings?.circulationEnabled) {
    input name: 'circulationOpenPct', type: 'number', title: 'Circulation open percentage (10–100 %)', defaultValue: CIRCULATION_OPEN_DEFAULT, submitOnChange: true
    input name: 'circulationDebounceSec', type: 'number', title: 'Circulation debounce (0–600 s)', defaultValue: CIRCULATION_DEBOUNCE_DEFAULT, submitOnChange: true
    paragraph '<small>When the thermostat runs the fan without active heating or cooling, open the ' +
              'zone\u2019s vents to the circulation percentage. The debounce suppresses short fan-only bursts. ' +
              'The airflow safety floor always takes precedence.</small>'
  }

  input name: 'controlStrategy', type: 'enum', title: 'Control Strategy',
        options: dabV2ControlStrategyOptions(),
        defaultValue: dabV2NewInstallDefaultStrategy(), submitOnChange: true
  input name: 'safetyFloorPct', type: 'number', title: 'Airflow safety floor (%)', defaultValue: 40
  input name: 'minVentOpenGlobalPct', type: 'number', title: 'Minimum vent opening (0–100 %)', defaultValue: MIN_VENT_OPEN_DEFAULT
  paragraph '<small>Every balanced vent is commanded to at least this percentage. The airflow safety ' +
            'floor and the inactive-room close both take precedence over this minimum.</small>'
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
  dabPerRoomDoorSensorInputs()

  input name: 'dabV2DiagnosticsEnabled', type: 'bool',
        title: 'Create diagnostic devices (per-room + zone summary)', defaultValue: false
  paragraph '<small>Applies to the DAB v2 "balance" control strategy only. Devices are created on ' +
            'the first balance evaluation after enabling and refresh on every evaluation (about every ' +
            '3 minutes while heating/cooling, every 10 minutes while idle, with your intervals above).</small>'
  if (settings.thermostat1AdditionalStandardVents < 0) {
    app.updateSetting('thermostat1AdditionalStandardVents', 0)
  } else if (settings.thermostat1AdditionalStandardVents > MAX_STANDARD_VENTS) {
    app.updateSetting('thermostat1AdditionalStandardVents', MAX_STANDARD_VENTS)
  }

  if (!getThermostat1Mode() || getThermostat1Mode() == 'auto') {
    patchStructureData([mode: 'manual'])
    atomicState?.putAt('thermostat1Mode', 'manual')
  }

  dabEfficiencyLinkSection()
}

// Per-room door sensors (R11.6): an open door only affects its OWN room's
// conditioning rate, so each discovered room may map its own contact sensor.
// Rooms with no per-room sensor fall back to the whole-home door sensor.
// Occupancy is taken per-room from the Flair puck automatically (the
// room-occupied attribute), with the occupancy source as the whole-home fallback.
private dabPerRoomDoorSensorInputs() {
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
}

private dabEfficiencyLinkSection() {
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

private ventOptionsSection() {
  section('Vent Options') {
    input name: 'ventGranularity', type: 'enum', title: 'Vent Adjustment Granularity (in %)',
          options: ['5':'5%', '10':'10%', '25':'25%', '50':'50%', '100':'100%'],
          defaultValue: '5', required: true, submitOnChange: true
    paragraph '<small>Select how granular the vent adjustments should be. For example, if you choose 50%, vents ' +
              'will only adjust to 0%, 50%, or 100%. Lower percentages allow for finer control, but may ' +
              'result in more frequent adjustments (which could affect battery-powered vents).</small>'
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
  // Full instance teardown (R8.13; design §5.4 "uninstalled() still tears down
  // everything"). KEEP the existing complete teardown of children, ALL schedules
  // (app-wide), and ALL subscriptions...
  removeChildren()
  unschedule()
  unsubscribe()
  // ...AND additionally leave NO credential or instance-global bookkeeping behind:
  // drop the held OAuth token and clear the instance's shared/concurrent
  // atomicState (activeRequests, request-tracking timestamps, cadence, cycle ids).
  state.remove('flairAccessToken')
  clearDabV2AtomicState()
}

// Clear ALL instance-global atomicState bookkeeping as part of the full
// uninstalled() teardown (R8.13). Removing each key (over a SNAPSHOT of the key
// set, so mutation during iteration is safe) is the portable way to empty
// atomicState on-hub; clear() is used only as a fallback where the platform
// exposes it. No-op when atomicState is unavailable (e.g. off-device harness
// without a backing map).
private void clearDabV2AtomicState() {
  if (atomicState == null) { return }
  try {
    new ArrayList(atomicState.keySet()).each { k -> atomicState.remove(k) }
  } catch (ignored) {
    try { atomicState.clear() } catch (ignored2) { /* nothing else we can do */ }
  }
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
    // R6.2: a fan-mode flip (auto <-> on) while idle starts/stops circulation,
    // which the operating-state subscription cannot see. Subscribing the fan
    // mode lets the evaluate notice promptly instead of waiting for the next
    // idle cadence tick; the handler gates on dabEnabled + circulationEnabled
    // and debounces short bursts (R6.7/R6.8).
    subscribe(settings.thermostat1, 'thermostatFanMode', thermostat1FanModeHandler)
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

  // Per-zone schedule setup (R2.13/R2.14; design §2.2/§5.4). Arm one zone-scoped
  // evaluate schedule for EACH of THIS instance's zones, addressed by the
  // Task-14.2 zone-suffixed id `dabV2ZoneScheduleId(zoneId)`. Driven by
  // getZoneIds() (the post-migration zone shape) rather than the legacy global
  // thermostat1, so installed()/updated()/initialize() all establish per-zone
  // setup for the instance's own zones only — and the zone-scoped teardown
  // (removeZone / reassignDeviceToZone) can cancel exactly that zone's job.
  scheduleDabV2ZoneEvaluations()

  // R14.6 transition cleanup: reconcile the opt-in diagnostics surface with the
  // saved settings — turning the toggle off (or leaving the balance strategy)
  // removes the diagnostic children and mirrored state instead of leaving them
  // stale until uninstall.
  dabV2CleanupDiagnosticsIfDisabled()
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
      // Fall back to room temperature; defer (null) if the room API has none
      // either (R1.21 — never command on missing data).
      def roomTemp = vent.currentValue('room-current-temperature-c')
      if (roomTemp == null) {
        log "Deferring for '${roomName}': Puck source has no temperature and room API has none", 2
        return null
      }
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
    // R1.20/R1.21: with neither a Puck source nor a room API temperature,
    // defer (null sentinel) rather than commanding on a fabricated 0°C reading.
    log "Deferring for room '${roomName}' - no temperature from Puck source or room API", 2
    return null
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

// R6.9 circulation (fan-only) awareness — OFF by default. Existing installs are
// unchanged on upgrade because the flag must be explicitly enabled.
boolean getDabV2CirculationEnabled() {
  coerceBoolean(settings?.circulationEnabled, false)
}

// R6.5/R6.6 circulation open percentage. Documented default 50 %, validated
// range 10–100 % with out-of-range input clamped to the nearest bound
// (consistent with the existing clampInt numeric-config validation approach). A
// missing/blank/non-numeric value degrades to the documented default.
Integer getDabV2CirculationOpenPct() {
  clampInt(settings?.circulationOpenPct, CIRCULATION_OPEN_MIN, CIRCULATION_OPEN_MAX, CIRCULATION_OPEN_DEFAULT)
}

// R6.7/R6.8 circulation debounce (seconds). Documented default 60 s, validated
// range 0–600 s with out-of-range input clamped to the nearest bound. Feeds the
// shouldApplyCirculationChange short-burst suppression gate.
Integer getDabV2CirculationDebounceSec() {
  clampInt(settings?.circulationDebounceSec, CIRCULATION_DEBOUNCE_MIN, CIRCULATION_DEBOUNCE_MAX, CIRCULATION_DEBOUNCE_DEFAULT)
}

// R7.4 configurable minimum vent opening (global, D3). Documented default 0 %,
// validated range 0–100 % with out-of-range input clamped to the nearest bound
// (consistent with the existing clampInt numeric-config validation approach). A
// missing/blank/non-numeric value degrades to the documented default. Per-vent
// overrides (state.zones[zoneId].minVentOpen) win over this global value.
Integer getDabV2MinVentOpenPct() {
  clampInt(settings?.minVentOpenGlobalPct, MIN_VENT_OPEN_MIN, MIN_VENT_OPEN_MAX, MIN_VENT_OPEN_DEFAULT)
}

// R6.7 circulation debounce gate. Mirrors the shouldApplyVentMove anti-chatter
// cooldown so short fan-only bursts do not cause circulation thrash: a
// circulation state change is SUPPRESSED only when a prior change is on record
// AND it falls inside the debounce window. A change is APPLIED when the window
// has elapsed, when there is no prior change on record, or when the debounce is
// zero (suppression disabled). PURE: time is supplied by the caller (nowMs /
// lastCirculationMs) and never read from the platform here.
//
// Requirements: 6.7, 6.8
boolean shouldApplyCirculationChange(Map args) {
  Long debounceMs = (args.debounceMs != null ? args.debounceMs : 0L) as Long
  // A zero (or non-positive) debounce disables short-burst suppression.
  if (debounceMs <= 0L) { return true }
  Long nowMs = args.nowMs != null ? (args.nowMs as Long) : null
  Long lastMs = args.lastCirculationMs != null ? (args.lastCirculationMs as Long) : null
  // No prior change on record -> nothing to debounce against.
  if (nowMs == null || lastMs == null) { return true }
  // Suppress while still inside the debounce window; apply once it has elapsed.
  return (nowMs - lastMs) >= debounceMs
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
    closeInactiveRooms        : getDabV2CloseInactiveRooms(),
    circulationEnabled        : getDabV2CirculationEnabled(),
    circulationOpenPct        : getDabV2CirculationOpenPct(),
    circulationDebounceSec    : getDabV2CirculationDebounceSec(),
    // R7.4 minimum vent opening (global; per-vent overrides are merged per zone
    // in getDabV2ZoneConfig). Applied before the final sfApply (precedence:
    // safety floor > inactive-room close > minimum opening).
    minVentOpenGlobalPct      : getDabV2MinVentOpenPct(),
    minVentOpen               : [:]
  ]
}

// Resolve the DAB v2 evaluate config for a SINGLE zone (R2.10/R2.11/R8.3).
// IDENTICAL to getDabV2Config() EXCEPT safetyFloorPct is read from this zone's
// OWN slice (state.zones[zoneId].safetyFloorPct) and clamped on the same
// SAFETY_FLOOR_MIN/MAX/DEFAULT band as the instance-global getter. Reading no
// other zone's slice, so each zone is floored on its OWN value when sfApply runs
// over only that zone's assigned vents — there is no cross-zone airflow
// accumulator. A missing/blank/non-numeric per-zone value degrades to the
// documented default via clampDecimal, exactly like the global getter.
Map getDabV2ZoneConfig(String zoneId) {
  Map config = getDabV2Config()
  Map zones = (state?.zones instanceof Map) ? (Map) state.zones : [:]
  Map zone = (zones[zoneId] instanceof Map) ? (Map) zones[zoneId] : [:]
  config.safetyFloorPct = clampDecimal(zone?.safetyFloorPct, SAFETY_FLOOR_MIN, SAFETY_FLOOR_MAX, SAFETY_FLOOR_DEFAULT)
  // R7.4: a per-zone global minimum opening (zone.minOpeningPct) overrides the
  // instance-global setting; per-vent overrides (zone.minVentOpen) win over both.
  if (zone?.minOpeningPct != null) {
    config.minVentOpenGlobalPct = clampInt(zone.minOpeningPct, MIN_VENT_OPEN_MIN, MIN_VENT_OPEN_MAX, MIN_VENT_OPEN_DEFAULT)
  }
  config.minVentOpen = (zone?.minVentOpen instanceof Map) ? (Map) zone.minVentOpen : [:]
  return config
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

// === Per-zone learned-model persistence (Option A; R2.7/R2.8, design §4.3/§5.3) ===
//
// Under Option A one App_Instance hosts N zones under `state.zones[zoneId]`, and
// each zone owns its OWN learned efficiency model stored at
// `state.zones[zoneId][DABV2_MODEL_STATE_KEY]`. These per-zone overloads are the
// load/save seam the zone-iterating evaluate loop uses; they read and write ONLY
// the addressed zone's slice so no zone can read or write another zone's model
// (R2.8). They coexist with the flat `loadDabV2Model()` / `saveDabV2Model(Map)`
// used by `initialize()` and `migrateToZoneModel` — Groovy dispatches on the
// `String zoneId` argument, so the legacy callers are unaffected.

// Load (and migrate-on-load with no data loss) the learned model from ONLY this
// zone's slice. An empty default-zone slice seeds from the legacy device-
// attribute efficiency export so an upgraded single-thermostat install loses no
// learning (parity with the flat loader); any other zone starts from a fresh
// model so a zone never inherits the global or another zone's learning (R2.8).
def loadDabV2Model(String zoneId) {
  Map zones = (state?.zones instanceof Map) ? (Map) state.zones : [:]
  Map zone = (zones[zoneId] instanceof Map) ? (Map) zones[zoneId] : [:]
  Object persisted = zone.get(DABV2_MODEL_STATE_KEY)
  boolean alreadyV2 = persisted instanceof Map &&
    ((Map) persisted).get('v') instanceof Number &&
    (((Map) persisted).get('v') as int) >= MIO_SCHEMA_VERSION

  Object source = persisted != null ? persisted
      : (zoneId == 'default' ? exportEfficiencyData() : null)

  def model = mioMigrate(source)

  // Persist the migrated model back as schema v2 (idempotent: an already-v2
  // slice is left untouched), writing ONLY this zone's slice.
  if (!alreadyV2) {
    saveDabV2Model(zoneId, model)
  }
  return model
}

// Encode + size-bound the in-memory learned model via Dabv2ModelIo and write the
// compact schema-v2 payload to ONLY this zone's slice
// (`state.zones[zoneId][DABV2_MODEL_STATE_KEY]`), leaving every other zone's
// slice byte-for-byte unchanged (R2.8). Mirrors the bounding/encoding contract
// of the flat `saveDabV2Model(Map)`; bounding is observable, never silent.
void saveDabV2Model(String zoneId, Map model) {
  def bounded = mioBound(model)
  Map zones = (state?.zones instanceof Map) ? (Map) state.zones : new LinkedHashMap()
  Map zone = (zones[zoneId] instanceof Map) ? (Map) zones[zoneId] : new LinkedHashMap()
  zone[DABV2_MODEL_STATE_KEY] = bounded.encoded
  zones[zoneId] = zone
  state.zones = zones
  if (!bounded.withinBudget) {
    log "DAB v2 model for zone ${zoneId} persisted at ${bounded.finalBytes} bytes; over " +
        "budget even after bounding steps ${bounded.stepsApplied}", 2
  } else if (!bounded.stepsApplied.isEmpty()) {
    log "DAB v2 model for zone ${zoneId} persisted at ${bounded.finalBytes} bytes after " +
        "bounding steps ${bounded.stepsApplied}", 3
  }
}

// User-initiated DAB learning reset / recalibrate (R7.3; R7.13–R7.21; design
// §R7.3). Clears the persisted learned efficiency model for the selected scope so
// stale per-room/per-vent efficiency does not misbalance a changed topology
// (R7.17) and DAB relearns from scratch on subsequent HVAC cycles (R7.16).
//
// Scope (D5 = per-Zone with an all-Zones option):
//   - scope == a zoneId               -> clears ONLY that zone's learned model
//                                         (R7.14 single-Zone, R7.15).
//   - scope == DABV2_RESET_SCOPE_ALL  -> iterates getZoneIds() and clears EACH
//                                         zone's learned model (R7.14 all-Zones).
//
// Confirmation (R7.18 / R7.20): the action clears ONLY when explicitly confirmed.
// `confirmed == true` confirms directly (button/command path passes the value);
// when `confirmed` is null the action falls back to the UI confirmation flag
// `settings.dabResetConfirm`. Without confirmation NOTHING is cleared and the
// action is a safe no-op that asks the user to confirm — so learned data is never
// cleared without an explicit user action (R7.20).
//
// Each scope clears by writing a FRESH encoded schema-v2 model into the zone's
// own slice (`state.zones[zoneId][DABV2_MODEL_STATE_KEY]`) rather than removing
// the key. Writing an empty-but-stamped model guarantees the loader returns an
// empty model on the next cycle even for the 'default' zone (whose loader would
// otherwise re-seed from the legacy device-attribute efficiency export), so
// relearning truly starts from scratch (R7.16).
//
// The device-attribute backup and the `exportEfficiencyData` /
// `mioExportModel` / `mioImportModel` backup-restore path are intentionally left
// untouched, preserving the existing backup/restore path (R7.21). On completion
// a user-visible confirmation is surfaced in `state.dabResetStatus` (R7.19).
def resetDabLearning(String scope, Boolean confirmed = null) {
  // R7.20: only an explicit, confirmed user action may clear learned data.
  boolean isConfirmed = (confirmed != null) ? confirmed.booleanValue()
      : (settings?.dabResetConfirm ? true : false)
  if (!isConfirmed) {
    state.dabResetStatus = '⚠ DAB reset requires confirmation — tick the confirmation box and retry.'
    log 'DAB reset requested without confirmation; nothing cleared (R7.20)', 2
    return state.dabResetStatus
  }

  // Resolve the scope to the concrete list of zone ids to clear (R7.14).
  List targetZoneIds = (scope == DABV2_RESET_SCOPE_ALL) ? getZoneIds() : [scope]

  // Clear each target zone's learned model by writing a fresh empty model into
  // ONLY that zone's slice (R7.15); writing a stamped empty model (not removing
  // the key) ensures the loader does not re-seed from device attributes so
  // relearning starts from scratch (R7.16).
  targetZoneIds.each { String zoneId ->
    if (zoneId != null) {
      saveDabV2Model(zoneId, mioNewModel())
    }
  }

  // R7.19: surface a completion confirmation to the user.
  String scopeLabel = (scope == DABV2_RESET_SCOPE_ALL)
      ? "all zones (${targetZoneIds.size()})" : "zone '${scope}'"
  state.dabResetStatus = ("✓ DAB learned data reset for ${scopeLabel}. " +
      'Relearning will begin on subsequent HVAC cycles.').toString()
  log "DAB learning reset for ${scopeLabel} (R7.15/R7.16)", 2
  return state.dabResetStatus
}

// One-time, idempotent schema bump that wraps today's single-thermostat install
// as the `'default'` entry of the zone-keyed model (Option A — ONE App_Instance
// hosts N zones, design §4.1/§4.3/§5.5; R8.14–8.16, R8.27, R8.28). The instance
// stays single-`structureId`, single OAuth token, and single
// `atomicState.activeRequests` budget (instance-global keys are left untouched);
// only the per-control config and learned model move under
// `state.zones['default']`.
//
// Behavior (Property 24):
//   • Relocates the learned model under
//     `state.zones['default'][DABV2_MODEL_STATE_KEY]` as a schema-v2-stamped
//     encoded payload via the lossless `mioMigrate`/`mioEncode` seam (every
//     curve/counter preserved; new fields back-filled), without re-deriving it.
//   • Moves the existing thermostat, vent assignments, conventional-vent count,
//     and safety floor into the default zone.
//   • Is idempotent: an already-wrapped shape (a `state.zones['default']`) is
//     detected and the call is a no-op, so `state.zones` is byte-for-byte stable
//     across a second migration.
//   • No re-discovery and no re-authentication; legacy DAB is retained and the
//     `balance` strategy stays opt-in.
def migrateToZoneModel() {
  // Idempotency: an already-wrapped install is left untouched (no-op).
  if (state?.zones instanceof Map && ((Map) state.zones).containsKey('default')) {
    return state.zones
  }

  Map zones = (state?.zones instanceof Map) ? (Map) state.zones : new LinkedHashMap()
  Map defaultZone = new LinkedHashMap()

  // (1) Relocate the learned model: migrate the current flat payload losslessly
  //     and store the encoded schema-v2 form under the default zone. The flat
  //     `state[DABV2_MODEL_STATE_KEY]` is left in place so the legacy DAB load
  //     path keeps working until zone-aware persistence (task 12) supersedes it.
  Map migrated = mioMigrate(state?.get(DABV2_MODEL_STATE_KEY))
  defaultZone.put(DABV2_MODEL_STATE_KEY, mioEncode(migrated))

  // (2) Relocate the single-thermostat config snapshot into the default zone.
  defaultZone.put('thermostat', settings?.thermostat1)
  defaultZone.put('safetyFloorPct', settings?.safetyFloorPct)
  defaultZone.put('additionalStandardVents', settings?.thermostat1AdditionalStandardVents)
  defaultZone.put('assignedVentIds',
    getChildDevices().findAll { it.hasAttribute('percent-open') }
                     .collect { it.getDeviceNetworkId() })

  zones.put('default', defaultZone)
  state.zones = zones
  return state.zones
}

// ------------------------------
// Device -> Zone assignment seam (R2.1-R2.5, R2.20, R2.21; design §4.1/§4.5)
// ------------------------------
// Option A: ONE App_Instance hosts N zones under `state.zones[zoneId]`. Each
// zone owns its `assignedVentIds` / `assignedPuckIds` subset. "At most one
// active Zone per device" (R2.3) is enforced directly by the uniqueness rule in
// `assignDeviceToZone`: assigning a device to a zone removes it from every other
// zone's assigned sets. A device assigned to no zone is inert (R2.20/R2.21) —
// it is never offered as commandable by any zone, so it is left at its last
// commanded position.

// True when the child device identified by `deviceId` is a vent (advertises the
// `percent-open` attribute); pucks do not. Mirrors the vent/puck split used by
// `migrateToZoneModel` and `listDiscoveredDevices`.
private boolean isVentDevice(String deviceId) {
  def device = getChildDevice(deviceId)
  return device != null && device.hasAttribute('percent-open')
}

// The full selectable candidate set offered to every zone: every vent and every
// puck (i.e. every child device) discovered in the instance/SID (R2.1, R2.2).
// Zone-independent — every zone may choose from the full set.
List getZoneSelectableDeviceIds() {
  return getChildDevices().collect { it.getDeviceNetworkId() }
}

// Ensure `state.zones` exists and `state.zones[zoneId]` has both assigned-set
// lists, returning the zone map ready for mutation.
private Map ensureZone(String zoneId) {
  Map zones = (state?.zones instanceof Map) ? (Map) state.zones : new LinkedHashMap()
  Map zone = (zones[zoneId] instanceof Map) ? (Map) zones[zoneId] : new LinkedHashMap()
  if (!(zone.assignedVentIds instanceof List)) { zone.assignedVentIds = [] }
  if (!(zone.assignedPuckIds instanceof List)) { zone.assignedPuckIds = [] }
  zones[zoneId] = zone
  state.zones = zones
  return zone
}

// Assign a device to `zoneId` and enforce single-zone membership (R2.3): the
// device is added to this zone's vent or puck assigned set (vent detected via
// the `percent-open` attribute) and removed from EVERY OTHER zone's assigned
// sets, so it belongs to at most one zone.
def assignDeviceToZone(String deviceId, String zoneId) {
  if (deviceId == null || zoneId == null) { return state?.zones }
  boolean vent = isVentDevice(deviceId)

  // Remove the device from every other zone's assigned sets (uniqueness).
  Map zones = (state?.zones instanceof Map) ? (Map) state.zones : new LinkedHashMap()
  zones.each { otherZoneId, otherZone ->
    if (otherZoneId != zoneId && otherZone instanceof Map) {
      if (otherZone.assignedVentIds instanceof List) {
        ((List) otherZone.assignedVentIds).remove(deviceId)
      }
      if (otherZone.assignedPuckIds instanceof List) {
        ((List) otherZone.assignedPuckIds).remove(deviceId)
      }
    }
  }

  // Add to the target zone's appropriate set (deduped).
  Map zone = ensureZone(zoneId)
  List target = vent ? (List) zone.assignedVentIds : (List) zone.assignedPuckIds
  if (!target.contains(deviceId)) { target << deviceId }
  return state.zones
}

// Devices that exist in the SID but are assigned to no zone (R2.20): excluded
// from all balancing and from every zone's combined-airflow computation.
List getUnassignedDeviceIds() {
  Set assigned = [] as Set
  Map zones = (state?.zones instanceof Map) ? (Map) state.zones : [:]
  zones.each { zoneId, zone ->
    if (zone instanceof Map) {
      assigned.addAll((zone.assignedVentIds ?: []) as List)
      assigned.addAll((zone.assignedPuckIds ?: []) as List)
    }
  }
  return getZoneSelectableDeviceIds().findAll { !assigned.contains(it) }
}

// The vents a zone will actually command (R2.4, R2.5, R2.21): its own assigned
// vents that still exist as child devices. A device in no zone appears in no
// zone's commandable set, so it is never commanded.
List getZoneCommandableVentIds(String zoneId) {
  Map zones = (state?.zones instanceof Map) ? (Map) state.zones : [:]
  Map zone = (zones[zoneId] instanceof Map) ? (Map) zones[zoneId] : [:]
  List assignedVents = (zone.assignedVentIds ?: []) as List
  return assignedVents.findAll { getChildDevice(it) != null }
}

// Ordered list of configured zone ids. Falls back to the single 'default' zone
// (the post-migration shape) when no zones are present yet.
List getZoneIds() {
  Map zones = (state?.zones instanceof Map) ? (Map) state.zones : [:]
  if (zones.isEmpty()) { return ['default'] }
  return new ArrayList(zones.keySet())
}

// Create a new control zone from the UI (R2 multi-zone). The config page can
// otherwise only render zones that already exist, so this is the single entry
// point that introduces a new zone id into state.zones. Steps:
//   1. Materialize the implicit 'default' zone first when no explicit zones
//      exist yet, so adding zone #2 never drops the migrated single-thermostat
//      config/model that lives under 'default'.
//   2. Derive a unique zone id from the user-supplied name (generateZoneId).
//   3. Persist the zone via ensureZone and snapshot its display name.
//   4. Re-arm per-zone schedules so the new zone gets its own evaluate job.
//   5. Clear the input so the next add starts blank (best-effort).
// A blank name is a no-op (no zone is created).
def addZoneFromUi() {
  String rawName = (settings?.newZoneName ?: '').toString().trim()
  if (!rawName) { return state?.zones }

  Map existing = (state?.zones instanceof Map) ? (Map) state.zones : [:]
  if (existing.isEmpty()) { ensureZone('default') }

  String zoneId = generateZoneId(rawName)
  Map zone = ensureZone(zoneId)
  zone.zoneName = rawName
  state.zones[zoneId] = zone

  scheduleDabV2ZoneEvaluations()

  try {
    app?.removeSetting('newZoneName')
  } catch (ignored) {
    // removeSetting unavailable (e.g. off-device harness) — leaving the field
    // populated is harmless; the next add slugs/dedupes the same name.
  }
  return state.zones
}

// Derive a unique, stable zone id from a user-supplied display name: slug to
// [a-z0-9-], collapse/trim separators, fall back to 'zone', then append
// '-2', '-3', ... until the id is not already in getZoneIds(). Uses no
// wall-clock/RNG so the same inputs are reproducible under the off-device
// harness (and on-device).
String generateZoneId(String name) {
  String base = (name ?: '').toLowerCase()
      .replaceAll('[^a-z0-9]+', '-')
      .replaceAll('^-+', '')
      .replaceAll('-+$', '')
  if (!base) { base = 'zone' }
  List existingIds = getZoneIds()
  if (!existingIds.contains(base)) { return base }
  int i = 2
  String candidate = "${base}-${i}"
  while (existingIds.contains(candidate)) {
    i++
    candidate = "${base}-${i}"
  }
  return candidate
}

// Arm one zone-scoped evaluate schedule per configured zone (R2.13/R2.14; design
// §2.2/§5.4). Each zone's job is NAMED by its zone-suffixed id
// `dabV2ZoneScheduleId(zoneId)` so it is individually addressable: the
// zone-scoped teardown cancels exactly that zone via
// `unschedule(dabV2ZoneScheduleId(zoneId))` and never cross-cancels another zone
// or instance. overwrite:true keeps at most one pending job per zone. The
// recurring adaptive cadence remains the single self-rescheduling global
// `dabV2EvaluateTick` (WA-3: avoid a proliferation of recurring timers); these
// per-zone jobs are (re)established on each lifecycle event and give the
// zone-scoped teardown a concrete, per-zone handle to cancel.
private void scheduleDabV2ZoneEvaluations() {
  Integer delaySec = (dabV2CadenceIntervalMin(false) * 60) as Integer
  // All zones share ONE static handler (runScheduledDabV2ZoneEvaluate) and carry
  // their zoneId in the scheduler `data` map, so NO dynamic per-zone handler
  // names are needed. Dynamic names previously required a `methodMissing` MOP
  // hook, which makes the Hubitat sandbox recompile the whole app pathologically
  // slowly (it routes every dynamic call in the class through the hook) — the
  // app could not be saved on-hub. Zone-scoped teardown reconciles by re-arming
  // the surviving zones rather than cancelling a per-zone-named job.
  unschedule('runScheduledDabV2ZoneEvaluate')
  getZoneIds().each { String zoneId ->
    runIn(delaySec, 'runScheduledDabV2ZoneEvaluate', [overwrite: false, data: [zoneId: zoneId]])
  }
}

// Real body for a zone's scheduled evaluate. Hubitat invokes the static handler
// `runScheduledDabV2ZoneEvaluate` and passes the scheduler `data` map, from which
// we read the zoneId. Guarded on dabEnabled and isolated so one zone's transient
// failure can never strand another (R2.7/R2.14).
void runScheduledDabV2ZoneEvaluate(Map data = null) {
  String zoneId = (data instanceof Map) ? (data.zoneId as String) : null
  if (!settings?.dabEnabled || zoneId == null) { return }
  try {
    evaluateDabV2ZoneById(zoneId)
  } catch (err) {
    logError err
  }
}

// Hubitat invokes a scheduled job by its handler-method NAME. Per-zone evaluates
// use the single static handler `runScheduledDabV2ZoneEvaluate` (above) with the
// zoneId carried in the scheduler `data` map, so there are NO dynamic handler
// names and therefore NO `methodMissing` MOP hook (its presence forced the
// sandbox to route every dynamic call in the class through it, which made the
// app impossible to save on-hub).

// Reassign a device into `targetZoneId` (R2.4, Property 23). Enforces the
// existing single-zone uniqueness rule by delegating to `assignDeviceToZone`,
// and for every source zone the device just left, cancels that source zone's
// zone-scoped scheduled move (`unschedule(dabV2ZoneScheduleId(sourceZoneId))`)
// so the source zone issues no further command for it and no orphaned schedule
// keeps actuating it (design §5.4 "Device reassignment Zone A->B"). The source
// zones are computed BEFORE reassigning, since `assignDeviceToZone` strips the
// device from every other zone. No command is issued: the device is left at its
// last commanded position and the target zone picks it up on its next evaluation.
def reassignDeviceToZone(String deviceId, String targetZoneId) {
  if (deviceId == null || targetZoneId == null) { return state?.zones }

  // Move the device into the target zone (uniqueness rule removes it from the
  // source zones' assigned sets).
  assignDeviceToZone(deviceId, targetZoneId)

  // Reconcile per-zone schedules: re-arm one job per surviving zone so the
  // device's former zone(s) re-evaluate WITHOUT it and no orphaned schedule keeps
  // actuating it. (Single static handler + data map means we reconcile by
  // re-arming rather than cancelling a per-zone-named job.)
  scheduleDabV2ZoneEvaluations()
  return state.zones
}

// Zone-scoped teardown on zone removal (R2.13/R2.14, Property 23; design §5.4
// "Zone-scoped teardown on zone removal"): cancel ONLY this zone's named
// schedule and clear ONLY `state.zones[zoneId]` (and that zone's zone-suffixed
// settings, if any), leaving every other zone's schedule and `state.zones[*]`
// entry untouched. Only `uninstalled()` tears everything down.
def removeZone(String zoneId) {
  if (zoneId == null) { return state?.zones }

  // Clear only this zone's slice of state.zones.
  Map zones = (state?.zones instanceof Map) ? (Map) state.zones : new LinkedHashMap()
  zones.remove(zoneId)
  state.zones = zones

  // Re-arm per-zone schedules for the SURVIVING zones only; the removed zone gets
  // no job (it is no longer in getZoneIds()), so its scheduled evaluate is gone
  // and no other zone is affected.
  scheduleDabV2ZoneEvaluations()

  // Best-effort clear of this zone's zone-suffixed settings (never fatal: the
  // sandbox may not expose removeSetting, and the canonical state is state.zones).
  try {
    ['zoneName', 'zoneThermostat', 'zoneAssignedDevices', 'zoneAdditionalStandardVents'].each { String prefix ->
      String key = "${prefix}_${zoneId}"
      if (settings?.containsKey(key)) { app?.removeSetting(key) }
    }
  } catch (ignored) {
    // removeSetting unavailable (e.g. off-device harness) — state teardown above
    // is authoritative.
  }
  return state.zones
}

// Render the single-page repeated per-zone configuration sections (R2.6, R2.9,
// R2.12; design §4.1/§4.5). One section per zone shows the per-zone display
// name (R2.12), the controlling thermostat (R2.6), the conventional-vent count
// (R2.9), and the device-assignment selector drawn from the full selectable set
// (R2.1/R2.2). The selection is persisted into `state.zones[zoneId]` via
// `applyZoneAssignmentSelection`, which enforces the single-zone uniqueness rule
// (R2.3). Unassigned devices are surfaced as inert (R2.20/R2.21).
def renderZoneConfigSections() {
  if (!settings?.dabEnabled) { return }
  List selectable = getZoneSelectableDeviceIds()
  Map selectableOptions = [:]
  selectable.each { id ->
    def device = getChildDevice(id)
    selectableOptions[id] = device ? device.getLabel() : id
  }

  // Add-zone affordance (R2.6/R2.12): without this control the page only renders
  // zones that already exist in state.zones, and getZoneIds() falls back to the
  // single implicit 'default' zone — so a user could never create a second zone.
  // The button is handled by appButtonHandler -> addZoneFromUi().
  section('Add a zone') {
    input name: 'newZoneName', type: 'text', title: 'New zone name',
          required: false, submitOnChange: false
    input name: 'addZone', type: 'button', title: 'Add zone', submitOnChange: true
    paragraph '<small>Create an additional control zone (its own thermostat, assigned vents/pucks, ' +
              'safety floor, and learned model). After adding, assign devices to it below; each vent ' +
              'or puck belongs to at most one zone.</small>'
  }

  getZoneIds().each { zoneId ->
    Map zone = ((state?.zones instanceof Map) && (state.zones[zoneId] instanceof Map)) ? (Map) state.zones[zoneId] : [:]
    String currentName = zone.zoneName ?: (settings?."zoneName_${zoneId}" ?: zoneId)
    section("Zone: ${currentName}") {
      input name: "zoneName_${zoneId}", type: 'text', title: 'Zone display name',
            defaultValue: (zone.zoneName ?: zoneId), submitOnChange: true
      input name: "zoneThermostat_${zoneId}", type: 'capability.thermostat',
            title: 'Controlling thermostat for this zone', multiple: false, required: false, submitOnChange: true
      input name: "zoneAdditionalStandardVents_${zoneId}", type: 'number',
            title: 'Count of conventional Vents for this zone', defaultValue: 0, submitOnChange: true
      input name: "zoneDevices_${zoneId}", type: 'enum',
            title: 'Vents and pucks assigned to this zone',
            options: selectableOptions, multiple: true, required: false, submitOnChange: true
      paragraph '<small>Each vent or puck belongs to at most one zone; assigning it here removes it ' +
                'from any other zone. Devices left unassigned are not balanced or commanded.</small>'
      applyZoneAssignmentSelection(zoneId)
    }
  }

  List unassigned = getUnassignedDeviceIds()
  if (!unassigned.isEmpty()) {
    section('Unassigned devices') {
      String names = unassigned.collect { selectableOptions[it] ?: it }.join(', ')
      paragraph "<small>These devices belong to no zone and are left inert (not balanced, " +
                "not commanded): ${names}</small>"
    }
  }
}

// Persist the per-zone device multi-select into `state.zones[zoneId]`, honoring
// the single-zone uniqueness rule for every selected device (R2.3). Stores the
// per-zone display name and conventional-vent count snapshot as well (R2.9/R2.12).
private void applyZoneAssignmentSelection(String zoneId) {
  Map zone = ensureZone(zoneId)
  zone.zoneName = settings?."zoneName_${zoneId}" ?: zoneId
  zone.additionalStandardVents = settings?."zoneAdditionalStandardVents_${zoneId}" ?: 0
  def selectedRaw = settings?."zoneDevices_${zoneId}"
  if (selectedRaw == null) { return }
  List selected = (selectedRaw instanceof List) ? selectedRaw : [selectedRaw]
  selected.each { deviceId -> assignDeviceToZone(deviceId.toString(), zoneId) }
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

// R6.2 fan-on-while-idle fallback. The circulation seam (`dabV2CirculationResult`)
// only sees the resolved ACTION, so the fallback is translated HERE, where the
// fan mode is still available: an `idle` operating state with the fan forced `on`
// is handed downstream as the canonical `fan only` action (R6.1) so the
// circulation path can engage. Every other combination passes the raw operating
// state through unchanged. Delegates the detection itself to the pure library
// (`dabv2DetectCirculation`) so R6.1/R6.2/R6.4 semantics live in exactly one
// place. PURE on its inputs (no platform reads) so it is directly unit-testable.
String resolveThermostatEvaluateAction(String operatingState, String fanMode) {
  if (dabv2DetectCirculation(operatingState, fanMode)) { return 'fan only' }
  return operatingState
}

// Resolve the effective DAB v2 evaluate action for a thermostat DEVICE: the raw
// `thermostatOperatingState`, with the R6.2 fan-on-while-idle fallback folded in
// via `resolveThermostatEvaluateAction`. Both attributes belong to Hubitat's
// standard Thermostat capability, so `currentValue` simply returns null when a
// device never reported one — which degrades to the raw operating state.
String thermostatEvaluateAction(thermostat) {
  if (thermostat == null) { return null }
  String operatingState = thermostat.currentValue('thermostatOperatingState')
  String fanMode = thermostat.currentValue('thermostatFanMode')
  return resolveThermostatEvaluateAction(operatingState, fanMode)
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
    // Multi-zone routing (review on 51912e9): the recurring cadence tick is the
    // only self re-arming evaluate driver (the per-zone runIn jobs are one-shot
    // lifecycle kicks), so when explicit zones exist it must take the
    // zoned/chunked path — each zone with its OWN thermostat and model slice.
    // The flat path (global thermostat1 + flat model) remains for the
    // single-implicit-default-zone install so its model storage is unchanged.
    if (dabV2HasExplicitZones()) {
      runDabV2ZonedEvaluate()
    } else {
      runDabV2BalanceEvaluate()
    }
  } else {
    evaluateRebalancingVents()
  }
}

// True when the install has explicitly configured zones: more than one zone id,
// or any zone other than the implicit post-migration 'default'. Such installs
// must evaluate per zone; a bare/default-only install keeps the flat path.
private boolean dabV2HasExplicitZones() {
  Map zones = (state?.zones instanceof Map) ? (Map) state.zones : [:]
  if (zones.isEmpty()) { return false }
  return zones.size() > 1 || !zones.containsKey('default')
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
    // R6.2: resolved WITH the fan mode so an idle+fan-on state reaches the
    // circulation path as the canonical 'fan only' action.
    def action = thermostatEvaluateAction(settings?.thermostat1)
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
      // R13/R14 (forum #392): publish the opt-in diagnostic surfaces for this
      // evaluation. No-op unless 'Create diagnostic devices' is enabled; guarded
      // internally so a diagnostics failure can never break balancing.
      publishDabV2Diagnostics(zoneResult, roomData, loadDabV2Model(), setpointC)
      return zoneResult
    }
  } catch (err) {
    logError err
  }
}

// === Zone-iterating evaluate loop (Option A; R2.4/R2.5/R2.7/R2.8, R8.26; design §5.3) ===
//
// The multi-zone evaluate entry point. It iterates the configured zones
// (`state.zones[zoneId]`) and runs ONE independent DAB control loop per zone
// (R2.7), each reading/writing ONLY its own learned model entry (R2.8) and
// commanding/counting ONLY its own assigned vents (R2.4/R2.5). To respect the
// Hubitat ~20s method budget (R8.26) the loop CHUNKS across zones: a bounded
// number of zones is processed per invocation and the remainder is continued on
// a fresh scheduled invocation via `runInMillis`, so a home with many zones
// never blocks past the budget.
def runDabV2ZonedEvaluate() {
  if (!settings?.dabEnabled) { return }
  List zoneIds = getZoneIds()
  if (!zoneIds) { return }
  runDabV2ZonedEvaluateChunk([zoneQueue: new ArrayList(zoneIds)])
}

// Process up to `DABV2_ZONES_PER_CHUNK` zones from the pending queue, then
// reschedule itself for the remainder. The queue is carried forward in the
// scheduled-handler `data` so each invocation stays well within the method
// budget. A per-zone failure is isolated (logged) so it can never strand the
// remaining zones or the continuation.
def runDabV2ZonedEvaluateChunk(Map data = null) {
  if (!settings?.dabEnabled) { return }
  List queue = (data?.zoneQueue instanceof List) ? new ArrayList((List) data.zoneQueue) : getZoneIds()
  int processed = 0
  while (!queue.isEmpty() && processed < DABV2_ZONES_PER_CHUNK) {
    String zoneId = queue.remove(0)?.toString()
    if (zoneId != null) {
      try {
        evaluateDabV2ZoneById(zoneId)
      } catch (err) {
        logError err
      }
    }
    processed++
  }
  if (!queue.isEmpty()) {
    runInMillis(DABV2_ZONE_CHUNK_DELAY_MS, 'runDabV2ZonedEvaluateChunk',
                [overwrite: true, data: [zoneQueue: queue]])
  }
}

// Run one independent DAB control loop for a single zone. Builds the PURE
// per-room inputs from ONLY this zone's commandable vents (R2.4/R2.5), loads and
// (in the finally block) persists ONLY this zone's learned model (R2.8), and
// dispatches the zone's own targets. Wrapped so a transient evaluate failure
// still leaves the zone's model slice stamped at schema v2.
private void evaluateDabV2ZoneById(String zoneId) {
  Map zones = (state?.zones instanceof Map) ? (Map) state.zones : [:]
  Map zone = (zones[zoneId] instanceof Map) ? (Map) zones[zoneId] : [:]

  // Each zone reads and writes ONLY its own learned model (R2.8).
  Map model = loadDabV2Model(zoneId)

  // Restrict topology to ONLY this zone's commandable vents (R2.4/R2.5): a
  // device outside the zone's assigned set is neither commanded nor counted.
  Set commandable = (getZoneCommandableVentIds(zoneId) ?: []) as Set
  Map zoneVentsByRoomId = buildZoneVentsByRoomId(commandable)

  try {
    if (!zoneVentsByRoomId.isEmpty()) {
      def thermostat = (zone?.thermostat != null) ? zone.thermostat : settings?.thermostat1
      // R6.2: resolved WITH the fan mode so an idle+fan-on state reaches the
      // circulation path as the canonical 'fan only' action.
      def action = thermostatEvaluateAction(thermostat)
      String mode = resolveDabV2HvacAction(action)
      // Setpoint is read in the active conditioning direction (idle/fan zones
      // still need it for the bounded pre-adjust trigger).
      String setpointMode = (mode == HEATING) ? HEATING : COOLING
      BigDecimal setpointC = getThermostatSetpoint(setpointMode)
      if (setpointC != null) {
        List roomData = gatherDabV2ZoneRoomData(zoneVentsByRoomId, mode)
        if (roomData) {
          Map ctxInputs = buildDabV2ContextInputs()
          Map prevCycle = (zone?.dabV2Cycle in Map) ? (Map) zone.dabV2Cycle : null
          // Floor THIS zone on its OWN safetyFloorPct (read from its own slice)
          // so sfApply enforces the zone's own floor over only its own vents —
          // no cross-zone airflow accumulator (R2.10/R2.11/R8.3).
          Map zoneConfig = getDabV2ZoneConfig(zoneId)
          Map zoneResult = evaluateDabV2Zone(roomData, setpointC, action, zoneConfig, ctxInputs, prevCycle)
          // Persist this zone's OWN cycle bookkeeping under its OWN slice so one
          // zone's cycle state never bleeds into another's.
          zone.dabV2Cycle = [mode: zoneResult.mode, cycleId: zoneResult.cycleId,
              lastActiveMode: zoneResult.lastActiveMode, anchorTargets: zoneResult.anchorTargets,
              idleSinceMs: zoneResult.idleSinceMs, predictedSpreadC: zoneResult.predictedSpreadC]
          zones[zoneId] = zone
          state.zones = zones
          dispatchDabV2Targets(zoneResult, roomData)
          // R13/R14 (forum #392): publish the opt-in diagnostics from this
          // zone's OWN model. Per-room devices are room-keyed so zones coexist;
          // the single summary device reflects the most recently evaluated zone.
          publishDabV2Diagnostics(zoneResult, roomData, model, setpointC)
        }
      }
    }
  } finally {
    // Persist this zone's model back to ONLY its own slice (R2.8). Even on a
    // no-op cycle this keeps the slice stamped at schema v2.
    saveDabV2Model(zoneId, model)
  }
}

// Build a room-id -> [ventIds] map restricted to ONLY the given commandable vent
// ids, derived from the instance-global discovered topology
// (`atomicState.ventsByRoomId`). A room contributes only the vents that belong
// to this zone; a room with none of the zone's vents is omitted entirely, so the
// zone never reads or counts another zone's devices (R2.4/R2.5).
private Map buildZoneVentsByRoomId(Set commandable) {
  Map out = new LinkedHashMap()
  if (!commandable) { return out }
  def byRoom = atomicState?.ventsByRoomId
  if (!(byRoom instanceof Map)) { return out }
  ((Map) byRoom).each { roomId, ventIds ->
    List ids = (ventIds in List) ? (List) ventIds : [ventIds]
    List mine = ids.findAll { commandable.contains(it) }
    if (!mine.isEmpty()) { out.put(roomId, mine) }
  }
  return out
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
      // R1.21 defer: a room with no resolvable temperature is skipped this
      // cycle (never commanded on missing data) rather than fed a fabricated 0.
      if (tempC == null) {
        log "Deferring zone room ${roomId}: no resolvable temperature", 2
        return
      }
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

// A VALID (2xx) response may still carry NO JSON body. Flair PATCH responses
// occasionally come back empty during cloud hiccups, and Hubitat's
// AsyncResponse.getJson() then throws IllegalArgumentException ("No json exists
// for response", forum #396) - which aborted the rest of the handler. Parse
// defensively: a missing/unparseable body degrades to null and the caller
// decides what still applies.
private safeGetJson(resp, String context) {
  try {
    return resp?.getJson()
  } catch (Exception e) {
    log "${context}: response carried no parseable JSON body (${e.message ?: e})", 2
    return null
  }
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

// === DAB v2 circulation (fan-only) path (Task 8.2; R6) ===
//
// When the controlling thermostat reports a circulation operating state
// (`dabv2DetectCirculation`, R6.1/R6.2) AND circulation is enabled (R6.9), open
// the zone's vents to the configured circulation % (R6.3/R6.5/R6.6). Honors the
// inactive-room interaction: when "close vents on inactive rooms" is enabled only
// active-room vents are targeted; otherwise every vent is (R6.12/R6.13). The
// per-vent targets are routed through the single Safety_Floor choke point so the
// floor always wins (R6.10/R6.11) — `sfApply` raises the targeted vents until the
// commanded combined airflow reaches the floor. Returns null when circulation is
// disabled or the operating state is not circulation, so the caller falls back to
// the normal idle / pre-adjust path. Circulation is a NON-conditioning action
// (treated as DABV2_ACTION_IDLE): no efficiency sample is recorded (R6.14, gated
// in finalizeRoomStates). On a transition back to heating/cooling the normal DAB
// path resumes on the next evaluation (R6.15).
private Map dabV2CirculationResult(action, List roomData, BigDecimal setpointC,
    Map cfg, Map ctxInputs, Map prevCycle) {
  if (!coerceBoolean(cfg?.circulationEnabled, false)) { return null }
  String operatingState = (action == null ? null : action.toString())
  // fanMode is not surfaced at this seam; the canonical fan-only operating state
  // (R6.1) is sufficient. The fan-on+idle fallback (R6.2) is detected upstream by
  // `thermostatEvaluateAction` (both evaluate entry points), where the fan mode
  // is available, and handed in as a 'fan only' action.
  if (!dabv2DetectCirculation(operatingState, null)) { return null }

  BigDecimal circPct = (cfg?.circulationOpenPct ?: CIRCULATION_OPEN_DEFAULT) as BigDecimal
  boolean closeInactive = coerceBoolean(cfg?.closeInactiveRooms, true)

  // Build the per-room circulation candidates keyed by roomId (room-keyed so the
  // result threads through sfApply, which indexes rooms by roomId). Each room's
  // per-room active flag drives the inactive-room interaction in the pure helper.
  Map srcByRoom = [:]
  List circIds = []
  Map activeById = [:]
  (roomData ?: []).each { rd ->
    if (rd == null) { return }
    String rid = rd.roomId == null ? null : rd.roomId.toString()
    if (rid == null) { return }
    srcByRoom[rid] = rd
    circIds << rid
    activeById[rid] = coerceBoolean(rd.active, false) ? Boolean.TRUE : Boolean.FALSE
  }

  // Pure circulation targets (R6.3/R6.10–R6.13): active-room vents (or all when
  // not closing inactive) -> circulation %.
  Map targets = dabv2CirculationTargets(circIds, circPct, activeById, closeInactive)
  if (targets.isEmpty()) { return null }

  // The circulating rooms ARE the zone's device set for the floor math. Mark them
  // active with a positive signed error so the Safety_Floor can raise them to the
  // floor (the circulation targets carry no comfort error of their own). Inactive
  // contribution is zeroed — circulation explicitly decides which vents open.
  List sfRooms = []
  targets.keySet().each { rid ->
    Map src = (Map) srcByRoom[rid]
    List vids = (src?.ventIds ?: [rid])
    BigDecimal cur = isDabV2UsableNumber(src?.currentOpen) ? (src.currentOpen as BigDecimal) : 0
    sfRooms << [roomId: rid, active: true, signedErrorC: 1.0d, currentOpen: cur, ventIds: vids]
  }
  def settings = dabV2AllocSettings(cfg, [sum: 0, count: 0])

  def floored = sfApply(targets, sfRooms, settings)
  Map safeTargets = floored[0] as Map
  Set floorRequiredRooms = computeFloorRequiredVentIds(targets, safeTargets)
  String lastActiveMode = prevCycle?.lastActiveMode ?: prevCycle?.mode
  Map anchor = (prevCycle?.anchorTargets in Map) ? (Map) prevCycle.anchorTargets
             : (prevCycle?.targets in Map ? (Map) prevCycle.targets : null)

  return [balancing: true, action: DABV2_ACTION_IDLE, circulation: true,
          mode: DABV2_ACTION_IDLE, targets: safeTargets,
          floorBinding: floored[1] as boolean, airflowLimited: ([] as Set),
          floorRequiredRooms: floorRequiredRooms, predictedSpreadC: 0,
          combinedOpenPct: dabV2CombinedOpenPct(safeTargets, sfRooms, settings),
          cycleId: (prevCycle?.cycleId ?: 0L), newAnchor: false, reusedAnchor: false,
          lastActiveMode: lastActiveMode, idleSinceMs: null, anchorTargets: anchor,
          inactiveClosed: dabV2InactiveClosed(roomData, closeInactive)]
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
    // R6 fan-only / circulation awareness (off by default, R6.9). When enabled
    // and the thermostat reports a circulation operating state, open the zone's
    // vents to the circulation % and route through sfApply so the floor wins
    // (R6.3/R6.10/R6.11). Circulation is a non-conditioning action — it records
    // no efficiency sample (gated in finalizeRoomStates, R6.14).
    Map circ = dabV2CirculationResult(action, roomData, setpointC, cfg, ctxInputs, prevCycle)
    if (circ != null) { return circ }
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
  // R7.4 minimum vent opening, applied BEFORE the final sfApply so the documented
  // precedence holds: safety floor > inactive-room close > configured minimum
  // opening. Inactive-closed groups are left untouched (the minimum never
  // overrides the inactive-room close); the floor below may still reopen them.
  Map withMin = dabv2ApplyMinOpening(normalized, rooms,
    cfg.minVentOpenGlobalPct, (cfg.minVentOpen instanceof Map ? (Map) cfg.minVentOpen : [:]),
    coerceBoolean(cfg.closeInactiveRooms, true))
  def floored = sfApply(withMin, rooms, settings)
  Map safeTargets = floored[0] as Map

  // Per-room "must open to MEET the floor" set: a room whose floor-padded target
  // exceeds its pre-floor (balance) target had airflow added solely to satisfy
  // the floor, so its move bypasses anti-chatter on dispatch (R6.2/R10.5).
  Set floorRequiredRooms = computeFloorRequiredVentIds(withMin, safeTargets)

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
  // R7.4 minimum opening before the final sfApply (precedence preserved on reuse).
  Map withMin = dabv2ApplyMinOpening(anchorTargets, rooms,
    cfg.minVentOpenGlobalPct, (cfg.minVentOpen instanceof Map ? (Map) cfg.minVentOpen : [:]),
    coerceBoolean(cfg.closeInactiveRooms, true))
  def floored = sfApply(withMin, rooms, settings)
  Map safeTargets = floored[0] as Map
  Set floorRequiredRooms = computeFloorRequiredVentIds(withMin, safeTargets)
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
// Combined airflow (per-vent mean) of a dispatch candidate plan: APPLIED groups
// contribute their target, HELD groups their current aperture. Extracted from a
// nested closure inside dabV2PlanGroupDispatch into a top-level for-loop method:
// a closure-containing-closure made the Hubitat sandbox recompile the whole app
// pathologically slowly. Pure arithmetic, no Hubitat APIs.
private double dabV2DispatchedCombinedAirflow(List candidates) {
  double sum = 0.0d
  int vents = 0
  for (d in (candidates ?: [])) {
    int nv = Math.max(1, (((d.ventIds ?: []) as List).size()))
    double v = d.apply ? (d.target as double) : (d.currentOpen as double)
    sum += v * nv
    vents += nv
  }
  return vents > 0 ? (sum / vents) : 0.0d
}

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

  // === Fail-safe-to-floor escalation (Task 6.6; R4.11/R4.12/R4.13/R4.14) ======
  // `computeFloorRequiredVentIds` only flags groups the Safety_Floor RAISED above
  // their allocator base target. A "load-bearing ordinary open" (a high safe
  // target on a currently-low vent that the floor silently depends on) is NOT
  // flagged, so the per-cycle cap / anti-chatter cooldown can HOLD it — leaving
  // the actually-dispatched plan (APPLIED groups -> their target, HELD groups ->
  // their current aperture) below the floor (vents "stuck closed" under
  // throttling). After the ordinary cap/cooldown decisions, reconstruct the
  // dispatched plan's combined airflow and, while it sits below the floor:
  //   Phase 1 — APPLY held load-bearing OPENS (largest airflow gain first) at
  //             their comfort target; and if comfort capacity is exhausted,
  //   Phase 2 — OPEN vents BEYOND their comfort target toward 100 (most-headroom
  //             first) as the inviolable-floor override.
  // Escalated/opened groups are promoted to floor-required (`mustOpen`) so they
  // bypass the cap AND the cooldown exactly like an sfApply-forced open
  // (R4.13/R4.14) and are fail-open retried on a failed PATCH. Ordinary
  // non-load-bearing moves (the floor is met without them) stay held/capped,
  // preserving burst coalescing (R4.9).
  //
  // The floor threshold is `opts.floorPct` when the orchestrator supplies it
  // (`dispatchDabV2Targets` passes the zone's clamped safety floor — this is what
  // enables the Phase-2 open-beyond-comfort override); otherwise it is the SAFE
  // plan's own combined airflow — every group at its safe target — which the
  // single `sfApply` choke point already guarantees meets the floor, so never
  // dropping below it guarantees the dispatched plan never drops below the floor
  // (and Phase 2 never triggers without an explicit floor). Combined airflow here
  // is the per-vent mean over the plan's groups (conventional/inactive vents only
  // ADD airflow, so omitting them is conservative — it can only over-open).
  final double ESC_EPS = 1e-9d

  double floorThreshold
  if (opts?.floorPct instanceof Number &&
      !Double.isNaN(((Number) opts.floorPct).doubleValue()) &&
      !Double.isInfinite(((Number) opts.floorPct).doubleValue())) {
    floorThreshold = ((Number) opts.floorPct).doubleValue()
  } else {
    double sum = 0.0d
    int vents = 0
    candidates.each { d ->
      int nv = Math.max(1, ((d.ventIds ?: []).size()))
      sum += (d.target as double) * nv
      vents += nv
    }
    floorThreshold = vents > 0 ? (sum / vents) : 0.0d
  }

  int floorStep = (opts?.granularity != null && (opts.granularity as int) > 0) ? (opts.granularity as int) : 5
  int escGuard = 0
  int escMax = (candidates.size() + 1) * ((int) (100 / floorStep) + 2)
  while (dabV2DispatchedCombinedAirflow(candidates) < floorThreshold - ESC_EPS && escGuard < escMax) {
    escGuard++
    // Phase 1 — cheapest first: APPLY a held OPEN at its existing (comfort) target.
    // Largest airflow gain first so the fewest moves close the floor gap. A held
    // close (target <= current) never helps, so it is skipped.
    def flip = null
    double flipGain = 0.0d
    candidates.each { d ->
      if (d.apply) { return }
      double gain = (d.target as double) - (d.currentOpen as double)
      if (gain <= ESC_EPS) { return }
      double weighted = gain * Math.max(1, ((d.ventIds ?: []).size()))
      if (flip == null || weighted > flipGain) { flip = d; flipGain = weighted }
    }
    if (flip != null) { flip.apply = true; flip.mustOpen = true; continue }

    // Phase 2 — fail-safe-to-floor (R4.11/R4.12; AGENTS.md priority #1): comfort
    // capacity is exhausted but the floor is STILL unmet, so OPEN a vent BEYOND
    // its allocator target, one grid step at a time, most-headroom first
    // (deterministic roomId tie-break). Over-conditioning is the lesser evil
    // versus leaving the system below the airflow-safety floor under throttling.
    // The comfort-biased sfApply choke point is untouched; this dispatch-level
    // override only ever RAISES airflow and only when an explicit floor is set.
    def best = null
    double bestHead = 0.0d
    candidates.each { d ->
      double cur = d.apply ? (d.target as double) : (d.currentOpen as double)
      double head = 100.0d - cur
      if (head <= ESC_EPS) { return }
      if (best == null || head > bestHead ||
          (head == bestHead && (d.roomId as String) < (best.roomId as String))) {
        best = d; bestHead = head
      }
    }
    if (best == null) { break }   // every vent already wide open — nothing more to give
    double baseVal = best.apply ? (best.target as double) : (best.currentOpen as double)
    best.target = Math.min(100.0d, baseVal + floorStep)
    best.apply = true
    best.mustOpen = true
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
  // Supply the zone's clamped safety floor so the planner can escalate any
  // load-bearing ordinary open the cap/cooldown would otherwise hold below the
  // floor (Task 6.6; R4.11-R4.14). Caller-supplied opts (e.g. maxMovesPerCycle)
  // are preserved; an explicit floorPct in opts wins.
  Map planOpts = (opts == null) ? [:] : new HashMap(opts)
  if (planOpts.floorPct == null) {
    planOpts.floorPct = sfClampSafetyFloor(settings?.safetyFloorPct)
  }
  if (planOpts.granularity == null && settings?.ventGranularity) {
    planOpts.granularity = settings.ventGranularity.toInteger()
  }
  List plan = dabV2PlanGroupDispatch(safeTargets, floorReq, rooms, lastGroup, nowMs, planOpts)

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
// values — activity, temperature, signed error-to-setpoint, proposed vent open %,
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
    // Room activity is surfaced per room and used by the summary to keep
    // inactive outliers out of the zone-wide max error (review on 51912e9).
    attrs.active = coerceBoolean(rd.active, false)
    if (isDabV2UsableNumber(rd.tempC)) {
      BigDecimal t = (rd.tempC as BigDecimal)
      attrs.temperature = roundBigDecimal(t, 2)
      if (setpointC != null) {
        BigDecimal signed = heating ? ((setpointC as BigDecimal) - t) : (t - (setpointC as BigDecimal))
        attrs.signedErrorC = roundBigDecimal(signed, 2)
      }
    }
    // The PROPOSED aperture from this evaluation. Deliberately not named
    // "commanded": the dispatch layer downstream may suppress the actual move
    // (anti-chatter cooldown, batching, stale-cycle and idempotency gates), so
    // this is the evaluation's plan, not a confirmation the vent moved.
    if (targets.containsKey(roomId)) {
      attrs.proposedOpenPct = roundBigDecimal((targets[roomId] as BigDecimal), 1)
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

// Max ACTIVE-room |signed error| across the per-room diagnostics (R14.1).
// Inactive rooms are excluded so a deliberately unconditioned outlier (closed
// guest room, open-window room) cannot dominate the zone summary.
private BigDecimal dabV2MaxAbsError(Map roomDiag) {
  if (!roomDiag) { return 0 }
  BigDecimal worst = 0
  roomDiag.each { rid, attrs ->
    if (attrs?.signedErrorC != null && coerceBoolean(attrs.active, true)) {
      BigDecimal e = (attrs.signedErrorC as BigDecimal).abs()
      if (e > worst) { worst = e }
    }
  }
  return worst
}

// Map an evaluation result to the hold/recalculating/idle status enum (R14.1):
// idle when not balancing; recalculating when the EVALUATION produced a new plan
// (a floor-required open or a fresh cycle anchor); otherwise hold. This is the
// evaluation's status, not an actuation receipt — the dispatch layer may still
// suppress the physical move (cooldown/batching/idempotency gates).
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
  // Strategy-comparison metrics come from the learning store (model.metrics)
  // unless the caller supplies an explicit override; an empty store simply
  // omits the comparison fields (review on 51912e9).
  Map effectiveMetrics = metrics
  if (effectiveMetrics == null && model?.metrics in Map && !((Map) model.metrics).isEmpty()) {
    effectiveMetrics = (Map) model.metrics
  }
  Map roomDiag = gatherDabV2RoomDiagnostics(zoneResult, roomData, model, setpointC)
  // Create devices for THIS publish; prune ONLY against the authoritative
  // whole-topology room set. A zoned install publishes one zone at a time, so
  // pruning against the publish set would delete every other zone's devices,
  // and a room with a transiently unreadable temperature would be treated as a
  // topology removal (review findings on 51912e9).
  dabV2EnsureRoomDiagnosticDevices(roomDiag.keySet())
  dabV2PruneStaleRoomDiagnosticDevices(dabV2AllManagedRoomIds())
  roomDiag.each { roomId, attrs ->
    def dev = getChildDevice(DABV2_DIAG_ROOM_DNI_PREFIX + roomId)
    if (dev == null) { return }
    attrs.each { name, value -> safeSendEvent(dev, [name: name, value: value]) }
  }
  // Maintain the 24 h counters from this observation, then publish the summary.
  String status = dabV2DiagnosticStatus(zoneResult)
  dabV2RecordObservation(status, (nowMs != null ? nowMs : now()) as Long)
  def summaryDev = dabV2EnsureSummaryDevice()
  Map sum = gatherDabV2SystemSummary(zoneResult, roomDiag, dabV2Counters(), effectiveMetrics)
  if (summaryDev != null) {
    sum.each { name, value -> safeSendEvent(summaryDev, [name: name, value: value]) }
  }
  // Mirror the same values into `state` so the app status page can render them
  // even where a dashboard / child device is not used (design R13/R14 surface 1).
  // Rooms are MERGED across publishes (zones publish one at a time) and filtered
  // to the authoritative topology so departed rooms drop out of the mirror too.
  if (state != null) {
    state.dabV2Diagnostics = [summary: sum, rooms: dabV2MergedRoomsMirror(roomDiag),
                              updatedMs: (nowMs != null ? nowMs : now())]
  }
}

// The authoritative set of managed room ids across ALL zones, from the
// discovered topology (`atomicState.ventsByRoomId`). Returns null when the
// topology is unknown (pre-discovery) so callers skip pruning rather than
// treating "unknown" as "empty".
private Set dabV2AllManagedRoomIds() {
  def byRoom = atomicState?.ventsByRoomId
  if (!(byRoom instanceof Map) || ((Map) byRoom).isEmpty()) { return null }
  return ((Map) byRoom).keySet().collect { it?.toString() } as Set
}

// Create any missing per-room diagnostic devices for THIS publish (create-only;
// idempotent, an existing device is reused).
private void dabV2EnsureRoomDiagnosticDevices(Set roomIds) {
  (roomIds ?: ([] as Set)).each { roomId ->
    String dni = DABV2_DIAG_ROOM_DNI_PREFIX + roomId
    if (getChildDevice(dni) == null) {
      dabV2SafeAddChildDevice(DABV2_DIAG_ROOM_DRIVER, dni, "DAB Diagnostics ${roomId}".toString())
    }
  }
}

// Delete per-room diagnostic devices whose room has ACTUALLY left the topology
// (R14.6). `authoritativeRoomIds` is the whole-topology room set; null means the
// topology is unknown, in which case nothing is pruned.
private void dabV2PruneStaleRoomDiagnosticDevices(Set authoritativeRoomIds) {
  if (authoritativeRoomIds == null) { return }
  Set wanted = authoritativeRoomIds.collect { DABV2_DIAG_ROOM_DNI_PREFIX + it } as Set
  getChildDevices().each { dev ->
    String dni = dev?.getDeviceNetworkId()
    if (dni != null && dni.startsWith(DABV2_DIAG_ROOM_DNI_PREFIX) && !wanted.contains(dni)) {
      dabV2SafeDeleteChildDevice(dni)
    }
  }
}

// Merge this publish's per-room diagnostics over the previously mirrored rooms
// (zones publish one at a time), dropping rooms that left the topology.
private Map dabV2MergedRoomsMirror(Map roomDiag) {
  Map prior = (state?.dabV2Diagnostics in Map && ((Map) state.dabV2Diagnostics).rooms in Map) ?
      new LinkedHashMap((Map) ((Map) state.dabV2Diagnostics).rooms) : [:]
  prior.putAll(roomDiag ?: [:])
  Set authoritative = dabV2AllManagedRoomIds()
  if (authoritative != null) {
    prior.keySet().retainAll(authoritative)
  }
  return prior
}

// Transition cleanup (R14.6; review on 51912e9): when the diagnostics surface
// is no longer active — the toggle turned off, DAB disabled, or the control
// strategy moved off `balance` (legacy publishes nothing) — remove every
// diagnostic child device and the mirrored state so nothing stale stays
// visible. Runs from initialize() so every settings save reconciles; a no-op
// while the surface is active or when nothing exists to remove.
private void dabV2CleanupDiagnosticsIfDisabled() {
  boolean active = dabV2DiagnosticsEnabled() &&
      coerceBoolean(settings?.dabEnabled, false) &&
      getDabV2ControlStrategy() == STRATEGY_BALANCE
  if (active) { return }
  int removed = 0
  getChildDevices().each { dev ->
    String dni = dev?.getDeviceNetworkId()
    if (dni != null && (dni.startsWith(DABV2_DIAG_ROOM_DNI_PREFIX) || dni == DABV2_DIAG_SUMMARY_DNI)) {
      dabV2SafeDeleteChildDevice(dni)
      removed++
    }
  }
  if (state != null) { state.remove('dabV2Diagnostics') }
  if (removed > 0) {
    log "DAB v2 diagnostics inactive: removed ${removed} diagnostic child device(s)", 2
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

// --- Throttle status + notification coalescing (R4.15-R4.18, design §6.5) ----
// dabV2RecordThrottle records that a throttle (HTTP 429 / rate-limit) occurred.
// It sets a user-visible status (atomicState.throttleStatus, R4.17) and coalesces
// repeated identical throttles within DABV2_ERROR_COALESCE_MS so a throttle storm
// surfaces a SINGLE clear notification rather than flooding the log (R4.15/R4.16).
// Returns true when the throttle is newly surfaced, false when coalesced within
// the window. Time is injectable (`nowMs`) for deterministic testing.
boolean dabV2RecordThrottle(Long nowMs = null) {
  Long t = (nowMs != null ? nowMs : now()) as Long
  // R4.17: the status always reflects that throttling occurred, independent of
  // whether this particular occurrence is surfaced as a fresh notification.
  if (atomicState != null) {
    atomicState.throttleStatus = "Throttling occurred at ${t}; backing off and maintaining control toward the safety floor"
  }
  // R4.15/R4.16: reuse the established per-key coalescing window so repeated
  // throttles within DABV2_ERROR_COALESCE_MS collapse into one surfaced status.
  return dabV2ShouldNotifyError('throttle', t)
}

// dabV2NoteControlRecovered flips the user-visible status to "control recovered
// after throttling" once the request path is healthy again (R4.18, design §6.5).
void dabV2NoteControlRecovered(Long nowMs = null) {
  Long t = (nowMs != null ? nowMs : now()) as Long
  if (atomicState != null) {
    atomicState.throttleStatus = "Control recovered after throttling at ${t}"
  }
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

// Initialize request tracking.
//
// R4.19 (Task 6.8): timeout-gated stuck-counter detect/reset (design §6.4). A
// counter wedged at MAX_CONCURRENT_REQUESTS with no progress (no increment/
// decrement stamping atomicState.requestTrackingTs) for longer than
// REQUEST_TRACKING_STUCK_MS is detected and reset to 0 so the request path cannot
// wedge permanently. The reset is GATED by the timeout: a legitimately busy
// counter that is within the window (or has no recorded progress timestamp yet)
// is preserved and never reset prematurely. The periodic cleanupPendingRequests()
// (runEvery5Minutes) still provides a belt-and-suspenders scheduled reset.
private initRequestTracking() {
  if (atomicState.activeRequests == null) {
    atomicState.activeRequests = 0
    return
  }
  def currentActiveRequests = atomicState.activeRequests ?: 0
  if (currentActiveRequests >= MAX_CONCURRENT_REQUESTS) {
    Long lastProgress = (atomicState.requestTrackingTs != null) ? (atomicState.requestTrackingTs as Long) : null
    if (lastProgress != null && (now() - lastProgress) >= REQUEST_TRACKING_STUCK_MS) {
      log "Active request counter wedged at ${currentActiveRequests}/${MAX_CONCURRENT_REQUESTS} for >=${REQUEST_TRACKING_STUCK_MS}ms with no progress - resetting to 0 (R4.19)", 1
      atomicState.activeRequests = 0
    }
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
  // R4.19: stamp the last-progress timestamp so the stuck-counter detect/reset is
  // measured from the most recent real progress (design §6.4).
  atomicState.requestTrackingTs = now()
}

// Decrement active request counter
def decrementActiveRequests() {
  initRequestTracking()
  def currentCount = atomicState.activeRequests ?: 0
  atomicState.activeRequests = Math.max(0, currentCount - 1)
  // R4.19: a completing request is progress too, so a steadily-draining path is
  // never mistaken for a wedge (design §6.4).
  atomicState.requestTrackingTs = now()
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

// R4.1/R4.2 (Task 6.4, design §6.1): generalized HTTP 429 classification.
// A 429 is a TRANSIENT throttle condition on BOTH the data and auth paths, not
// an unrecoverable error and never the token-clearing re-auth path. Returns true
// iff the response is a well-formed HTTP error with status 429.
def isThrottleResponse(resp) {
  try {
    return resp != null && resp.hasError() && resp.getStatus() == 429
  } catch (ignored) {
    return false
  }
}

// R4.4 (design §6.2): if a 429 carries a Retry-After header, surface it as ms so
// dabv2BackoffIntervalMs can honor it (clamped to the cap). Tolerates a missing
// header or non-numeric value by returning null (-> exponential backoff).
def retryAfterMsFromResponse(resp) {
  try {
    def headers = resp?.respondsTo('getHeaders') ? resp.getHeaders() : null
    if (!headers) { return null }
    def ra = headers['Retry-After'] ?: headers['retry-after']
    if (ra == null) { return null }
    return (Long) (new BigDecimal(ra.toString().trim()) * 1000L).longValue()
  } catch (ignored) {
    return null
  }
}

// R4.1/R4.3 (Task 6.4): data-path 429 handling shared by the async data
// callbacks. Treats the 429 as transient and schedules a bounded retry of the
// affected resource via the existing getDataAsync retry wrapper when the retry
// context (uri/callback) is available. Preserves the still-valid token and never
// routes through the token-clearing autoReauthenticate path. Returns true iff the
// response was a 429 (so the caller should stop normal processing).
def handleDataPathThrottle(resp, data) {
  if (!isThrottleResponse(resp)) { return false }
  int retryCount = (data?.retryCount ?: 0) as int
  Long retryAfterMs = retryAfterMsFromResponse(resp)
  if (data?.uri && data?.callback && retryCount < MAX_API_RETRY_ATTEMPTS) {
    def retryData = [uri: data.uri, callback: data.callback, retryCount: retryCount + 1]
    // Sanitize before scheduling (forum #382): the scheduler JSON round-trip
    // turns a live device into a LazyMap. overwrite:false so parallel 429
    // retries don't clobber each other.
    if (data.containsKey('data')) { retryData.data = sanitizeRetryData(data.data) }
    runInMillis(dabv2BackoffIntervalMs(retryCount, retryAfterMs), 'retryGetDataAsyncWrapper',
                [overwrite: false, data: retryData])
    log "Data-path throttle (429): scheduled bounded retry of ${data.uri} " +
        "(attempt ${retryCount + 1}/${MAX_API_RETRY_ATTEMPTS})", 2
  } else if (data?.uri) {
    log "Data-path throttle (429) for ${data.uri}: retry budget exhausted; deferring to next cycle", 2
  } else {
    log "Data-path throttle (429) for ${data?.deviceType}: no retry context; deferring to next cycle", 2
  }
  return true
}

// === Scheduler-safe retry data (forum #382; AGENTS "store IDs, never device objects") ===
//
// Hubitat serializes runIn/runInMillis `data:` maps to JSON and deserializes
// them when the job fires, so a live ChildDeviceWrapper placed in scheduler data
// comes back as a groovy.json.internal.LazyMap (its JSON dump is the
// `[capabilities:[[attributes:[...]]]]` shape seen in user logs). A downstream
// handler then calls sendEvent(LazyMap, ...) and throws MissingMethodException.
// Every deferral/retry path must therefore strip the device to its network id
// BEFORE scheduling (sanitize) and look it back up AFTER the round-trip
// (rehydrate). All other keys (cacheKey etc.) pass through untouched.

// Replace a live `device` entry with its `deviceId` so the map survives the
// scheduler's JSON round-trip. Non-map / device-less payloads pass through.
private sanitizeRetryData(data) {
  if (!(data instanceof Map) || data.device == null) { return data }
  Map copy = new LinkedHashMap((Map) data)
  def device = copy.remove('device')
  copy.deviceId = device.getDeviceNetworkId()
  return copy
}

// Restore a sanitized `deviceId` entry to the live `device` child wrapper.
// Returns null when the device no longer exists (caller drops the retry) —
// a deleted child is the only way the lookup can fail.
private rehydrateRetryData(data) {
  if (!(data instanceof Map) || data.deviceId == null || data.device != null) { return data }
  def device = getChildDevice(data.deviceId.toString())
  if (device == null) {
    logError "Retry dropped: device ${data.deviceId} no longer exists"
    return null
  }
  Map copy = new LinkedHashMap((Map) data)
  copy.remove('deviceId')
  copy.device = device
  return copy
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
      // Sanitize EVERY deferral (not just /room): a live device object in
      // scheduler data comes back as a LazyMap (forum #382). overwrite:false so
      // concurrent deferrals from a poll burst don't clobber each other's retry
      // job — the old default silently dropped all but the last deferred request,
      // which is how a newly added Puck 2's discovery GET could vanish.
      def retryData = [uri: uri, callback: callback, retryCount: retryCount + 1,
                       data: sanitizeRetryData(data)]
      runInMillis(dabv2BackoffIntervalMs(retryCount, null), 'retryGetDataAsyncWrapper',
                  [overwrite: false, data: retryData])
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
    // Normal retry for non-room requests. Rehydrate a sanitized deviceId back
    // into the live child wrapper (forum #382) so downstream handlers get a
    // real device, never the scheduler's JSON round-trip of one.
    def retryPayload = rehydrateRetryData(data.data)
    if (data.data != null && retryPayload == null) { return }
    getDataAsync(data.uri, data.callback, retryPayload, data.retryCount)
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
      // Same scheduler-safety rules as getDataAsync (forum #382): sanitize the
      // device out of the payload and never clobber a sibling retry job.
      def retryData = [uri: uri, callback: callback, body: body,
                       data: sanitizeRetryData(data), retryCount: retryCount + 1]
      runInMillis(dabv2BackoffIntervalMs(retryCount, null), 'retryPatchDataAsyncWrapper',
                  [overwrite: false, data: retryData])
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
  // Rehydrate a sanitized deviceId back into the live child wrapper (forum #382).
  def retryPayload = rehydrateRetryData(data.data)
  if (data.data != null && retryPayload == null) { return }
  patchDataAsync(data.uri, data.callback, data.body, retryPayload, data.retryCount)
}

def noOpHandler(resp, data) {
  // Issue #7: even a fire-and-forget PATCH holds a throttle slot — release it
  // when the callback lands, or structure-mode patches slowly wedge the counter.
  decrementActiveRequests()
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
      runInMillis(dabv2BackoffIntervalMs(retryCount, null), 'retryAuthenticateWrapper', [data: [retryCount: retryCount + 1]])
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
      // R4.2/R4.3 (Task 6.4, design §6.1): an auth-path 429 is a TRANSIENT
      // throttle, not a terminal credential failure. Retry via the existing
      // auth-retry wrapper with bounded backoff and PRESERVE the still-valid
      // token (never clear it, never take the autoReauthenticate path).
      if (status == 429) {
        int retryCount = (data?.retryCount ?: 0) as int
        Long retryAfterMs = retryAfterMsFromResponse(resp)
        if (retryCount < MAX_API_RETRY_ATTEMPTS) {
          log "Auth-path throttle (429): scheduling bounded auth retry " +
              "(attempt ${retryCount + 1}/${MAX_API_RETRY_ATTEMPTS}); token preserved", 2
          runInMillis(dabv2BackoffIntervalMs(retryCount, retryAfterMs), 'retryAuthenticateWrapper',
                      [data: [retryCount: retryCount + 1]])
        } else {
          state.authError = "Authentication throttled (429) after ${MAX_API_RETRY_ATTEMPTS} retries"
          logError state.authError
        }
        return
      }
      def errorMsg = "Authentication failed with HTTP ${status}"
      if (status == 401) {
        errorMsg += ": Invalid credentials. Please verify your Client ID and Client Secret."
      } else if (status == 403) {
        errorMsg += ": Access forbidden. Please verify your OAuth credentials have proper permissions."
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
    case 'addZone':
      addZoneFromUi()
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
    case 'resetDabLearning':
      // User-initiated DAB reset (R7.3). Scope is the UI-selected zone id (or the
      // all-zones sentinel); confirmation is read from the UI flag inside
      // resetDabLearning so nothing clears without an explicit user action (R7.20).
      resetDabLearning((settings?.dabResetScope ?: DABV2_RESET_SCOPE_ALL) as String)
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
  // This fans out to six endpoints (vents, pucks, puck2s, rooms?include=pucks,
  // /api/pucks, /api/puck2s) and three different handlers each create pucks, with
  // inconsistent puck naming ("Puck-${id}" here vs "${room} Puck" in the rooms
  // handler). Consolidating the redundant fan-out and unifying puck naming touches
  // the device-discovery/creation flow and is owned by the later app-wiring work
  // (tasks 9.x); it is recorded as deferred future work (task 10.4) rather than
  // repaired now because it is not safety-bearing (no airflow impact) and a
  // piecemeal change here would risk regressing device identity. makeRealDevice is
  // idempotent on network id, so the duplicate fan-out is currently harmless
  // (re-discovers the same devices).
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
  // Puck 2 devices are a SEPARATE JSON:API resource type (`puck2s`) and are
  // never included in /pucks responses (official API docs, Pucks endpoint note;
  // forum #387 room payload). Query them alongside v1 pucks - both revisions
  // share the same 'Flair pucks' child driver.
  def puck2sUri = "${BASE_URL}/api/structures/${structureId}/puck2s"
  log "Calling puck2s endpoint: ${puck2sUri}", 2
  getDataAsync(puck2sUri, 'handleDeviceList', [deviceType: 'puck2s'])
  // Also try to get pucks from rooms since they might be associated there
  def roomsUri = "${BASE_URL}/api/structures/${structureId}/rooms?include=pucks"
  log "Calling rooms endpoint for pucks: ${roomsUri}", 2
  getDataAsync(roomsUri, 'handleRoomsWithPucks')
  // Try getting pucks directly without structure (both revisions)
  def allPucksUri = "${BASE_URL}/api/pucks"
  log "Calling all pucks endpoint: ${allPucksUri}", 2
  getDataAsync(allPucksUri, 'handleAllPucks')
  def allPuck2sUri = "${BASE_URL}/api/puck2s"
  log "Calling all puck2s endpoint: ${allPuck2sUri}", 2
  getDataAsync(allPuck2sUri, 'handleAllPucks', [deviceType: 'puck2s'])
}


def handleAllPucks(resp, data) {
  decrementActiveRequests()  // Always decrement when response comes back
  // Serves BOTH /api/pucks (v1, default) and /api/puck2s (Puck 2): the API
  // resource type rides in on data.deviceType so the created child records the
  // endpoint family its readings must be polled from.
  String puckType = (data?.deviceType == 'puck2s') ? 'puck2s' : 'pucks'
  try {
    log "handleAllPucks called for ${puckType}", 2
    if (!isValidResponse(resp)) {
      log "handleAllPucks: Invalid response status: ${resp?.getStatus()}", 2
      return
    }
    def respJson = resp?.getJson()
    log "All ${puckType} endpoint response: has data=${respJson?.data != null}, count=${respJson?.data?.size() ?: 0}", 2

    if (respJson?.data) {
      def puckCount = 0
      respJson.data.each { puckData ->
        try {
          if (puckData?.id) {
            puckCount++
            def puckId = puckData?.id?.toString()?.trim()
            def puckName = puckData?.attributes?.name?.toString()?.trim() ?: "Puck-${puckId}"

            log "Creating puck from all ${puckType} endpoint: ${puckName} (${puckId})", 2

            def device = [
              id   : puckId,
              type : puckType,
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
        log "Discovered ${puckCount} ${puckType} from all ${puckType} endpoint", 3
      }
    }
  } catch (Exception e) {
    log "Error in handleAllPucks: ${e.message}", 1
  }
}

def handleRoomsWithPucks(resp, data) {
  decrementActiveRequests()  // Always decrement when response comes back
  // Declared OUTSIDE the first try block: the room-relationships block below
  // reads it too. It previously referenced a try-scoped local, which threw
  // MissingPropertyException on every call and silently disabled the
  // rooms->relationships->pucks discovery path (the catch only logged at debug
  // level), costing one of the four puck discovery sources.
  def respJson = null
  try {
    log "handleRoomsWithPucks called", 2
    if (!isValidResponse(resp)) { 
      log "handleRoomsWithPucks: Invalid response status: ${resp?.getStatus()}", 2
      return 
    }
    respJson = resp.getJson()
    
    // Log the structure to debug
    log "handleRoomsWithPucks response: has included=${respJson?.included != null}, included count=${respJson?.included?.size() ?: 0}, has data=${respJson?.data != null}, data count=${respJson?.data?.size() ?: 0}", 2
    
    // Check if we have included pucks data (either revision: v1 `pucks` or
    // Puck 2 `puck2s` - separate JSON:API resource types per the official docs)
    if (respJson?.included) {
      def puckCount = 0
      respJson.included.each { it ->
        try {
          if ((it?.type == 'pucks' || it?.type == 'puck2s') && it?.id) {
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
            
            log "About to create puck device with id: ${puckId}, name: ${puckName}, type: ${it.type}", 1
            
            def device = [
              id   : puckId,
              type : it.type?.toString(),
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
  
  
  // Also check if pucks are in the room data relationships. Rooms carry the
  // two puck revisions in SEPARATE relationship lists (`pucks` for v1,
  // `puck2s` for Puck 2 - see forum #387's payload), so both are walked.
  try {
    if (respJson?.data) {
      int roomPuckCount = 0
      for (Object roomObj : respJson.data) {
        def room = roomObj
        roomPuckCount += onboardRoomPuckRefs(room, room?.relationships?.pucks?.data, 'pucks')
        roomPuckCount += onboardRoomPuckRefs(room, room?.relationships?.puck2s?.data, 'puck2s')
      }
      if (roomPuckCount > 0) {
        log "Found ${roomPuckCount} puck references in rooms", 3
      }
    }
  } catch (Exception e) {
    log "Error checking room puck relationships: ${e.message}", 1
  }
}

// Onboard the puck references of ONE room relationship list (`pucks` v1 or
// `puck2s` Puck 2). Returns the number of references seen. Per-item isolation:
// one bad reference never aborts the remaining ones. Extracted as a top-level
// helper (plain for loop) so handleRoomsWithPucks stays closure-light per the
// on-hub compile guidance.
private int onboardRoomPuckRefs(room, List refs, String puckType) {
  if (!refs) { return 0 }
  int count = 0
  for (Object refObj : refs) {
    def puck = refObj
    try {
      count++
      def puckId = puck?.id?.toString()?.trim()
      if (!puckId || puckId.isEmpty()) {
        log "Skipping ${puckType} reference with invalid ID in room ${room?.attributes?.name}", 2
        continue
      }

      // Create a minimal puck device from the reference
      def puckName = "Puck-${puckId}"
      if (room?.attributes?.name) {
        puckName = "${room.attributes.name} Puck"
      }

      log "Creating puck device from room reference: ${puckName} (${puckId}, ${puckType})", 2

      def device = [
        id   : puckId,
        type : puckType,
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
  return count
}


def handleDeviceList(resp, data) {
  decrementActiveRequests()  // Always decrement when response comes back
  log "handleDeviceList called for ${data?.deviceType}", 2
  // R4.1 (Task 6.4): a data-path 429 is a transient throttle. Schedule a bounded
  // retry of the affected resource and stop normal processing — do NOT fall into
  // the generic isValidResponse error branch (which would drop the response with
  // no retry).
  if (handleDataPathThrottle(resp, data)) { return }
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
    // Issue #7 (GitHub): per-item isolation. One device whose payload or trait
    // processing throws must not abort onboarding of the remaining devices in
    // the payload (the puck handlers already isolate per item; this vent/puck
    // path did not, so a single failing vent pinned discovery at exactly one
    // onboarded vent no matter how often Discover was clicked).
    try {
      // `puck2s` is the Puck 2 resource type - a SEPARATE JSON:API type from v1
      // `pucks` (official API docs). Both onboard onto the same puck driver.
      if (it?.type == 'vents' || it?.type == 'pucks' || it?.type == 'puck2s') {
        if (it.type == 'vents') {
          ventCount++
        } else {
          puckCount++
        }
        // R1 blank-name fallback (design §R1.1, cross-ref R7.5/R7.32): mirror the
        // ID-derived label fallback already used by handleAllPucks/handleRoomsWithPucks
        // so a Puck 2 (or vent) shipped with a blank/default name is not dropped by
        // makeRealDevice's null/blank-label guard.
        def deviceId = it?.id?.toString()?.trim()
        def label = it?.attributes?.name?.toString()?.trim()
        if (!label) {
          label = (it.type == 'vents' ? "Vent-${deviceId}" : "Puck-${deviceId}")
        }
        def device = [
          id   : it?.id,
          type : it?.type,
          label: label
        ]
        def dev = makeRealDevice(device)
        if (dev && it.type == 'vents') {
          processVentTraits(dev, [data: it])
        }
      } else {
        // R1.22/R1.23: an unrecognized device `type` (or unexpected attribute
        // shape) is logged at a diagnostic level and skipped per-device so
        // discovery continues onboarding the recognized devices in the payload.
        log "Skipping unrecognized device type '${it?.type}' (id=${it?.id})", 2
      }
    } catch (Exception e) {
      logError "Error onboarding discovered device (id=${it?.id}, type=${it?.type}): ${e?.message}"
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
    // Both puck revisions ('pucks' v1 and 'puck2s' Puck 2) share the same
    // 'Flair pucks' child driver; only the API resource type differs.
    def deviceType = device.type == 'vents' ? 'Flair vents' : 'Flair pucks'
    try {
      newDevice = addChildDevice('bot.flair', deviceType, deviceId, [name: deviceLabel, label: deviceLabel])
    } catch (Exception e) {
      logError "Failed to add child device: ${e.message}"
      return null
    }
  }
  // Persist the Flair API resource type on the child so polling routes to the
  // right endpoint family (/api/pucks/... vs /api/puck2s/...). Idempotent, and
  // it self-heals a device first seen through a source that reported the other
  // type. Devices created before this change carry no value and default to the
  // v1 'pucks' paths, so existing installs are unchanged.
  try {
    newDevice.updateDataValue('apiType', device.type?.toString())
  } catch (Exception e) {
    log "Could not record apiType for ${deviceId}: ${e.message}", 2
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
    // Route to the device's own API resource family: v1 pucks poll
    // /api/pucks/..., Puck 2 polls /api/puck2s/... (separate JSON:API types).
    // Pucks created before apiType was recorded default to the v1 paths.
    String puckType = puckApiType(device)
    // Get puck data and current reading with caching
    getDeviceDataWithCache(device, deviceId, puckType, 'handlePuckGet')
    getDeviceReadingWithCache(device, deviceId, puckType, 'handlePuckReadingGet')
    // Check cache before making room API call
    getRoomDataWithCache(device, deviceId, isPuck)
  } else {
    // Get vent reading with caching
    getDeviceReadingWithCache(device, deviceId, 'vents', 'handleDeviceGet')
    // Check cache before making room API call
    getRoomDataWithCache(device, deviceId, isPuck)
  }
}

// The Flair API resource family a puck child belongs to: 'puck2s' when the
// device was discovered through a Puck 2 endpoint/reference, else the v1
// 'pucks' (including every device created before apiType was recorded, so
// existing installs keep their exact pre-change paths).
private String puckApiType(device) {
  return device?.getDataValue('apiType') == 'puck2s' ? 'puck2s' : 'pucks'
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
  
  // No valid cache and no pending request, make the API call. A puck's room
  // link lives under its own resource family (/api/pucks/{id}/room vs
  // /api/puck2s/{id}/room per the Puck2 relationships in the official docs).
  def endpoint = isPuck ? puckApiType(device) : 'vents'
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
  
  // No valid cache and no pending request, make the API call. Every resource
  // family exposes the same shape: /api/<type>/<id>/current-reading (vents,
  // pucks, and puck2s alike per the official docs).
  def uri = "${BASE_URL}/api/${deviceType}/${deviceId}/current-reading"
  getDataAsync(uri, callback + 'WithCache', [device: device, cacheKey: cacheKey])
}

// NOTE (issue #7): never registered as an async callback — retained for direct
// (synthetic) invocation only, so it must NOT touch the throttle counter. The
// registered callback (handleRoomGetWithCache) owns the slot release.
def handleRoomGet(resp, data) {
  if (!isValidResponse(resp) || !data?.device) { return }
  processRoomTraits(data.device, resp.getJson())
}

// Modified handleRoomGet to include caching
def handleRoomGetWithCache(resp, data) {
  // Issue #7: this is the REGISTERED async callback for room fetches — release
  // the throttle slot exactly once, first thing, on every outcome. It never
  // decremented before, so every room poll leaked a slot until the counter
  // wedged at 8/8 and all API traffic (including discovery) starved.
  decrementActiveRequests()
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

// NOTE (issue #7): never registered as an async callback — invoked synthetically
// (reading cache hits) with a fake resp map, so it must NOT touch the throttle
// counter. The registered callback (handleDeviceGetWithCache) owns the slot release.
def handleDeviceGet(resp, data) {
  if (!isValidResponse(resp) || !data?.device) { return }
  processVentTraits(data.device, resp.getJson())
}

// Modified handleDeviceGet to include caching
def handleDeviceGetWithCache(resp, data) {
  // Issue #7: registered async callback — owns the slot release (exactly once,
  // first thing, on every outcome). It never decremented before (leak).
  decrementActiveRequests()
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

// NOTE (issue #7): never registered as an async callback — invoked synthetically
// (device-data cache hits and handlePuckGetWithCache delegation) with a fake
// resp map, so it must NOT touch the throttle counter. The registered callback
// (handlePuckGetWithCache) owns the slot release.
def handlePuckGet(resp, data) {
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
    // A USB-powered Puck 2 reports voltage 0.0 (power-source 'USB' per the
    // official docs) - deriving battery from it would surface a misleading 0%.
    // Battery-powered devices of either revision keep the existing derivation.
    boolean usbPowered = puckData?.attributes?.'power-source' == 'USB'
    if (puckData?.attributes?.voltage != null && !usbPowered) {
      try {
        def voltage = puckData.attributes.voltage as BigDecimal
        // Map the puck-resource voltage onto the canonical voltage attribute
        // (R1.9), then derive battery from it (R1.10).
        sendEvent(data.device, [name: 'voltage', value: voltage, unit: 'V'])
        def battery = deriveBatteryPercent(voltage)
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
    // R1.6/R1.7/R1.8: map the shared puck diagnostic attributes (RSSI ->
    // canonical rssi, firmware-version-s, motion/occupancy) with per-attribute
    // null guards so partial Puck 2 payloads still process (R1.11/R1.12).
    emitPuckDiagnostics(data.device, puckData.attributes)
  }
}

// R1.6/R1.7/R1.8: shared puck diagnostic-attribute mapping used by both
// handlePuckGet (pucks resource) and handlePuckReadingGet (sensor reading) so
// every puck revision (V1 + Puck 2) surfaces the same canonical attributes with
// per-attribute null guards. Missing optional fields are tolerated — each guard
// simply skips, never aborting the rest of the mapping (R1.11/R1.12). The puck
// revision is classified (never gated) for optional diagnostics only (R1.3).
private emitPuckDiagnostics(device, Map attrs) {
  if (!device || attrs == null) { return }
  // RSSI: map onto the canonical rssi attribute (R1.7). v1 reports
  // current-rssi; Puck 2 readings report sub-ghz-rssi (or wifi-rssi when on
  // WiFi) per the official puck2-sensor-readings docs.
  if (attrs['current-rssi'] != null) {
    sendEvent(device, [name: 'rssi', value: attrs['current-rssi'], unit: 'dBm'])
  } else if (attrs['sub-ghz-rssi'] != null) {
    sendEvent(device, [name: 'rssi', value: attrs['sub-ghz-rssi'], unit: 'dBm'])
  } else if (attrs['wifi-rssi'] != null) {
    sendEvent(device, [name: 'rssi', value: attrs['wifi-rssi'], unit: 'dBm'])
  }
  // Firmware: surface firmware-version-s where present (R1.8). Puck 2 reports
  // a NUMERIC firmware-version instead; surface it on the same canonical
  // attribute so both revisions read alike in Hubitat.
  if (attrs['firmware-version-s'] != null) {
    sendEvent(device, [name: 'firmware-version-s', value: attrs['firmware-version-s']])
  } else if (attrs['firmware-version'] != null) {
    sendEvent(device, [name: 'firmware-version-s', value: attrs['firmware-version'].toString()])
  }
  // Motion/occupancy where exposed (R1.6): map the boolean occupancy flag onto
  // the MotionSensor motion attribute.
  if (attrs['occupied'] != null) {
    sendEvent(device, [name: 'motion', value: (attrs['occupied'] ? 'active' : 'inactive')])
  }
  // Optional Puck-2 hardware-revision diagnostics (R1.3): classify, never gate.
  // Unrecognized revisions still onboard; this only annotates the device.
  try {
    String rev = dabv2PuckRevision(attrs)
    if (rev && rev != 'UNKNOWN') {
      sendEvent(device, [name: 'puckHwVersion', value: rev])
    }
  } catch (Exception ignored) {
    // classification is best-effort diagnostics only; never block mapping
  }
}

// Modified handlePuckGet to include caching
def handlePuckGetWithCache(resp, data) {
  // Issue #7: registered async callback — owns the slot release (exactly once,
  // first thing, on every outcome). The handlePuckGet delegate below no longer
  // decrements, so the slot is not double-released.
  decrementActiveRequests()
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


// NOTE (issue #7): never registered as an async callback — invoked synthetically
// (reading cache hits and handlePuckReadingGetWithCache delegation) with a fake
// resp map, so it must NOT touch the throttle counter. The registered callback
// (handlePuckReadingGetWithCache) owns the slot release.
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
        def voltage = reading.attributes['system-voltage']
        // Map system-voltage to voltage attribute for Rule Machine compatibility
        sendEvent(data.device, [name: 'voltage', value: voltage, unit: 'V'])
        def battery = deriveBatteryPercent(voltage as BigDecimal)
        sendEvent(data.device, [name: 'battery', value: battery, unit: '%'])
      } catch (Exception e) {
        log "Error calculating battery from reading: ${e.message}", 2
      }
    }
    // R1.6/R1.7/R1.8: map RSSI -> canonical rssi, firmware-version-s, and
    // motion/occupancy from the sensor reading with per-attribute null guards
    // (R1.11/R1.12), identically to the puck-resource path (parity, R1.17/R1.25).
    emitPuckDiagnostics(data.device, reading.attributes)
  }
}

// Modified handlePuckReadingGet to include caching
def handlePuckReadingGetWithCache(resp, data) {
  // Issue #7: registered async callback — owns the slot release (exactly once,
  // first thing, on every outcome). The handlePuckReadingGet delegate below no
  // longer decrements, so the slot is not double-released.
  decrementActiveRequests()
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

   // Voltage/battery population (R7.34/R7.35/R7.37). Populate the canonical
   // `voltage` attribute from whichever path the reading provides: the
   // resource-level `voltage` field OR `system-voltage` on the
   // `vent-sensor-readings` sub-resource (do not assume only `system-voltage`).
   // Derive `battery` via the existing (v-2.0)/1.6*100 map clamped 0..100.
   // Guard before `sendEvent` so a null/non-numeric reading never overwrites
   // the stored last-good value (state.lastGoodVoltage[ventId]).
   def ventAttrs = details?.data?.attributes ?: [:]
   def rawVoltage = ventAttrs['voltage'] != null ? ventAttrs['voltage'] : ventAttrs['system-voltage']
   def voltage = asFiniteNumber(rawVoltage)
   if (voltage != null) {
     sendEvent(device, [name: 'voltage', value: voltage, unit: 'V'])
     def battery = deriveBatteryPercent(voltage)
     sendEvent(device, [name: 'battery', value: battery, unit: '%'])
     def ventId = device?.getId()
     if (ventId != null) {
       if (state.lastGoodVoltage == null) { state.lastGoodVoltage = [:] }
       state.lastGoodVoltage[ventId] = voltage
     }
   }
}

// Returns the value when it is a finite numeric value, otherwise null. Used to
// guard voltage/battery population so a missing or non-numeric reading never
// overwrites a stored last-good value (R7.37). Non-Number types (e.g. strings
// such as 'n/a') and NaN/Infinity are treated as missing.
private asFiniteNumber(value) {
  if (value == null) { return null }
  if (value instanceof Number) {
    double d = value.doubleValue()
    if (Double.isNaN(d) || Double.isInfinite(d)) { return null }
    return value
  }
  return null
}

// Derives a battery percentage from a battery voltage using the established
// (v - 2.0) / 1.6 * 100 map (2.0 V = 0%, 3.6 V = 100%), rounded to the nearest
// integer and clamped to 0..100.
//
// Issue #7 (GitHub): this intentionally uses double math + Math.round instead of
// calling `.round()` on the BigDecimal arithmetic result. The hub's Groovy
// runtime lacks the round(BigDecimal) extension that the off-device test
// harness has, so `battery.round()` resolved to the JDK's
// BigDecimal.round(MathContext) with an implicit null argument and threw the
// raw "java.lang.NullPointerException: null" (reported at line 5150, method
// handleDeviceList) that aborted vent discovery. Math.round dispatches
// identically on-hub and off-device.
private int deriveBatteryPercent(Number voltage) {
  long rounded = Math.round(((voltage.doubleValue() - 2.0d) / 1.6d) * 100.0d)
  return (int) Math.max(0L, Math.min(100L, rounded))
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
      runInMillis(dabv2BackoffIntervalMs(retryCount, null), 'retryGetStructureDataAsyncWrapper', [data: [retryCount: retryCount + 1]])
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
    if (!response?.data) {
      logError 'No structure data available'
      return
    }

    // Deterministic Home-Id resolution (R2.15-R2.19): a configured id wins, a
    // single structure is adopted, but >1 with none configured refuses to
    // auto-pick the blind first() and requires an explicit selection. Never
    // clear/overwrite an already-configured structureId.
    def selection = dabv2SelectStructureId(response.data, settings?.structureId)
    if (selection?.requireSelection) {
      log 'Multiple structures found; select a Home Id in the app preferences before automation can run', 2
      return
    }
    if (selection?.id) {
      app.updateSetting('structureId', selection.id)
      def chosen = response.data.find { it?.id == selection.id }
      log "Structure loaded: id=${selection.id}, name=${chosen?.attributes?.name}", 2
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
      runInMillis(dabv2BackoffIntervalMs(retryCount, null), 'retryGetStructureDataWrapper', [data: [retryCount: retryCount + 1]])
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
  boolean slotReleased = false
  
  try {
    httpGet(httpParams) { resp ->
      decrementActiveRequests()
      slotReleased = true
      
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
      // Deterministic Home-Id resolution (R2.15-R2.19) on the blocking path too:
      // route through the pure helper instead of the blind response.data.first().
      // Never clear/overwrite an already-configured structureId.
      def selection = dabv2SelectStructureId(response.data, settings?.structureId)
      if (selection?.requireSelection) {
        log 'Multiple structures found; select a Home Id in the app preferences before automation can run', 2
        return
      }
      if (!selection?.id) {
        logError 'getStructureData: no structure data'
        return
      }
      def myStruct = response.data.find { it?.id == selection.id }
      // Log only essential fields at level 3
      log "Structure loaded: id=${selection.id}, name=${myStruct?.attributes?.name}, mode=${myStruct?.attributes?.mode}", 3
      app.updateSetting('structureId', selection.id)
    }
  } catch (Exception e) {
    // Issue #7: the closure may have already released the slot before throwing
    // (e.g. a non-success HTTP status) — never release the same slot twice.
    if (!slotReleased) { decrementActiveRequests() }
    
    if (retryCount < MAX_API_RETRY_ATTEMPTS) {
      log "Structure data request failed (attempt ${retryCount + 1}/${MAX_API_RETRY_ATTEMPTS}): ${e.message}", 2
      // Schedule retry asynchronously
      runInMillis(dabv2BackoffIntervalMs(retryCount, null), 'retryGetStructureDataWrapper', [data: [retryCount: retryCount + 1]])
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
  
  // Process the API response. An empty-body PATCH response (forum #396)
  // degrades to null and we fall through to the local target update below;
  // the next poll reconciles from the API.
  def respJson = safeGetJson(resp, 'handleVentPatch')
  if (respJson?.data) {
    traitExtract(device, [data: respJson.data], 'percent-open', 'percent-open', '%')
    traitExtract(device, [data: respJson.data], 'percent-open', 'level', '%')
  }
  
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
  // Same empty-body tolerance as handleVentPatch (forum #396).
  def respJson = safeGetJson(resp, 'handleRoomPatch')
  if (respJson != null) {
    traitExtract(data.device, respJson, 'active', 'room-active')
  }
}

// ------------------------------
// R3 — per-room temperature setpoints / offsets (config boundary + resolution)
// ------------------------------

// Rule-Machine / driver entry point for a per-room target or offset, mirroring
// the `patchRoom` rule-control pattern. The inbound value arrives in the boundary
// display unit and is converted to internal Celsius (R19.3), then clamped at the
// config boundary with the SAME bounds the pure resolver enforces so UI- and
// Rule-Machine-supplied values get identical treatment (R3.4/R3.5/R3.16).
// mode 'absolute' persists absTargetC; mode 'offset' persists offsetC. (R3.14/R3.15)
def patchRoomSetpoint(device, value, mode) {
  def roomId = device?.currentValue('room-id')
  if (!roomId || value == null || mode == null) { return }
  BigDecimal valueC = dabV2BoundaryToCelsius(value)
  if (valueC == null) { return }
  if (!(state.perRoomSetpoints instanceof Map)) { state.perRoomSetpoints = [:] }
  Map cfg = (state.perRoomSetpoints[roomId] instanceof Map) ?
    (Map) state.perRoomSetpoints[roomId] : [absTargetC: null, offsetC: PER_ROOM_OFFSET_DEFAULT_C]
  String m = mode.toString().trim().toLowerCase()
  if (m == 'offset') {
    cfg.offsetC = clampDecimal(valueC, PER_ROOM_OFFSET_MIN_C, PER_ROOM_OFFSET_MAX_C, PER_ROOM_OFFSET_DEFAULT_C)
  } else {
    cfg.absTargetC = clampDecimal(valueC, PER_ROOM_ABS_MIN_C, PER_ROOM_ABS_MAX_C, PER_ROOM_ABS_DEFAULT_C)
  }
  state.perRoomSetpoints[roomId] = cfg
  log "Set per-room setpoint (${m}) for '${device?.currentValue('room-name')}' -> ${cfg}", 3
}

// Resolve a room's effective target in Celsius from its persisted config, deferring
// the value math to the pure `dabv2ResolveRoomTargetC` (absolute wins; abs clamps
// 10–32; offset clamps -5..+5). Surfaces a configuration warning when BOTH an
// absolute target and a non-zero offset are present (the absolute target is
// authoritative and the offset is ignored). (R3.1/R3.2/R3.4/R3.5)
Map resolveRoomSetpoint(sharedSetpointC, rawAbsTargetC, rawOffsetC) {
  BigDecimal shared = sharedSetpointC == null ? null : (sharedSetpointC as BigDecimal)
  BigDecimal absTarget = rawAbsTargetC == null ? null : (rawAbsTargetC as BigDecimal)
  BigDecimal offset = rawOffsetC == null ? null : (rawOffsetC as BigDecimal)
  BigDecimal targetC = dabv2ResolveRoomTargetC(shared, absTarget, offset,
    PER_ROOM_ABS_MIN_C, PER_ROOM_ABS_MAX_C, PER_ROOM_OFFSET_MIN_C, PER_ROOM_OFFSET_MAX_C)
  boolean conflict = absTarget != null && offset != null && offset != 0.0G
  String warning = conflict ?
    "Per-room absolute target ${absTarget}C is authoritative; the configured offset ${offset}C is ignored." :
    null
  return [targetC: targetC, conflict: conflict, warning: warning]
}

// Build the room-id -> resolved-target-C map the allocator/legacy sizing consume,
// from persisted per-room config. A room with a configured target but NO usable
// temperature is OMITTED (deferred — never command on missing data, R3.12); a room
// with no per-room config, or whose config resolves to the shared setpoint
// (neutral), is also omitted so default behavior is unchanged (R3.3).
Map dabV2BuildPerRoomTargetC(roomData, sharedSetpointC) {
  Map out = [:]
  if (!(roomData instanceof List)) { return out }
  Map perRoom = (state.perRoomSetpoints instanceof Map) ? (Map) state.perRoomSetpoints : [:]
  BigDecimal shared = sharedSetpointC == null ? null : (sharedSetpointC as BigDecimal)
  roomData.each { rd ->
    def roomId = rd?.roomId
    if (roomId == null) { return }
    Map cfg = (perRoom[roomId] instanceof Map) ? (Map) perRoom[roomId] : null
    if (cfg == null) { return }                       // no per-room config -> neutral
    if (!isDabV2UsableNumber(rd.tempC)) { return }    // R3.12 — defer on missing temp
    Map res = resolveRoomSetpoint(shared, cfg.absTargetC, cfg.offsetC)
    BigDecimal targetC = res.targetC as BigDecimal
    if (shared != null && targetC == shared) { return }  // resolves to baseline -> neutral
    out[roomId] = targetC
  }
  return out
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

// R6.2 fan-mode event handler. A fan-mode flip (auto <-> on) while the system is
// idle starts/stops fan-only circulation, which the operating-state subscription
// cannot observe. Gated on dabEnabled + circulationEnabled (a disabled feature
// changes nothing downstream, so the evaluate would be a pointless API cost) and
// routed through the `shouldApplyCirculationChange` debounce so short fan bursts
// never thrash the vents (R6.7/R6.8): a suppressed flip is simply picked up by
// the next cadence tick if it persists. The applied-change timestamp is stamped
// ONLY when an evaluate actually runs, mirroring the gate's documented contract.
def thermostat1FanModeHandler(evt) {
  log "Thermostat fan mode changed to: ${evt.value}", 3
  if (!settings?.dabEnabled || !getDabV2CirculationEnabled()) { return }
  Long nowMs = now()
  Long lastMs = atomicState?.dabV2LastCirculationChangeMs as Long
  Long debounceMs = (getDabV2CirculationDebounceSec() as long) * 1000L
  if (!shouldApplyCirculationChange(nowMs: nowMs, lastCirculationMs: lastMs, debounceMs: debounceMs)) {
    log 'Circulation change suppressed by debounce (R6.7)', 3
    return
  }
  atomicState.dabV2LastCirculationChangeMs = nowMs
  selectAndRunDabV2Evaluate()
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
        // R1.21 defer: skip rebalancing evaluation when temperature is missing.
        if (roomTemp == null) { continue }
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

  // R6.14 circulation no-learning gate: a fan-only / circulation cycle is a
  // NON-conditioning action (treated as DABV2_ACTION_IDLE), so it records NO
  // heating/cooling efficiency sample — learning from a cycle that was not
  // actively conditioning would corrupt the per-room rates. Detection mirrors the
  // pure helper (`dabv2DetectCirculation`), with an explicit `data.circulation`
  // flag as a belt-and-suspenders gate set by the evaluate seam.
  if (coerceBoolean(data?.circulation, false) ||
      dabv2DetectCirculation(data?.hvacMode as String, data?.fanMode as String)) {
    log 'Skipping room state finalization - circulation (fan-only) cycle records no efficiency sample (R6.14)', 3
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
        // R1.21 defer: do not learn a rate from a fabricated reading when the
        // room has no resolvable temperature this cycle.
        if (currentTemp == null) {
          log "Deferring rate calc for '${roomName}': no resolvable temperature", 2
          continue
        }
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
        // R1.21 defer: skip recording a starting temperature when none is
        // resolvable rather than persisting a fabricated 0°C baseline.
        if (currentTemp == null) {
          log "Deferring starting temperature for '${vent.currentValue('room-name')}': none resolvable", 2
          return
        }
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
  // Operate on a copy so the floor choke point never mutates the caller's
  // pre-floor (comfort/overshoot-close) plan in place. This mirrors the pure
  // DAB v2 `sfApply` contract (returns a fresh plan, leaves its input intact),
  // so a per-room overshoot-closed plan stays observable alongside the floored
  // plan (R3.10/R3.11). Callers already consume the return value.
  if (calculatedPercentOpen != null) {
    calculatedPercentOpen = new LinkedHashMap(calculatedPercentOpen)
  }
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
    return reconcileGridToFloor(calculatedPercentOpen, additionalStandardVents)
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
  return reconcileGridToFloor(calculatedPercentOpen, additionalStandardVents)
}

// R5.10 / R5.12 — legacy grid reconciliation (the floor choke point runs AFTER
// rounding). The dispatcher snaps each commanded position onto the configured
// granularity grid (`roundToNearestMultiple`) AFTER this method has padded the
// continuous plan up to the combined-airflow floor. A round-DOWN can drop the
// combined airflow back below the floor, so re-check the plan ON THE GRID here
// and, only when needed, raise raisable vents one grid step at a time until the
// rounded plan still meets MIN_COMBINED_VENT_FLOW. This mirrors the DAB v2 path,
// where the single `sfApply` choke point runs after `dabV2GroupNormalize`.
//
// Precedence (safety floor > inactive-room close > granularity grid): the
// most-open vent is raised first, so a closed/inactive room is reopened only as a
// last resort. When the plan already rounds to a floor-safe combined value (e.g.
// the default 5% grid) this is a silent no-op that leaves the continuous plan
// byte-for-byte unchanged (no extra log output, no value changes).
private Map reconcileGridToFloor(Map calculatedPercentOpen, additionalStandardVents) {
  if (!calculatedPercentOpen) { return calculatedPercentOpen }
  int granularity = settings.ventGranularity ? settings.ventGranularity.toInteger() : 5
  if (granularity <= 1) { return calculatedPercentOpen }
  int standardCount = additionalStandardVents > 0 ? (additionalStandardVents as int) : 0
  int deviceCount = standardCount + calculatedPercentOpen.size()
  if (deviceCount <= 0) { return calculatedPercentOpen }
  BigDecimal standardContribution = (standardCount as BigDecimal) * STANDARD_VENT_DEFAULT_OPEN

  // Snapshot the on-grid plan the dispatcher would actually command.
  Map gridOpen = [:]
  calculatedPercentOpen.each { ventId, pct ->
    gridOpen[ventId] = roundToNearestMultiple((pct ?: 0) as BigDecimal)
  }

  Closure roundedCombined = {
    BigDecimal sum = standardContribution
    gridOpen.each { ventId, pct -> sum += (pct as BigDecimal) }
    return sum / deviceCount
  }

  Set bumped = [] as Set
  int raises = 0
  while (roundedCombined() < MIN_COMBINED_VENT_FLOW && raises < MAX_ITERATIONS) {
    def raisable = gridOpen.findAll { ventId, pct -> (pct as int) < MAX_PERCENTAGE_OPEN }
    if (!raisable) { break }
    def pick = raisable.max { it.value }.key
    gridOpen[pick] = Math.min(MAX_PERCENTAGE_OPEN as int, (gridOpen[pick] as int) + granularity)
    bumped << pick
    raises++
  }

  // Rewrite ONLY the vents reconciliation actually raised (snapped to the grid so
  // dispatch rounding is a no-op on them). Every other vent keeps its continuous
  // value — the dispatcher still rounds it, but the floor guarantee already holds
  // because `roundedCombined()` accounted for those rounded contributions. This
  // keeps a grid-safe plan byte-for-byte unchanged and never zeroes a small vent
  // that this method did not deliberately raise.
  bumped.each { ventId -> calculatedPercentOpen[ventId] = gridOpen[ventId] }
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
        // R1.21 defer: a room with no resolvable temperature is omitted from the
        // open-percentage computation this cycle (left at its last commanded
        // position) rather than commanded on a fabricated 0°C reading.
        if (roomTemp == null) {
          log "Deferring open-% calc for '${roomName}': no resolvable temperature", 2
          return
        }
        
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

def calculateOpenPercentageForAllVents(rateAndTempPerVentId, String hvacMode, BigDecimal setpoint, longestTime,
    boolean closeInactive = true, Map perRoomTargetByVentId = null) {
  def percentOpenMap = [:]
  rateAndTempPerVentId.each { ventId, stateVal ->
    try {
      // R3.9: size and overshoot-close each room against its OWN resolved per-room
      // target when supplied; absent (or equal to the shared setpoint) -> identical
      // behavior to the shared-setpoint baseline.
      BigDecimal resolvedTarget = setpoint
      if (perRoomTargetByVentId != null && perRoomTargetByVentId[ventId] != null) {
        resolvedTarget = perRoomTargetByVentId[ventId] as BigDecimal
      }
      def percentageOpen = MIN_PERCENTAGE_OPEN
      if (closeInactive && !stateVal.active) {
        log "Closing vent on inactive room: ${stateVal.name}", 3
      } else if (hasRoomReachedSetpoint(hvacMode, resolvedTarget, stateVal.temp)) {
        // Directional setpoint check takes precedence over the rate-too-low shortcut:
        // a room at/past its resolved target in the conditioning direction must close (overshoot-close).
        def msg = hvacMode == COOLING ? 'cooler' : 'warmer'
        log "Closing vent: '${stateVal.name}' is already ${msg} (${stateVal.temp}) than target (${resolvedTarget})", 3
      } else if (stateVal.rate < MIN_TEMP_CHANGE_RATE) {
        log "Opening vent at max since change rate is too low: ${stateVal.name}", 3
        percentageOpen = MAX_PERCENTAGE_OPEN
      } else {
        percentageOpen = calculateVentOpenPercentage(stateVal.name, stateVal.temp, resolvedTarget, hvacMode, stateVal.rate, longestTime)
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
