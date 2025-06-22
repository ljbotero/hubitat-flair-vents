# Hubitat-Flair Vents Integration Architecture

**Version 0.2**

## Overview

A Hubitat integration for Flair Smart Vents that implements Dynamic Airflow Balancing (DAB) - an algorithm that learns room heating/cooling efficiency and automatically optimizes vent positions to reduce HVAC runtime.

## Architecture

### System Components

```
┌─────────────┐      OAuth 2.0      ┌──────────────┐
│ Hubitat Hub │◄────────────────────►│ Flair Cloud  │
│             │                      │     API      │
│  ┌─────────┐│   Async REST API    │              │
│  │   App   ││◄────────────────────►│              │
│  └────┬────┘│   (Throttled)       └──────┬───────┘
│       │     │                             │
│  ┌────▼────┐│                      ┌──────▼───────┐
│  │ Drivers ││                      │ Flair Devices│
│  └─────────┘│                      │ (Vents/Pucks)│
└─────────────┘                      └──────────────┘
```

### Core Files

- **`hubitat-flair-vents-app.groovy`** - Parent app handling OAuth, device discovery, DAB algorithm, and API communication
- **`hubitat-flair-vents-driver.groovy`** - Vent driver (SwitchLevel capability)
- **`hubitat-flair-vents-pucks-driver.groovy`** - Puck driver (Temperature/Humidity/Motion sensors)

## Core Features

### API Integration
- OAuth 2.0 authentication with automatic token refresh
- Automatic authentication on startup and token expiration (401/403 errors)
- Asynchronous REST API communication
- Request throttling and queueing system
- Response caching for performance optimization
- Graceful error handling and retry logic

### Dynamic Airflow Balancing (DAB)
- Machine learning algorithm that tracks room efficiency
- Exponential model for optimal vent positioning
- Safety constraints to protect HVAC system (minimum 30% airflow)
- Pre-adjustment based on temperature trends
- Support for mixed smart and conventional vent systems

### Device Management
- Parent-child architecture for scalability
- Support for Flair Vents (motorized dampers)
- Support for Flair Pucks (temperature/humidity sensors)
- Real-time device status updates
- Configurable polling intervals

## Development Best Practices

### 1. Test-Driven Development (TDD)
**MANDATORY for all new features:**
- Write tests FIRST before implementation
- Follow red-green-refactor cycle
- Maintain minimum 80% code coverage
- Run tests with: `gradle clean test`

### 2. State Management
```groovy
// ALWAYS use atomicState for concurrent data
atomicState.sharedData = [...]

// Use state only for single-thread data
state.localData = [...]

// NEVER store device objects
atomicState.deviceIds = ['id1', 'id2']  // Correct
state.devices = [device1, device2]      // Wrong - memory leak
```

### 3. Asynchronous Programming
- ALWAYS use async HTTP methods: `asynchttpGet()`, `asynchttpPatch()`
- NEVER use blocking calls: `httpGet()`, `httpPost()`
- Implement proper callbacks for all async operations
- Handle timeouts gracefully (5 second limit)

### 4. Error Handling
```groovy
// Validate all external data
if (!isValidResponse(resp)) { return }

// Check device existence
def device = getChildDevice(id)
if (!device) {
    logError "Device not found: ${id}"
    return
}

// Use safe navigation
def value = device?.currentValue('temperature') ?: 0
```

### 5. Performance Optimization
- Maximum 20 seconds execution time per method
- Break long operations into smaller chunks
- Use `runInMillis()` for delayed execution
- Implement caching for expensive operations
- Minimize state storage size

### 6. Code Organization
- Single Responsibility Principle - one method, one purpose
- Maximum 50 lines per method
- Descriptive naming: `calculateVentOpenPercentage()` not `calc()`
- Group related constants together
- Use type hints where possible

