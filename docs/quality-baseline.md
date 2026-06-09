# Quality Baseline — `hubitat-flair-vents`

> Captured by task **1.2** (R18.4) at upstream baseline commit
> `8f8dd43b961b6af9cad94cf4b643adaa14a41d2c`. This is the frozen reference for
> the per-file warning budget: **each modified file must end at or below its
> baseline count below, and the project-wide total must trend down** as the code
> review (task 2) and the DAB v2 port proceed.

## How this was captured

The repo is configured for `npm-groovy-lint` (which embeds **CodeNarc**) via the
existing `.groovylintrc.json` (`"extends": "all"`, indentation = 2 spaces,
LineLength = 160, with a project-specific set of rules disabled).

```bash
# from the repo root (hubitat-flair-vents/)
npx --yes --registry=https://registry.npmjs.org npm-groovy-lint@17.0.5 \
    --no-insight --output json . > glint-baseline.json
```

- Tool: **npm-groovy-lint 17.0.5**, embedding **CodeNarc 3.7.0** (Groovy 3.0.9 superlite).
- Config: the repo's own `.groovylintrc.json` (unchanged).
- Scope: all `*.groovy` files plus `build.gradle` (32 files linted).
- Date: 2026-06-09.

> Note: a public-registry mirror (`registry.npmjs.org`) is specified explicitly
> because the environment's default npm registry is an authenticated CodeArtifact
> proxy that rejects anonymous installs. Task 1.3 will wire an equivalent
> `codenarc` Gradle task so the baseline can be reproduced without npm.

## Project-wide totals

| Severity | Count |
|---|---|
| Errors | **0** |
| Warnings | **200** |
| Info | **2210** |
| **Total findings** | **2410** |
| Files linted | 32 |
| Files with ≥1 finding | 31 |

### Findings by rule (project-wide)

| Rule | Count | Rule | Count |
|---|---|---|---|
| Indentation | 995 | EmptyCatchBlock | 2 |
| TrailingWhitespace | 713 | UnnecessaryToString | 2 |
| UnnecessaryGString | 355 | UnnecessaryObjectReferences | 2 |
| Println | 52 | CouldBeElvis | 2 |
| CatchException | 42 | CouldBeSwitchStatement | 2 |
| ClassEndsWithBlankLine | 28 | ConfusingMethodName | 2 |
| IfStatementBraces | 29 | VariableName | 2 |
| FieldTypeRequired | 31 | ParameterReassignment | 1 |
| UnusedImport | 22 | UnnecessaryCollectCall | 1 |
| LineLength | 16 | UnnecessaryElseStatement | 1 |
| NestedBlockDepth | 15 | ConfusingTernary | 1 |
| JUnitPublicProperty | 15 | SpaceAfterComma | 1 |
| ConsecutiveBlankLines | 8 | ThrowRuntimeException | 1 |
| ClassStartsWithBlankLine | 8 | UnnecessaryIfStatement | 1 |
| GetterMethodCouldBeProperty | 8 | IfStatementCouldBeTernary | 1 |
| SpaceAfterSwitch | 7 | UnusedPrivateField | 1 |
| MethodSize | 5 | SimpleDateFormatMissingLocale | 1 |
| BlockEndsWithBlankLine | 5 | InvertedIfElse | 1 |
| UnnecessarySetter | 5 | Instanceof | 1 |
| ReturnNullFromCatchBlock | 4 | NoWildcardImports | 3 |
| ThrowException | 4 | SpaceAroundOperator | 4 |
| FactoryMethodName | 4 | UnusedVariable | 6 |

## Per-file baseline (warning budget)

Sorted by total findings. `E` = error, `W` = warning, `I` = info.
The per-file budget for R18.4 is the **Warnings** column (errors are already 0).

### Source files (`src/`) — primary review/port targets

