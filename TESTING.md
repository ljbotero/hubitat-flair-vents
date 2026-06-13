# Testing Guide for Hubitat-Flair Vents Integration

## Off-device JVM/Spock harness (DAB v2 — spec `hubitat-flair-vents-dab-v2`)

The DAB v2 work runs entirely **off-device** on the JVM with Spock — no physical
Hubitat hub (R18.1). The same root Gradle build compiles two source trees:

- `src/` — the Hubitat app + child drivers (exercised through the `hubitat_ci`
  sandbox).
- `libraries/` — the **pure** DAB v2 math modules (`dabv2-*.groovy`). These
  contain **no** Hubitat platform APIs, no wall-clock time, and no randomness, so
  the identical source is compiled verbatim here and `#include`-d by the app
  on-device (R18.2 / R18.7). They compile and are asserted against directly on
  the JVM (see `tests/harness-smoke-tests.groovy`).

A Gradle wrapper is provided, so use `./gradlew` (the documented commands below).

### Documented run commands (R18.6)

```bash
# from the repo root (hubitat-flair-vents/)
./gradlew test                       # full off-device suite (unit + property + parity + persistence)
./gradlew test --tests '*Parity*'    # parity gate only (added by task 8)
./gradlew test --tests '*Property*'  # property-based suite only
./gradlew codenarcMain codenarcTest  # quality / lint gate (npm-free, see below)
```

### Property-based tests — tagging convention

The harness standardizes on **Spock data-driven generators + a small seeded
random helper** (`tests/support/PropertyGen.groovy`), not jqwik: Spock
1.2-groovy-2.5 runs on the JUnit 4 platform that the existing suite depends on,
and adding a JUnit 5 property engine would fork the test platform. `PropertyGen`
supplies randomized, reproducible inputs and the iteration count.

Each Correctness Property (1–17 in `design.md`) is implemented by **exactly one**
Spock feature method that:

1. lives in a spec class whose name contains `Property` (so `--tests '*Property*'`
   selects it — e.g. `SafetyFloorPropertySpec`);
2. is named with the **exact tag** `Feature: hubitat-flair-vents-dab-v2, Property N: <text>`;
3. drives `>= PropertyGen.ITERATIONS` (100) randomized examples via a `where:`
   block (`i << (0..<PropertyGen.ITERATIONS)`), seeding `PropertyGen.forIteration(i)`
   so any failing iteration is reproducible.

`tests/harness-property-tests.groovy` (`HarnessPropertySpec`) is the worked
example of this convention.

### Parity evidence gate — shared fixtures + Reference regeneration (R17, D9)

The DAB v2 port is validated by **parity** against the validated Python
Reference (`hvac_vent_optimizer`) rather than a Groovy simulator (decision D9).
Both sides consume the **same** portable JSON scenarios; the Reference produces
the expected outputs.

- **Fixtures live in `tests/resources/parity/`** as `*.scenario.json` (the
  serialized allocator input + settings — platform-neutral) and
  `*.expected.json` (the Reference-generated commanded apertures,
  `airflowLimited`, `floorBinding`, `combinedOpenPct`, the stamped
  `referenceCommit` git SHA, and `tolerance.aperturePoints`).
  > Note: the design refers to `test/resources/parity/`; the actual harness
  > test source root is `tests/` (see `build.gradle` `sourceSets`), so fixtures
  > are under `tests/resources/parity/`. The loader also accepts the `test/`
  > spelling as a fallback.
- **Groovy loader:** `tests/support/ParityFixtures.groovy` parses a scenario
  into the pure `RoomAllocInput` / `AllocSettings` / `DuctSignals` (+ learned
  `VentCurve`) types and the expected file into `ParityExpected`. The schema is
  exercised by `ParitySchemaSpec` (`tests/parity-schema-tests.groovy`).
- **Schema test only here (task 8.1):** the six required comfort-critical
  scenarios are authored by task 8.2; the ±1-point parity comparison
  (Property 17) is task 8.3.

#### Regenerate the Reference expected outputs

