# Deploy & Baseline Runbook — `hubitat-flair-vents`

> Spec: `hubitat-flair-vents-dab-v2` (Code Review + DAB v2 Groovy port).
> This file is created by task **1.2** to record the upstream baseline and the
> layout reconciliation. The ordered **publish + rollback runbook** (R3.3–R3.5)
> is completed later by task **10.3** — see the placeholder at the end.

## 1. Upstream baseline (R2.1, R3.1)

The full current source of the published integration was cloned from GitHub into
the local working copy and committed as the baseline for this spec. Every
subsequent change is diffable against, and revertible to, this baseline.

| Item | Value |
|---|---|
| Canonical repository | `https://github.com/ljbotero/hubitat-flair-vents` |
| Default branch | `main` |
| **Upstream baseline commit SHA** | **`8f8dd43b961b6af9cad94cf4b643adaa14a41d2c`** |
| Baseline commit subject | `Merge from beta` |
| Baseline commit author / date | `ljbotero` / `Sun Aug 17 08:12:18 2025 -0500` |
| Clone date | 2026-06-09 |
| Working-copy path | `hubitat-flair-vents/` (this repo) |

Other refs observed on the remote at clone time (for reference only):

- `refs/heads/Beta` → `9480f7582a65c179ac9dda18501db6fbf2923b3f`
- `refs/pull/5/head` → `bfcc20c700cac2c08cf4dba1d840cb52f10971a9`
- `refs/pull/6/head` → `8a863b1a2aa3da3b9f311f36da682e489b337c72`

### Repository identification rationale

The spec names the integration `hubitat-flair-vents` (author Bartlomiej; the
"Flair smart vents Hubitat integration" with Dynamic Airflow Balancing). The
suggested URL `github.com/bot2600/hubitat_flair_vents` does **not** exist
(`git ls-remote` → *Repository not found*). A GitHub search for
`flair vents hubitat` returns `ljbotero/hubitat-flair-vents`
("Provides discovery and control capabilities for Flair Vent devices") as the
canonical published source; its name, description, DAB feature set, and Hubitat
Package Manager manifest (`packageManifest.json`, `repository.json`) match the
integration described by the spec and the
[Hubitat community thread](https://community.hubitat.com/t/new-control-flair-vents-with-hubitat-free-open-source-app-and-driver/132728).
This repository was used as the baseline.

> The `docs/` folder (the reusable Hubitat-developer skill from task 1.1, this
> runbook, and the quality baseline) is **not** part of the upstream commit; it
> is committed on top of the upstream baseline in this spec's working copy.

## 2. Target module layout vs. actual repo structure (R3.1, R3.2)

The design (`design.md` → *Module layout*) describes an **intended** shape
(`app/`, `drivers/`, `libraries/`, `test/`). The published repo uses a flatter
layout. Per the design note ("If the current repo keeps app and drivers as flat
top-level `.groovy` files, the same module split is preserved logically via
Libraries even if directory nesting differs"), we **keep the existing layout**
and realize the pure-core split logically; the directory deviations below are
expected and accepted.

### Actual top-level structure at baseline

```
hubitat-flair-vents/
  src/
    hubitat-flair-vents-app.groovy            parent app: OAuth, discovery, DAB, API I/O
    hubitat-flair-vents-driver.groovy         vent child driver (SwitchLevel)
    hubitat-flair-vents-pucks-driver.groovy   puck child driver (temp/humidity/motion)
    hubitat-ecobee-smart-participation.groovy  companion app (extra; not in design)
  tests/                                      35 Spock test files (gradle `tests` srcSet)
  docs/                                       (added by this spec — skill, deploy, quality baseline)
  build.gradle                                Gradle + Spock 1.2 + hubitat_ci 0.17 + JaCoCo
  .groovylintrc.json                          npm-groovy-lint / CodeNarc config (extends "all")
  packageManifest.json, repository.json       Hubitat Package Manager metadata
  architecture.md, README.md, TESTING.md, CHANGELOG.md, LICENSE
  hubitat-flair-vents-device.png
```

### Deviation table (design layout → actual)

| Design (intended) | Actual repo | Disposition |
|---|---|---|
| `app/hubitat-flair-vents-app.groovy` | `src/hubitat-flair-vents-app.groovy` | **Deviation (path).** Keep `src/`; app lives there. No move required. |
| `drivers/flair-vents-driver.groovy` | `src/hubitat-flair-vents-driver.groovy` | **Deviation (path + name).** Vent driver is `hubitat-flair-vents-driver.groovy` under `src/`. |
| `drivers/flair-pucks-driver.groovy` | `src/hubitat-flair-vents-pucks-driver.groovy` | **Deviation (path + name).** Puck driver present under `src/`. |
| `libraries/dabv2-*.groovy` (5 pure modules) | **absent** | **Gap to create.** No `libraries/` dir or pure DAB v2 modules yet; all logic is inline in the app. The pure split (`dabv2-allocator/safety-floor/learning/context/model-io`) is introduced by later tasks (2.x refactor + 3–7). On-device they are consumed via Hubitat Library `#include`. |
| `test/groovy` + `test/resources/parity` (Gradle+Spock) | `tests/` (Gradle+Spock already configured) | **Deviation (path) + partial match.** Off-device harness already exists (`build.gradle` maps `tests` as the test srcSet, Spock 1.2, hubitat_ci 0.17). Parity resources (`test/resources/parity`) do not exist yet (task 8). Task 1.3 builds on the existing `tests/` dir rather than a new `test/` dir. |
| `docs/skill/...`, `docs/deploy.md` | `docs/skill/hubitat-developer-skill.md` (task 1.1), this file | **Match.** Added by this spec. |

### Other notable observations (not layout; flagged for the code review, task 2)

- **Extra companion app** `src/hubitat-ecobee-smart-participation.groovy` (+ its
  test) exists in the repo but is **not** mentioned in the design. Treated as
  out-of-scope for the DAB v2 port; flagged here so it is not silently ignored.
- **Existing build tooling already satisfies much of R18.1/R18.2:** the repo
  ships a Gradle + Spock JVM harness and 35 test files. Task 1.3 should extend
  (not replace) this harness and add the `codenarc`/property-test wiring.
- **Safety-floor default mismatch (behavioral, for task 2/5):** `architecture.md`
  documents a **30 %** minimum airflow and conventional vents counted at **50 %**,
  whereas the spec mandates a **40 %** default floor and conventional vents at
  their configured open value (default 100 %). This is a behavioral gap for the
  Safety_Floor work, recorded here for traceability.

## 3. Quality baseline

See [`quality-baseline.md`](./quality-baseline.md) for the per-file CodeNarc
(npm-groovy-lint) warning/info counts captured at the baseline commit. Per
R18.4, each modified file must end at or below its baseline warning count and
the project-wide count must trend down.

---

## 4. Publish + rollback runbook — _to be completed by task 10.3 (R3.3–R3.5)_

> Placeholder. Task 10.3 will document here:
> - the ordered steps to publish updated app/driver code to a Hubitat hub and/or
>   the source repository;
> - the ordered rollback procedure restoring the previously published version
>   (baseline `8f8dd43b961b6af9cad94cf4b643adaa14a41d2c`);
> - the invariant that no deploy step drives combined open % below the
>   Safety_Floor, and that a failed step / post-deploy floor breach triggers
>   rollback (R3.4, R3.5);
> - the legacy-strategy removal plan (R20.5).