### 7. Logging Best Practices
```groovy
// Implement debug levels
private log(String msg, int level = 3) {
    if (settings?.debugLevel >= level) {
        log.debug msg
    }
}

// Disable verbose logging in production
// Use appropriate levels: 1=verbose, 2=info, 3=warn, 4=error
```

### 8. Parent-Child Communication
```groovy
// Child calling parent methods
parent.getDeviceData(device)
parent.patchVent(device, percentOpen)

// Parent updating child
sendEvent(device, [name: 'temperature', value: 75.5, unit: '°F'])

// Always verify parent exists
if (parent) { parent.someMethod() }
```

### 9. Memory Management
- Clear state in `uninstalled()` method
- Remove scheduled jobs and subscriptions
- Don't store large objects or arrays
- Use device IDs instead of device references
- Implement periodic cleanup for caches
- Avoid concurrent modification by collecting keys before modifying maps

### 10. Security Considerations
- Store OAuth tokens in `state`, not settings
- OAuth Client Secret displayed as password field (dots/asterisks)
- Never log sensitive data (tokens, passwords)
- Validate all user inputs
- Sanitize data before API calls
- Use HTTPS for all external communications
- Automatic re-authentication on 401/403 errors

### 11. Testing Requirements
```groovy
// Test structure using Spock
def "should handle null input gracefully"() {
    given: "null parameters"
    def input = null
    
    when: "method is called"
    def result = someMethod(input)
    
    then: "returns safe default"
    result == 0
}
```

Test categories:
- Unit tests for all calculations
- Integration tests for API communication
- Edge case tests (null, zero, negative, extreme values)
- Concurrency tests for state management
- Performance tests for long-running operations

### 12. Documentation Standards
- Document all public methods with purpose and parameters
- Include usage examples for complex methods
- Maintain up-to-date README
- Document all constants with units
- Keep architecture documentation current

### 13. Code Review Checklist
Before submitting PR, ensure:
- [ ] All tests pass
- [ ] Code coverage ≥ 80%
- [ ] No blocking HTTP calls
- [ ] Proper error handling
- [ ] Logging uses debug levels
- [ ] Methods < 50 lines
- [ ] State management uses atomicState correctly
- [ ] Memory cleanup in uninstalled()
- [ ] Documentation updated

## API Endpoints

```
GET  /api/structures              # Get homes
GET  /api/structures/{id}/vents   # Discover vents
GET  /api/structures/{id}/pucks   # Discover pucks
GET  /api/vents/{id}/current-reading
GET  /api/vents/{id}/room
PATCH /api/vents/{id}             # Update vent position
PATCH /api/rooms/{id}             # Update room active status
```

## Key Algorithms

### Temperature Change Rate Learning
```groovy
rate = Math.abs(endTemp - startTemp) / runTimeMinutes
adjustedRate = (rate / maxRate) / (percentOpen / 100)
```

### Vent Position Optimization
```groovy
percentOpen = 0.0991 * Math.exp((targetRate / maxRate) * 2.3)
```

### Minimum Airflow Protection
```groovy
combinedFlow = Σ(SmartVentOpen%) + (ConventionalVents × 50%)
if (combinedFlow < 30%) { adjustVentsProportionally() }
```

## Common Pitfalls to Avoid

1. **Blocking Operations** - Always use async methods
2. **Memory Leaks** - Clear state and unsubscribe properly
3. **Race Conditions** - Use atomicState for shared data
4. **Null Pointer Exceptions** - Use safe navigation (?.)
5. **Excessive Logging** - Respect debug levels
6. **Large State Objects** - Store IDs, not objects
7. **Missing Error Handling** - Validate all inputs
8. **Ignoring Timeouts** - Set appropriate HTTP timeouts
9. **Poor Test Coverage** - TDD is mandatory
10. **Undocumented Code** - Document as you code
11. **Concurrent Modification** - Collect keys before modifying maps during iteration
12. **Manual Authentication** - Implement automatic authentication and re-authentication