A committed Python tool, `tools/gen_parity_fixtures`, drives the Reference's
HA-free `balance`/`learning` modules over the shared scenarios and rewrites the
`*.expected.json` files, stamping each with the Reference git SHA. It loads the
Reference modules directly by file path, so **no Home Assistant install is
needed**.

```bash
# from the hubitat-flair-vents working copy, pointing at your hvac_vent_optimizer checkout
python tools/gen_parity_fixtures \
    --reference ../hvac_vent_optimizer \
    --scenarios tests/resources/parity \
    --out       tests/resources/parity
```

Short form (uses the defaults above): `python tools/gen_parity_fixtures`. See
`tools/gen_parity_fixtures/README.md` for all options and the SHA-stamping
details. Run `./gradlew test --tests '*Parity*'` afterwards to validate.

### Quality / lint gate — CodeNarc (npm-free)

`./gradlew codenarcMain codenarcTest` reproduces the lint gate **without npm**.
The frozen warning baseline in `docs/quality-baseline.md` was captured with
`npm-groovy-lint` (which embeds CodeNarc); the Gradle `codenarc` tasks are the
npm-free equivalent so CI/local runs need no Node toolchain. Config:

- ruleset: `config/codenarc/codenarc.groovy` (mirrors `.groovylintrc.json` —
  `"extends": "all"`, `LineLength = 160`, `Indentation = 2 spaces`, and the same
  disabled rules);
- `codenarcMain` lints `src/` + `libraries/`; `codenarcTest` lints `tests/`;
- runs with `ignoreFailures = true` — the gate is "no net increase per modified
  file vs. baseline" (R18.4), so the reports under
  `build/reports/codenarc/{main,test}.{html,txt,xml}` are the source of truth,
  not a hard pass/fail.

> Note: the legacy `gradle test` commands below still work. New DAB v2 work
> should prefer `./gradlew`.

## **Quick Start - Running Tests**

### **Basic Test Commands**

```bash
# Run all tests
gradle test

# Clean build and run tests with coverage
gradle clean test jacocoTestReport

# Full test suite with coverage verification
gradle clean test jacocoTestReport jacocoTestCoverageVerification
```

### **Java Version Compatibility**
The project uses Java 17. If you need to specify Java version:

```bash
# macOS/Linux with Java 17
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home gradle test
```

### **View Test Results**

```bash
# Open test results in browser
open build/reports/tests/test/index.html

# Open coverage report in browser  
open build/reports/jacoco/test/html/index.html

# View XML reports (for CI/CD)
cat build/reports/tests/test/*.xml
cat build/reports/jacoco/test/jacocoTestReport.xml
```

## **Test Structure**

### **Test Categories**

The test suite is organized into focused test files:

- **`math-calculations-tests.groovy`** - Mathematical utility functions
- **`temperature-conversion-tests.groovy`** - Temperature conversion and validation
- **`room-setpoint-tests.groovy`** - Room temperature and setpoint logic
- **`time-calculations-tests.groovy`** - Time-based calculations and predictions
- **`vent-opening-calculations-tests.groovy`** - Core DAB algorithm calculations
- **`room-change-rate-tests.groovy`** - Temperature change rate learning
- **`airflow-adjustment-tests.groovy`** - Minimum airflow safety calculations
- **`hubitat-flair-vents-app-tests.groovy`** - Legacy comprehensive tests

### **Coverage Areas**

✅ **Mathematical Functions**
- Rounding algorithms
- Rolling averages  
- Statistical calculations
- Precision handling

✅ **Temperature Logic**
- Celsius/Fahrenheit conversion
- Setpoint validation
- Room temperature tracking
- HVAC mode determination

✅ **Dynamic Airflow Balancing (DAB)**
- Vent opening percentage calculations
- Longest time-to-target predictions
- Temperature change rate learning
- Airflow safety minimums

✅ **Edge Cases & Error Handling**
- Null value handling
- Division by zero protection
- Invalid input validation
- Boundary condition testing

## **Running Specific Tests**

