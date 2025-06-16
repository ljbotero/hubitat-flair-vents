# Hubitat-Flair Vents Integration

## Project Overview

This project is a Hubitat home automation integration for Flair Smart Vents that provides intelligent HVAC airflow management through cloud API integration. The integration enables remote control of Flair vents and implements an advanced "Dynamic Airflow Balancing" (DAB) algorithm that automatically optimizes vent positions based on real-time temperature data and learned room efficiency patterns.

## Project File Structure

### Root Directory Files

- **`README.md`** - Project documentation and setup instructions for end users
- **`LICENSE`** - Apache License 2.0 legal terms
- **`packageManifest.json`** - Hubitat Package Manager metadata (version 0.16, author info, installation URLs)
- **`repository.json`** - Repository metadata for Hubitat package management
- **`build.gradle`** - Gradle build configuration for development and testing
- **`.groovylintrc.json`** - Groovy linting rules and code style configuration
- **`hubitat-flair-vents-device.png`** - Screenshot/image of Flair vent device in Hubitat interface

### Source Code (`src/` directory)

- **`hubitat-flair-vents-app.groovy`** - Main application file containing:
  - OAuth 2.0 authentication with Flair API
  - Device discovery and management
  - Dynamic Airflow Balancing algorithm implementation
  - Thermostat integration and HVAC cycle monitoring
  - API communication handlers and data processing

- **`hubitat-flair-vents-driver.groovy`** - Device driver file containing:
  - Individual vent device capabilities (SwitchLevel, Refresh, VoltageMeasurement)
  - Device attribute definitions (temperature, pressure, battery, room data)
  - Custom commands (setRoomActive)
  - Device lifecycle management (installed, updated, refresh)

- **`hubitat-ecobee-smart-participation.groovy`** - Separate integration for Ecobee thermostat smart participation features

### Test Suite (`tests/` directory)

- **`hubitat-flair-vents-app-tests.groovy`** - Unit tests for the main application logic
- **`hubitat-ecobee-smart-participation-tests.groovy`** - Unit tests for Ecobee integration

### File Relationships

1. **Package Manifest** (`packageManifest.json`) defines the installation package with references to:
   - Main app: `hubitat-flair-vents-app.groovy`
   - Device driver: `hubitat-flair-vents-driver.groovy`

2. **Parent-Child Architecture**:
   - App (`hubitat-flair-vents-app.groovy`) acts as parent managing multiple child devices
   - Driver (`hubitat-flair-vents-driver.groovy`) handles individual vent device instances
   - Communication flows: App ↔ Flair API ↔ Driver instances

3. **Development Infrastructure**:
   - Gradle build system for dependency management and testing
   - Groovy lint configuration for code quality
   - Comprehensive test suite for reliability

## High-Level Architecture

### System Components

1. **Hubitat Hub** - Local home automation controller running the integration
2. **Flair Cloud API** - RESTful API service at `https://api.flair.co` 
3. **Flair Smart Vents** - Physical motorized vents controlled via API
4. **Thermostat Integration** - Works with any Hubitat-compatible thermostat for temperature monitoring

### Integration Architecture

```
┌─────────────┐      OAuth 2.0      ┌──────────────┐
│ Hubitat Hub │◄────────────────────►│ Flair Cloud  │
│             │                      │     API      │
│  ┌─────────┐│      REST API       │              │
│  │   App   ││◄────────────────────►│              │
│  └────┬────┘│                      └──────┬───────┘
│       │     │                             │
│  ┌────▼────┐│                             │
│  │ Driver  ││                             │
│  └─────────┘│                      ┌──────▼───────┐
└─────────────┘                      │ Flair Vents  │
                                     └──────────────┘
```

## Low-Level Design

### Authentication Flow

The integration uses OAuth 2.0 client credentials flow:
1. User obtains Client ID and Client Secret from Flair
2. App exchanges credentials for access token at `/oauth2/token`
3. Token is stored in `state.flairAccessToken` 
4. All API calls include `Authorization: Bearer [token]` header
5. Token refresh scheduled hourly via `runEvery1Hour`

### Core Components

#### 1. Parent App (`hubitat-flair-vents-app.groovy`)

**Key Responsibilities:**
- OAuth authentication and token management
- Device discovery and creation
- Dynamic Airflow Balancing algorithm implementation
- API communication with Flair cloud
- Thermostat integration and HVAC state monitoring

