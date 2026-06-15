# AGENTS.md — Hubitat Integration for Flair Smart Vents

Guidance for AI coding agents working in this project. Tool-agnostic: this is the
single source of truth, referenced by Kiro steering, and readable by any agent
that supports `AGENTS.md` / `CLAUDE.md` / `.cursorrules` conventions.

---

## Product

A free, open-source Hubitat app + drivers that control [Flair Smart Vents](https://flair.co/)
to bring intelligent, adaptive air management to a home's HVAC system.

### What it does

- Exposes each Flair vent and puck as an individual Hubitat device.
- Provides remote and rule-based control of vent open level (`setLevel`, 0–100)
  and room activity (`setRoomActive true|false`).
- Runs **Dynamic Airflow Balancing (DAB)**: learns each room's heating/cooling
  efficiency and positions vents to reach target temperatures with fewer
  adjustments (longer motor/battery life, quieter operation).
- Enforces a **minimum-airflow safety floor** so the HVAC system is never starved,
  including homes that mix smart and conventional vents.

### DAB strategies

- **Legacy DAB** — original strategy. Default for existing installs.
- **DAB v2 "balance"** — synchronized-convergence allocator with learned
  per-room/per-vent efficiency, airflow-limited cross-coupling, and an inviolable
  airflow safety floor. Default for new installs, **parity-gated** against the
  validated Python reference. Adds opt-in per-room and zone-summary diagnostic devices.

### Distribution

- Author: Jaime Botero. Licensed Apache-2.0.
- Installed via Hubitat Package Manager / a single Hubitat **Bundle**
  (`bundles/flair-vents.v<version>.zip`) containing the app, both drivers, and the
  `FlairVentsDabv2` library. Support/discussion: Hubitat community forum.

### Guiding priorities

1. **Protect the HVAC system** — the airflow safety floor is inviolable.
2. **Minimize vent actuation** — fewer moves preserve motors and battery.
3. **Preserve backward compatibility** — existing installs keep legacy behavior
   until explicitly opted in.
4. **Parity over reinvention** — DAB v2 behavior must match the validated Python reference.

---

## Tech stack & commands

### Languages & platform

- **Groovy** targeting the **Hubitat** app/driver sandbox.
- **Java 11** toolchain for compilation (`build.gradle` sets language version 11;
  some docs mention 17 — prefer the JDK the Gradle toolchain resolves).
- Groovy `2.5.4` (matches the Hubitat platform runtime).

### Build / test system

- **Gradle** with the wrapper. Prefer `./gradlew` over a system `gradle`.
- Plugins: `groovy`, `jacoco` (coverage), `codenarc` (lint).
- Non-standard source layout: `srcDirs = ['src', 'libraries']` for main, `['tests']` for test.

Key dependencies:

- `org.spockframework:spock-core:1.2-groovy-2.5` — BDD test framework (runs on JUnit 4).
- `me.biocomp.hubitat_ci:hubitat_ci:0.17` — Hubitat sandbox simulation for off-device tests.
- `http-builder`, `commons-io`, `byte-buddy` (mocking).

### Common commands

Run from the repo root (`hubitat-flair-vents/`):

```bash
./gradlew test                       # full off-device suite (unit + property + parity + persistence)
./gradlew test --tests '*Parity*'    # parity gate only
./gradlew test --tests '*Property*'  # property-based suite only
./gradlew clean test jacocoTestReport            # tests + coverage report
./gradlew codenarcMain codenarcTest  # lint gate (npm-free CodeNarc)
```

Reports:

- Tests: `build/reports/tests/test/index.html`
- Coverage: `build/reports/jacoco/test/html/index.html`
- Lint: `build/reports/codenarc/{main,test}.{html,txt,xml}`

Regenerate parity fixtures (Python reference):

```bash
python tools/gen_parity_fixtures \
    --reference ../hvac_vent_optimizer \
    --scenarios tests/resources/parity \
    --out       tests/resources/parity
```

### Code style & quality

- Lint config: `.groovylintrc.json` (`"extends": "all"`), mirrored npm-free in
  `config/codenarc/codenarc.groovy`.
- **2-space indentation**, **max line length 160**.
- `def` / untyped params allowed; explicit return/parameter types not required.
- Quality gate is **"no net increase per modified file vs. the recorded CodeNarc
  baseline"**, not a hard zero. `ignoreFailures = true` — reports are the source of truth.
- Coverage thresholds are advisory (warn-only); JaCoCo cannot track sandbox-loaded
  code, so favor meaningful test scenarios over coverage percentage.

### Hubitat platform constraints (must follow)

- **Async HTTP only** (`asynchttpGet`/`asynchttpPatch`); never blocking `httpGet`/`httpPost`.
- Use **`atomicState`** for shared/concurrent data; `state` only for single-thread data.
- **Store IDs, never device objects** in state (avoids memory leaks).
- Method execution budget ~20s; break up long work with `runInMillis()`.
- Clean up schedules/subscriptions/state in `uninstalled()`.
- OAuth 2.0 tokens live in `state`, never logged; auto re-auth on 401/403.

### On-hub compile killers (the app must SAVE in the editor) — hard-won

The off-device Spock/`hubitat_ci` harness compiles fast and does **NOT** reproduce
the on-hub sandbox compiler, so a green test suite does **not** guarantee the app
will save on a real hub. Two constructs make Hubitat's sandbox AST transform blow
up superlinearly — the editor/HPM save spins forever with **no error in the logs**
(only a `performUpdates ran for NNNms` warning). Both were real outages in this app:

1. **No `methodMissing` / `propertyMissing` / `invokeMethod` (any metaclass/MOP hook).**
   Defining one forces the sandbox to route *every* dynamic call in the entire class
   through it → pathological compile. A `cross-cutting-invariants-guard` test fails the
   build if `methodMissing`/`propertyMissing` is reintroduced — keep it.
   - If you need to dispatch a scheduled job whose name is dynamic (e.g. per-zone),
     do **not** encode data in the handler name. Use a **single static handler** and
     pass the data in the scheduler `data:` map:
     `runIn(sec, 'myHandler', [overwrite:false, data:[zoneId: z]])` →
     `void myHandler(Map data) { ... data.zoneId ... }`.
2. **No deeply *nested* closures** (a closure that contains another closure, e.g.
   `Closure<Double> f = { ... list.each { ... } }`). Each closure compiles to its own
   class; nesting them is what detonates. Flat single-level closures (`list.each {}`)
   are fine. Prefer plain `for` loops and **extract** any inner closure into a top-level
   helper method — this is exactly why the pure library (typed `for` loops, no closures,
   no MOP) compiles in ~5s while a closure/MOP-heavy app of similar size hangs.

General rule: keep app methods closure-light and MOP-free; mirror the library's
typed, `for`-loop, helper-method style for anything hot or large.

**Diagnosing a save hang without the UI** (Hub Security off): drive the save endpoint
and time it — `POST /app/save` with `id=` empty + `create=true` + `source=<code>`
compiles and returns **302** on success, **200** (editor page) on a compile error, or
hangs/times out on the pathological case. Read current source/version via
`GET /app/ajax/code?id=<id>`. Binary-search by creating shells with the first K methods
to bisect to the offending construct. (Hubitat exposes no app-code *delete* endpoint on
2.5.x firmware — throwaway diagnostic apps must be removed from the Apps Code UI.)


### DAB v2 library purity rule

`libraries/flair-vents-dabv2.groovy` (`FlairVentsDabv2`) is **pure**: no Hubitat
APIs, no wall-clock time, no randomness, no `state`/`atomicState`. The identical
source compiles under the Spock harness and is `#include`d on-device. No `import`
or `package` lines — the `library()` call must come first; fully qualify types inline.

---

## Project structure

```
hubitat-flair-vents/
├── src/                  # Hubitat app + child drivers (run in the Hubitat sandbox)
├── libraries/            # PURE DAB v2 math modules (#include-d on-device)
├── tests/                # Spock specs (off-device JVM harness)
│   ├── resources/        # test fixtures, incl. resources/parity/*.json
│   └── support/          # test helpers (PropertyGen, ParityFixtures)
├── tools/                # tooling (e.g. gen_parity_fixtures Python reference driver)
├── config/codenarc/      # CodeNarc ruleset (npm-free lint mirror)
├── bundles/              # packaged Hubitat Bundle zips (release artifact)
├── build.gradle / settings.gradle / gradlew*   # Gradle build
├── packageManifest.json / repository.json      # Hubitat Package Manager metadata
├── .groovylintrc.json    # lint config (source of truth, mirrored in config/codenarc)
├── architecture.md       # architecture + best-practices reference
├── README.md / TESTING.md / CHANGELOG.md
```

### Source files (`src/`)

- `hubitat-flair-vents-app.groovy` — **parent app**: OAuth, device discovery,
  DAB orchestration, throttled async API communication. Constants declared as
  `@Field static final` at top.
- `hubitat-flair-vents-driver.groovy` — **vent** driver (SwitchLevel capability).
- `hubitat-flair-vents-pucks-driver.groovy` — **puck** driver (temp/humidity/motion).
- `hubitat-ecobee-smart-participation.groovy` — companion Ecobee helper app.

### Library (`libraries/`)

- `flair-vents-dabv2.groovy` — `FlairVentsDabv2` library bundling the pure DAB v2
  modules: Context_Mapper (`ctx*`), Learning_Model (`lrn*`), Safety_Floor (`sf*`),
  Allocator (`alloc*`), Model I/O (`mio*`). Top-level methods + plain Maps as value
  objects (the sandbox rejects user classes). Module-prefixed names stand in for
  the missing class namespace.
- `dabv2-placeholder.groovy` — placeholder/scaffolding.

### Tests (`tests/`)

- Spock specs named `*-tests.groovy`; classes contain `Test`/`Tests`/`Spec`.
- **Property-based** specs contain `Property` in the class name; each Correctness
  Property (1–17 in the spec design) maps to exactly one feature method named
  `Feature: hubitat-flair-vents-dab-v2, Property N: <text>`, driven by
  `support/PropertyGen` (seeded, 100 iterations) for reproducibility.
- **Parity** specs (`*Parity*`) validate DAB v2 against the Python reference using
  shared fixtures in `tests/resources/parity/` (`*.scenario.json` + `*.expected.json`),
  loaded via `support/ParityFixtures`.

### Naming & architecture conventions

- Hubitat namespace: `bot.flair`.
- **Parent-child** device architecture; children call `parent.*`, parent updates
  children via `sendEvent`. Always verify `parent` exists.
- Descriptive method names (`calculateVentOpenPercentage()`, not `calc()`);
  aim for single-responsibility methods under ~50 lines.
- Group related constants with documented units.

### Generated / ignored (do not edit by hand)

- `build/`, `bin/`, `.gradle/` — build outputs and caches.