| File | Total | E | W | I |
|---|---|---|---|---|
| `src/hubitat-flair-vents-app.groovy` | 440 | 0 | 87 | 353 |
| `src/hubitat-flair-vents-pucks-driver.groovy` | 5 | 0 | 0 | 5 |
| `src/hubitat-flair-vents-driver.groovy` | 2 | 0 | 0 | 2 |
| `src/hubitat-ecobee-smart-participation.groovy` | 0 | 0 | 0 | 0 |

### Build script

| File | Total | E | W | I |
|---|---|---|---|---|
| `build.gradle` | 39 | 0 | 7 | 32 |

### Test files (`tests/`)

| File | Total | E | W | I |
|---|---|---|---|---|
| `tests/authentication-fix-tests.groovy` | 546 | 0 | 14 | 532 |
| `tests/vent-control-functionality-tests.groovy` | 492 | 0 | 12 | 480 |
| `tests/efficiency-export-import-tests.groovy` | 408 | 0 | 23 | 385 |
| `tests/manual-cache-test.groovy` | 134 | 0 | 43 | 91 |
| `tests/instance-based-caching-tests.groovy` | 64 | 0 | 7 | 57 |
| `tests/decimal-precision-tests.groovy` | 27 | 0 | 2 | 25 |
| `tests/decimal-precision-fix-tests.groovy` | 26 | 0 | 3 | 23 |
| `tests/voltage-attribute-tests.groovy` | 21 | 0 | 0 | 21 |
| `tests/vent-operations-tests.groovy` | 20 | 0 | 0 | 20 |
| `tests/efficiency-import-edge-cases-simplified-tests.groovy` | 18 | 0 | 0 | 18 |
| `tests/time-calculations-tests.groovy` | 16 | 0 | 0 | 16 |
| `tests/room-change-rate-tests.groovy` | 15 | 0 | 0 | 15 |
| `tests/thermostat-setpoint-tests.groovy` | 15 | 0 | 0 | 15 |
| `tests/airflow-adjustment-tests.groovy` | 14 | 0 | 0 | 14 |
| `tests/efficiency-import-edge-cases-tests.groovy` | 13 | 0 | 0 | 13 |
| `tests/api-communication-tests.groovy` | 12 | 0 | 1 | 11 |
| `tests/room-setpoint-tests.groovy` | 12 | 0 | 0 | 12 |
| `tests/thermostat-state-tests.groovy` | 12 | 0 | 0 | 12 |
| `tests/vent-opening-calculations-tests.groovy` | 12 | 0 | 0 | 12 |
| `tests/math-calculations-tests.groovy` | 11 | 0 | 0 | 11 |
| `tests/device-driver-tests.groovy` | 9 | 0 | 1 | 8 |
| `tests/simple-framework-test.groovy` | 9 | 0 | 0 | 9 |
| `tests/hubitat-flair-vents-app-tests.groovy` | 5 | 0 | 0 | 5 |
| `tests/temperature-conversion-tests.groovy` | 5 | 0 | 0 | 5 |
| `tests/constants-validation-tests.groovy` | 3 | 0 | 0 | 3 |
| `tests/request-throttling-comprehensive-tests.groovy` | 3 | 0 | 0 | 3 |
| `tests/hubitat-ecobee-smart-participation-tests.groovy` | 2 | 0 | 0 | 2 |

## Notes for downstream tasks

- The dominant findings (`Indentation`, `TrailingWhitespace`, `UnnecessaryGString`)
  are low-risk style issues. Per R18.4 the rule is **no net increase per modified
  file**; opportunistic reduction is encouraged but not required outside the files
  a task touches.
- The primary port target, `src/hubitat-flair-vents-app.groovy` (87 warnings,
  440 total), is the file extracted into the pure `libraries/*.groovy` modules by
  the refactor tasks; its warning count should **decrease** as logic moves into
  clean, purpose-built modules.
- `src/hubitat-ecobee-smart-participation.groovy` is clean (0 findings) and out of
  scope for the DAB v2 port.