**Main Classes/Methods:**
- `autheticate()` - OAuth token exchange
- `discover()` - Discovers vents via `/api/structures/{id}/vents`
- `initializeRoomStates()` - DAB algorithm initialization
- `finalizeRoomStates()` - Temperature change rate calculation
- `patchVent()` - Updates vent position via API
- `thermostat1ChangeStateHandler()` - HVAC cycle detection

#### 2. Device Driver (`hubitat-flair-vents-driver.groovy`)

**Capabilities:**
- `SwitchLevel` - Controls vent opening percentage (0-100%)
- `Refresh` - Updates device status from cloud
- `VoltageMeasurement` - Battery voltage monitoring

**Custom Commands:**
- `setRoomActive(true/false)` - Enable/disable room for DAB algorithm

**Key Attributes:**
- Physical: `percent-open`, `duct-temperature-c`, `duct-pressure`, `motor-run-time`
- Room: `room-current-temperature-c`, `room-active`, `room-occupied`
- Efficiency: `room-cooling-rate`, `room-heating-rate`

### Dynamic Airflow Balancing Algorithm

The DAB algorithm is the core innovation that optimizes HVAC efficiency:

#### 1. Temperature Change Rate Learning

```groovy
// Calculate rate of temperature change per room
rate = Math.abs(endTemp - startTemp) / runTimeMinutes
// Adjust for partial vent opening
approxEquivMaxRate = (rate / maxRate) / (percentOpen / 100)
// Rolling average over 4 cycles
newRate = rollingAverage(currentRate, approxEquivMaxRate, weight, 4)
```

#### 2. Predictive Vent Positioning

When HVAC starts, the algorithm:
1. Calculates time to reach setpoint for each room based on learned rates
2. Finds the room that will take longest (`longestTimeToGetToTarget`)
3. Calculates optimal vent opening percentage using exponential formula:

```groovy
percentageOpen = BASE_CONST * Math.exp((targetRate / maxRate) * EXP_CONST)
// Where: BASE_CONST = 0.0991, EXP_CONST = 2.3
```

#### 3. Minimum Airflow Protection

Ensures combined airflow doesn't drop below 30% to prevent HVAC damage:
- Accounts for both smart and conventional vents
- Assumes conventional vents at 50% open
- Proportionally increases vent openings if below threshold

#### 4. Pre-Adjustment Logic

Detects when thermostat is about to start HVAC cycle:
- Monitors temperature approaching setpoint (within 0.2°C)
- Pre-positions vents before HVAC starts for efficiency

### API Integration Details

**Base URL:** `https://api.flair.co`

**Key Endpoints:**
- `GET /api/structures` - Get user's homes
- `GET /api/structures/{id}/vents` - Discover vents
- `GET /api/vents/{id}/current-reading` - Get vent status
- `GET /api/vents/{id}/room` - Get room details
- `PATCH /api/vents/{id}` - Update vent position
- `PATCH /api/rooms/{id}` - Update room active status

**Data Models:**
- Vent attributes: `percent-open`, `duct-temperature-c`, `firmware-version-s`
- Room attributes: `current-temperature-c`, `active`, `occupied`
- Structure attributes: `mode` (manual/auto)

### Performance Optimizations

1. **Asynchronous HTTP Calls** - Uses `asynchttpGet/Patch` for non-blocking operations
2. **Efficient Polling** - Configurable refresh interval (default 3 minutes)
3. **State Caching** - Minimizes API calls by tracking state locally
4. **Batch Operations** - Groups vent updates when possible

## Configuration & Setup

### Prerequisites