```bash
# Run specific test class
gradle test --tests "bot.flair.MathCalculationsTest"
gradle test --tests "bot.flair.VentOpeningCalculationsTest"

# Run tests matching pattern
gradle test --tests "*Temperature*"
gradle test --tests "*DAB*"

# Run with detailed output
gradle test --info

# Run with debug logging
gradle test --debug
```

## **Continuous Integration**

### **GitHub Actions / CI Pipeline**
```yaml
# Add to your CI pipeline
- name: Run Tests
  run: gradle clean test jacocoTestReport
  
- name: Upload Coverage
  uses: actions/upload-artifact@v3
  with:
    name: coverage-report
    path: build/reports/jacoco/test/html/
```

### **Test Automation**
```bash
# Watch mode (requires gradle plugin)
gradle test --continuous

# Test on file changes
gradle test --watch-fs
```

## **Test Quality Metrics**

### **Current Test Coverage**
- **Test Files**: 8 specialized test suites
- **Test Methods**: 50+ individual test cases
- **Algorithm Coverage**: All core DAB algorithms tested
- **Edge Cases**: Comprehensive boundary testing
- **Mock Data**: Realistic HVAC scenarios

### **Critical Functions Tested**

| Function | Test Coverage | Edge Cases |
|----------|---------------|------------|
| `calculateHvacMode` | ✅ Full | Temperature boundaries |
| `hasRoomReachedSetpoint` | ✅ Full | Heating/cooling modes |
| `calculateVentOpenPercentange` | ✅ Full | Mathematical precision |
| `calculateOpenPercentageForAllVents` | ✅ Full | Multi-room scenarios |
| `adjustVentOpeningsToEnsureMinimumAirflowTarget` | ✅ Full | Safety constraints |
| `calculateRoomChangeRate` | ✅ Full | Learning algorithm |
| `rollingAverage` | ✅ Full | Statistical accuracy |
| `convertFahrenheitToCentigrades` | ✅ Full | Temperature conversion |

## **Test Data & Scenarios**

### **Realistic Test Data**
Tests use realistic HVAC scenarios:
- **Temperature ranges**: 65°F - 85°F (18°C - 29°C)
- **Vent openings**: 0% - 100% in 5% increments
- **Room efficiency rates**: 0.001 - 1.5 °C/minute
- **Multi-room configurations**: Up to 8 rooms
- **Mixed vent types**: Smart + conventional vents

### **Edge Case Testing**
- **Null values**: All functions handle null inputs
- **Zero values**: Division by zero protection
- **Extreme temperatures**: Beyond normal HVAC ranges
- **Boundary conditions**: Min/max values for all parameters
- **Invalid inputs**: Negative values, out-of-range data

## **Debugging Test Failures**

### **Common Issues**
```bash
# Gradle version conflicts
./gradlew wrapper --gradle-version 8.14.2

# Java version issues  
java -version
./gradlew -version

# Clean build issues
gradle clean build

# Dependency conflicts
gradle dependencies
```

### **Test Output Analysis**
```bash
# Verbose test output
gradle test --info | grep -E "(PASSED|FAILED|ERROR)"

# Failed test details
gradle test --continue | tee test-output.log
```

## **Performance Testing**

### **Test Execution Time**
- **Full test suite**: ~6-8 seconds
- **Individual test file**: ~1-2 seconds
- **Coverage report generation**: ~2-3 seconds

### **Optimization Tips**
```bash
# Parallel test execution
gradle test --parallel

# Skip coverage for faster feedback
gradle test -x jacocoTestReport

# Test only changed code
gradle test --continuous
```

## **Architecture Notes**

### **Test Framework Stack**
- **Spock Framework**: BDD-style testing
- **Groovy**: Native language support
- **JaCoCo**: Coverage reporting  
- **Hubitat CI**: Sandbox environment simulation

### **Coverage Limitations**
Due to the Hubitat CI framework's dynamic class loading, JaCoCo cannot track coverage of sandbox-executed code. However, the test suite provides comprehensive validation of all critical algorithms through direct method invocation.

**Focus on test quality over coverage metrics** - the extensive test scenarios validate functionality more effectively than coverage percentages.