1. Hubitat Elevation hub (any version)
2. Flair Smart Vents installed and configured
3. Flair API credentials (obtained via [request form](https://forms.gle/VohiQjWNv9CAP2ASA))
4. Compatible thermostat integrated with Hubitat

### Installation Steps

1. Install driver code in Hubitat → Drivers Code
2. Install app code in Hubitat → Apps Code
3. Add app instance and configure OAuth credentials
4. Discover vents (creates child devices automatically)
5. Configure DAB settings:
   - Select primary thermostat
   - Set conventional vent count
   - Enable/disable inactive room closing

## Key Algorithms & Mathematical Models

### Temperature Change Rate Calculation

The system learns how efficiently each room heats/cools:

```
EffectiveRate = ΔTemperature / (Time × VentOpenPercentage)
```

Constraints:
- MIN_TEMP_CHANGE_RATE_C = 0.001°C/min
- MAX_TEMP_CHANGE_RATE_C = 1.5°C/min
- MIN_MINUTES_TO_SETPOINT = 1 minute (for valid calculations)

### Exponential Vent Opening Model

Based on empirical testing, vent opening follows exponential relationship:

```
OpenPercentage = 0.0991 × e^(2.3 × TargetRate/MaxRate)
```

This provides:
- Rapid opening for rooms far from setpoint
- Gradual throttling as rooms approach target
- Smooth transitions preventing oscillation

### Combined Airflow Calculation

```
CombinedFlow = Σ(SmartVentOpen%) + (ConventionalVents × 50%)
MinimumFlow = TotalVents × 30%
```

## Testing & Development

### Running Unit Tests

**Quick Commands:**
```bash
# Run all tests
gradle test

# Clean build with coverage
gradle clean test jacocoTestReport

# View results
open build/reports/tests/test/index.html
open build/reports/jacoco/test/html/index.html
```

### Test Suite Organization

**Specialized Test Files:**
- `math-calculations-tests.groovy` - Mathematical utility functions
- `temperature-conversion-tests.groovy` - Temperature conversion and validation
- `room-setpoint-tests.groovy` - Room temperature and setpoint logic
- `time-calculations-tests.groovy` - Time-based calculations and predictions
- `vent-opening-calculations-tests.groovy` - Core DAB algorithm calculations
- `room-change-rate-tests.groovy` - Temperature change rate learning
- `airflow-adjustment-tests.groovy` - Minimum airflow safety calculations
- `hubitat-flair-vents-app-tests.groovy` - Legacy comprehensive tests

**Test Coverage:**
- **50+ test cases** covering all critical algorithms
- **Mathematical precision** validation
- **Edge case testing** with null/zero/invalid inputs
- **Multi-room scenarios** with realistic HVAC data
- **Safety constraint validation** for minimum airflow

**Architecture Testing:**
- Temperature change rate learning algorithms
- Exponential vent opening calculations
- Rolling average statistical functions
- HVAC mode determination logic
- Airflow safety constraint validation

### Testing Framework

**Stack:**
- **Spock Framework 2.3** - BDD-style testing
- **Groovy 4.0.15** - Native language support
- **JaCoCo 0.8.8** - Coverage reporting
- **Hubitat CI 0.17** - Sandbox environment simulation

**Coverage Limitations:**
Due to Hubitat CI's dynamic class loading, JaCoCo cannot track coverage of sandbox-executed code. However, comprehensive test scenarios validate all critical functionality through direct method invocation.

**Focus on test quality over coverage metrics** - the extensive scenarios validate behavior more effectively than coverage percentages.

## External Documentation Links

### Hubitat Development
- [Hubitat Developer Documentation](https://docs.hubitat.com/)
- [Hubitat Driver Capabilities](https://docs.hubitat.com/index.php?title=Driver_Capability_List)
- [Hubitat App Development](https://docs.hubitat.com/index.php?title=Developer_Documentation)

### Flair API Documentation
- [Flair Developer Portal](https://docs.flair.co/)
- [Flair API Reference](https://api.flair.co/api/docs) (requires authentication)

### Community Resources
- [Hubitat Community Thread](https://community.hubitat.com/t/new-control-flair-vents-with-hubitat-free-open-source-app-and-driver/132728)
- [GitHub Repository](https://github.com/ljbotero/hubitat-flair-vents)

### Testing Documentation
- [TESTING.md](../TESTING.md) - Comprehensive testing guide
- [Spock Framework Documentation](https://spockframework.org/spock/docs/)
- [JaCoCo Documentation](https://www.jacoco.org/jacoco/trunk/doc/)

## Technical Considerations

### Limitations

1. **Cloud Dependency** - Requires internet connection and Flair cloud availability
2. **API Rate Limits** - Unknown limits, but uses conservative polling
3. **Temperature Sensor Accuracy** - Relies on thermostat/sensor precision
4. **Vent Motor Wear** - Minimizes adjustments to extend hardware life

### Best Practices

1. **Sensor Placement** - Position temperature sensors away from vents for accurate readings
2. **Conventional Vent Count** - Accurately count non-smart vents for proper airflow calculation
3. **HVAC Compatibility** - Ensure HVAC system can handle variable airflow
4. **Room Configuration** - Mark unused rooms as inactive to improve efficiency

### Security Considerations

- OAuth tokens stored in Hubitat's secure state storage
- All API communication over HTTPS
- No sensitive data logged in debug mode
- Client credentials should be kept confidential
