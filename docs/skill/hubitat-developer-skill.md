# Hubitat Developer Skill

> A reusable, source-grounded reference for developing **Hubitat Elevation** apps and
> device drivers in sandboxed Groovy. Produced for the `hubitat-flair-vents` review +
> DAB v2 port (spec `hubitat-flair-vents-dab-v2`, Requirement 1), and intended to be
> loaded for this and future Hubitat work.

## How to use this document

This skill captures the platform conventions and constraints that govern a Hubitat
app/driver. Every factual claim cites the specific official documentation page it is
grounded in. Where the official documentation is **silent or ambiguous** on a behavior
this port depends on (exact persisted-state byte ceiling, exact per-execution time
budget, exact per-app scheduled-job cap), the claim is **not** asserted as fact — it is
recorded in [Working assumptions](#working-assumptions-undocumented-ceilings) as an
explicit, conservative assumption (Requirement 1.5).

Conventions in this document:
- "official docs" = the Hubitat developer documentation rooted at
  <https://docs2.hubitat.com/developer>.
- Code reuse on-device is via Hubitat **Libraries** (`#include`); there are **no**
  external JAR / Maven / `@Grab` imports inside the sandbox (see
  [Sandboxed Groovy execution model](#2-sandboxed-groovy-execution-model)).
- Internal control math should be in a single unit system (this project uses Celsius)
  and converted only at the device boundary; the platform provides
  `fahrenheitToCelsius` / `celsiusToFahrenheit` / `getTemperatureScale` for that
  boundary ([Common Methods][common]).

## Primary sources

These are the authoritative pages this skill is grounded in. Topic sections below cite
the most specific applicable page.

- App Overview — <https://docs2.hubitat.com/developer/app/overview> ([app-overview][appov])
- Building a Simple App — <https://docs2.hubitat.com/developer/app/building-a-simple-app> ([simple-app][simple])
- App Preferences — <https://docs2.hubitat.com/developer/app/preferences> ([prefs][prefs])
- Driver Overview — <https://docs2.hubitat.com/developer/driver/overview> ([driver-overview][drvov])
- Common Methods Object — <https://docs2.hubitat.com/developer/common-methods-object> ([common][common])
- Best Practices — <https://docs2.hubitat.com/developer/best-practices> ([best-practices][best])
- App Object (subscribe/unsubscribe, runtime objects) — <https://docs2.hubitat.com/developer/app/app-object> ([app-object][appobj])
- Community: `singleThreaded` option for apps/drivers — <https://community.hubitat.com/t/2-2-9-singlethreaded-option-for-apps-drivers/80969>
- Community: scheduling periodic execution — <https://community.hubitat.com/t/scheduling-periodic-execution-with-specified-first-execution-time/39800>

> Community-forum links are secondary sources used only where the official docs are
> silent; claims grounded on them are flagged inline and, where they concern a hard
> ceiling, are carried into [Working assumptions](#working-assumptions-undocumented-ceilings).

---

## 1. App and driver lifecycle

**Apps are event-driven and not always running.** An installed app instance sleeps and
**wakes in response to specific triggers**, then runs the relevant handler and goes back
to sleep. Per the official App Overview, an app wakes for ([app-overview][appov]):

- device or hub/location **events** the app subscribed to via `subscribe()` (runs the
  named callback) — the most common reason an automation app wakes;
- **schedules** the app created via `runIn()` / `schedule()` / `runEvery*` (runs the
  named callback);
- **rendering the UI** when the user opens the app (executes the code inside
  `preferences`);
- **install / update / uninstall** (runs `installed()`, `updated()`, `uninstalled()`);
- an **HTTP endpoint** hit (OAuth + `mappings`).

**Required app callbacks** ([app-overview][appov]):

| Method | When it runs |
| --- | --- |
| `installed()` | once, when the app instance is first installed |
| `updated()` | every time the user presses **Done** in the app UI |
| `uninstalled()` | when the app is uninstalled (subscriptions/schedules are removed automatically; only needed to notify external systems) |

**Lifecycle idioms** demonstrated in the official Simple App guide ([simple-app][simple]):

- `installed()` typically calls `updated()` so first-install setup happens once
  (because `installed()`, not `updated()`, runs the first time the user selects Done
  unless `installOnOpen: true`).
- `updated()` should call `unsubscribe()` then re-`subscribe(...)` (and usually
  `unschedule(...)` before re-scheduling) so re-saving config does not leave **stale**
  subscriptions/schedules from a previous device selection.

**Drivers** have an analogous lifecycle, but `definition` and `preferences` live inside a
`metadata { ... }` block, and there is no `page`/`section` ([driver-overview][drvov]).
Driver methods called by the platform ([driver-overview][drvov]):

| Method | When it runs |
| --- | --- |
| `installed()` | when the device is first added |
| `updated()` | when the user selects **Save Preferences** |
| `initialize()` | on hub startup **iff** the driver declares `capability "Initialize"` |
| `parse(String desc)` | handles raw inbound Zigbee/Z-Wave/LAN data (typically emits events) |

A driver must implement a Groovy method for **every command** required by each declared
`capability` (e.g. `capability "Switch"` requires `on()` and `off()`)
([driver-overview][drvov]).

> **Relevance to this port.** The app must (re)establish subscriptions and its
> self-rescheduling evaluate job in `updated()` after `unsubscribe()`/`unschedule()`, and
> must not assume any code runs "continuously" — all periodic work is driven by the
> scheduler waking the app (see [Scheduling](#5-scheduling-runin-schedule-runevery)).

---

## 2. Sandboxed Groovy execution model

App and driver code runs in a **restricted Groovy sandbox**, not a general JVM. The
practical consequences relevant to this port:

- **No external libraries / arbitrary imports.** There is no `@Grab`, no Maven/JAR
  dependency mechanism, and no general external `import`. Code reuse on-device is via
  Hubitat **Libraries** included with `#include` directives, not external jars
  (grounded in the project design's reading of [Best Practices][best]; the official docs
  present Libraries as the on-device reuse mechanism and present no JAR/`@Grab` path).
- **Sandbox-safe alternatives are provided for ordinarily-reflective calls.** For
  example, the platform exposes `getObjectClassName(Object obj)` explicitly *"as an
  alternative to `<object>.getClass()` that is allowed in the Hubitat sandbox"*
  ([common][common]) — direct reflection is the kind of thing the sandbox restricts.
- **Time and platform services must come through provided helpers**, e.g. `now()`,
  `getSunriseAndSunset()`, `timeToday()`, `getLocation()` ([common][common]); the pure
  math modules in this port therefore take time/clock values **as parameters** rather
  than calling `java.time` directly, so the same source compiles under the off-device
  JVM harness and inside the sandbox.
- **Concurrency control is declarative.** A `definition` may set `singleThreaded: true`
  to prevent more than one overlapping wake of the same app/driver instance from
  executing simultaneously; the hub loads state, runs the method, and saves state before
  the next queued call ([driver-overview][drvov]; community thread
  <https://community.hubitat.com/t/2-2-9-singlethreaded-option-for-apps-drivers/80969>).

> **Relevance to this port.** The DAB v2 math (`Allocator`, `Safety_Floor`,
> `Learning_Model`, `Context_Mapper`, model I/O) must use **only** language features
> available both in the sandbox and on a plain JVM — no external imports, no forbidden
> reflection, no direct `java.time` — so the *identical* `libraries/*.groovy` source runs
> under Spock off-device and inside the app on-device.

---

## 3. `preferences` and `input` configuration

**Apps:** the `preferences` block defines the UI and is composed of one or more `page`
blocks, each containing one or more `section` blocks; inputs/paragraphs live inside a
`section` ([app-overview][appov], [prefs][prefs]). For single-page apps, an explicit
`page` is optional but a `section` is still required ([simple-app][simple]).

- A `page` can declare `install: true` (show the install button) and
  `uninstall: true` (show the Remove button) ([app-overview][appov]).
- Multi-page apps set `nextPage` to the `name` of the next page ([app-overview][appov]).

**Drivers:** `preferences` lives inside `metadata` and has **no** `page`/`section` — just
inputs ([driver-overview][drvov]).

**The `input()` method** (apps and drivers) — general form ([app-overview][appov],
[driver-overview][drvov]):

```groovy
input(name: "elementName", type: "elementType", title: "Element Title" /*, options */)
// short form (labels omitted, parentheses optional):
input "myName", "myType", title: "My Input"
```

**Common input types** ([driver-overview][drvov]):

| `type` | Groovy value |
| --- | --- |
| `text` | String |
| `number` | Integer |
| `decimal` | Double |
| `enum` | a selection (requires `options:` List; a List of Maps shows the value but returns the key); saved as a String |
| `bool` | Boolean (on/off slider) |
| `capability.<name>` | device selector (e.g. `capability.switch`, `capability.temperatureMeasurement`); add `multiple: true` for multi-select ([simple-app][simple]) |

**Settings semantics** ([app-overview][appov], [driver-overview][drvov]):

- Every `input` value is stored in a built-in `settings` Map keyed by the input `name`.
- Read via `settings.myName`, `settings["myName"]`, or directly as `myName` (the name is
  in scope across the app/driver).
- `defaultValue:` displays on load but is **not saved** to the setting until the page is
  saved; `required: true` blocks saving until the input is provided.
- Avoid naming an input `hubitatQueryString` (a reserved hub-provided setting)
  ([app-overview][appov]).

> **Relevance to this port.** Topology, the `balance` strategy selector, the safety-floor
> percentage, guardrail/deadband, cross-coupling toggle, conventional-vent count/open %,
> and optional outdoor/door/occupancy sources are all `input`s. Note the **Groovy-truth
> pitfall**: a numeric input of `0` and an absent (`null`) input both evaluate falsey in
> `if (someInput)` ([simple-app][simple]); validation must distinguish "0" from
> "unset" explicitly (relevant to floor/threshold validation in R4/R6).

---

## 4. State persistence: `state` and `atomicState`

Each installed app instance (and each device, for drivers) has a built-in **`state`**
object that behaves like a `Map` and persists **between executions**. It can hold
anything serializable to/from JSON — strings, numbers, and Lists/Maps of those
([app-overview][appov], [driver-overview][drvov]).

**`state` vs `atomicState`** ([app-overview][appov], [driver-overview][drvov]):

| | `state` | `atomicState` |
| --- | --- | --- |
| When written | serialized to JSON **just before the app sleeps** | **commits immediately** as each change is made |
| Efficiency | more efficient (preferred default) | less efficient |
| Use when | normal single-execution data | concurrent/overlapping executions read-modify-write shared state |

- Both names refer to the **same underlying data** and may be mixed, though most
  developers pick one ([app-overview][appov]).
- `atomicState.updateMapValue(stateKey, key, value)` reads a Map from `atomicState`,
  updates one entry, and writes it back ([app-overview][appov]).
- An alternative to `atomicState` for avoiding overlap is `singleThreaded: true` in
  `definition` ([app-overview][appov], [driver-overview][drvov]).

### JSON-serialization size constraint (documented direction, undocumented ceiling)

The official Best Practices page states `state` *"is convenient and works well for small
amounts of data"* but that **the data is serialized and deserialized to/from JSON on or
after (for `atomicState`, during) execution**, and that **large amounts of data may be
best stored more efficiently** by other means ([best-practices][best]). Suggested
alternatives for large data ([best-practices][best]):

- **File Manager** files (`uploadHubFile` / `downloadHubFile` / `deleteHubFile`,
  since 2.3.4.132 — [common][common]);
- a top-level `static @groovy.transform.Field` variable (note: **shared across all
  instances** and **not persisted across hub reboot or code re-save** — usable as a
  cache, not durable storage) ([best-practices][best]).

The docs describe a **direction** (keep `state` small; serialization cost grows with
size) but **do not publish a hard byte limit**. The exact ceiling is therefore an
explicit working assumption — see
[Working assumptions](#working-assumptions-undocumented-ceilings).

**Attributes vs `state`** ([best-practices][best]): values a user may want to automate on
(or view as "Current States") belong in **attributes/events** (`sendEvent`), not `state`;
`state` is for internal data that must survive between executions but is not an
automation trigger.

> **Relevance to this port.** The learned model (per-room dual efficiency, per-vent
> aperture→airflow curves, regimes) must be **compactly encoded and bounded** before it
> goes in `state`: short keys, rounded numbers, prunable LRU entries, and a documented
> ordered shrink strategy — never an unbounded structure. Re-entrancy-sensitive flags
> (evaluation single-flight guard, cycle id) use `atomicState` because they are
> read/written *during* execution.

---

## 5. Scheduling: `runIn`, `schedule`, `runEvery*`

All scheduling APIs are in the Common Methods object ([common][common]).

**One-shot delays:**

- `runIn(Long delayInSeconds, String handlerMethod, Map options = null)` — runs
  `handlerMethod` after roughly `delayInSeconds`. *"don't expect that it will be called
  in exactly that time."* Options ([common][common]):
  - `overwrite` (**default `true`**): cancels the previously scheduled run of the same
    handler and schedules anew; `false` creates a **duplicate** schedule.
  - `data`: optional Map passed to the handler.
  - `misfire: "ignore"`: fire as soon as possible if missed (can cause rapid catch-up
    firings).
  - Since 2.4.2 returns a job-id String usable with `cancelRunIn(String jobId)`.
- `runInMillis(Long delayInMilliseconds, handlerMethod, options)` — same semantics at ms
  granularity ([common][common]).
- `runOnce(Date|String dateTime, handlerMethod, options)` — run once at an absolute time
  ([common][common]).

**Recurring (fixed helpers)** — handler-name reschedule semantics, divide-evenly cadences
([common][common]):

- `runEvery1Minute`, `runEvery5Minutes`, `runEvery10Minutes`, `runEvery15Minutes`,
  `runEvery30Minutes`, `runEvery1Hour`, `runEvery3Hours`.

**Cron:** `schedule(String expression, String handlerMethod, Map options = null)` where
`expression` is a **7-field Quartz cron** (`Seconds Minutes Hours Day-of-Month Month
Day-of-Week Year`) ([common][common]). Example from the docs: `schedule("0 */10 * ? * *",
"mymethod")` runs every tenth minute ([common][common]). Periodic cron intervals should
divide evenly into the hour (community guidance:
<https://community.hubitat.com/t/scheduling-periodic-execution-with-specified-first-execution-time/39800>).

**Cancellation:** `unschedule()` removes all scheduled tasks; `unschedule(handlerMethod)`
removes only those for a specific handler ([common][common]). Being specific is
recommended ([simple-app][simple]).

**Key reschedule semantic:** because `overwrite` defaults to `true`, calling `runIn` again
with the **same handler name** replaces the prior pending job rather than stacking a
second one ([common][common]) — the basis for a single self-rescheduling evaluate job.

### Scheduler granularity, job cap, and execution-time budget

- **Granularity:** sub-minute recurring cadence is not offered by the fixed `runEvery*`
  helpers (smallest is `runEvery1Minute`); finer one-shot timing uses `runIn`/
  `runInMillis`, but the docs explicitly warn timing is **approximate**, not exact
  ([common][common]).
- **Per-app scheduled-job cap** and **per-execution wall-clock budget** are **not
  authoritatively published** in the developer docs — see
  [Working assumptions](#working-assumptions-undocumented-ceilings).

> **Relevance to this port.** Adaptive cadence (active ~3 min / idle ~10 min) is built
> from the standard helpers plus a **single self-rescheduling evaluate job** (one
> `runIn`/`runEvery*` per app, replaced on each transition via the `overwrite` default) —
> never a proliferation of timers — and all heavy I/O is async so an evaluate stays well
> under any plausible execution budget.

---

## 6. Event subscription

An app listens for device/hub/location changes by creating a subscription, typically in
`updated()` (and `installed()` via the `installed()`→`updated()` idiom)
([simple-app][simple], [app-object][appobj]):

```groovy
subscribe(motionSensor, "motion", "motionHandler")   // device, attribute, handler-name
// ...
def motionHandler(evt) {
    log.debug "${evt.name} = ${evt.value}"            // event object: name, value, etc.
}
```

- The handler name is a **String**; the method must exist in the app and receives a
  single **event object** exposing properties such as `name` and `value`
  ([simple-app][simple]).
- Changing an attribute value (usually) generates an **event**, and only events drive
  subscriptions — this is why automatable values belong in attributes, not `state`
  ([best-practices][best]).
- **Hygiene:** call `unsubscribe()` (all) or `unsubscribe(device)` before re-subscribing
  in `updated()` so a changed device selection does not leave a stale subscription
  ([simple-app][simple]).
- Subscriptions, schedules, and the `settings` map are all visible on the **App Status
  page**, which is the primary live-debugging surface ([simple-app][simple]).

> **Relevance to this port.** The app subscribes to thermostat operating-state (and
> optional outdoor/occupancy/door) events to flip the evaluation cadence and to anchor a
> new cycle on mode changes; subscriptions are rebuilt cleanly on every `updated()`.

---

## 7. Asynchronous and synchronous HTTP

For all outbound HTTP (e.g. the Flair REST API), prefer the **async** family so the app
yields the execution thread and receives the response in a callback
([common][common]):

- `asynchttpGet(callbackMethod, Map params, Map data = null)`
- `asynchttpPost(callbackMethod, Map params, Map data = null)`
- also `asynchttpPut`, `asynchttpPatch`, `asynchttpDelete`, `asynchttpHead`
  ([common][common]).

Each async call returns control immediately; the named **callback** receives the
response, plus the optional `data` Map you passed through ([common][common]). Synchronous
equivalents exist (`httpGet`, `httpPost`, `httpPostJson`, `httpPut`, `httpPatch`, …) and
block until the response returns ([common][common]).

**`params` map** (both families) supports `uri`, `path`, `query`/`queryString`,
`headers`, `contentType`, `requestContentType`, `body` (Map/Array auto-encoded for
POST/PUT/PATCH), and ([common][common]):

- **`timeout`** — seconds, **maximum 300** (since 2.0.9);
- `ignoreSSLIssues` (since 2.1.8), `followRedirects` (since 2.2.9, default `true`);
- `textParser` (sync GET/POST, since 2.1.1) to force plain-text parsing.

**Async + scheduler overlap (concurrency hazard).** `runIn`/`schedule` callbacks can
interleave with in-flight `asynchttp*` callbacks, so the evaluate→allocate→dispatch path
must be explicitly guarded against re-entrancy and against an old cycle's delayed callback
clobbering a newer cycle (use the `atomicState` single-flight guard and a cycle-identity
check; `singleThreaded: true` is the declarative alternative — §2,
[driver-overview][drvov]).

> **Relevance to this port.** All Flair I/O is **async** so each app execution is short
> (§5 budget). Vent dispatch must treat a **commanded-but-unconfirmed** close as *not yet
> reduced airflow* when computing the next allocation, and must **fail toward more-open**
> on error/timeout so actual combined airflow never drops below the safety floor.

---

## 8. Logging

Apps and drivers share a built-in `log` object writing to **Logs**
([app-overview][appov], [driver-overview][drvov]):

- `log.info`, `log.debug`, `log.trace`, `log.warn`, `log.error` — each tags the entry
  with its level ([app-overview][appov]).
- Use GString interpolation: `log.debug "state.foo = ${state.foo}"` ([app-overview][appov]).

**Conventions** ([best-practices][best], [simple-app][simple]):

- Logging itself rarely affects hub performance, but **users expect to control it** — gate
  verbose `debug`/`trace` behind a `logEnable` bool input.
- App convention: keep logging enabled until the user disables it (unlike some drivers
  that auto-disable debug after a delay).
- Always log lifecycle one-offs (`installed()` / `uninstalled()`) regardless of the
  toggle, since they happen at most once.

> **Relevance to this port.** Never log credentials/tokens. Coalesce repeated error
> notifications. Log the **reason** whenever the safety floor must reposition an inactive
> room as a last resort (R6.10).

---

## Working assumptions (undocumented ceilings)

Per Requirement 1.5, the following are behaviors this port depends on that the official
Hubitat developer documentation **does not authoritatively quantify**. Each is recorded
as an explicit, conservative **working assumption** — not asserted as a documented fact —
and the design budgets against the assumption rather than relying on an undocumented hard
limit.

| # | Topic | What the docs DO say (cited) | What is NOT documented | Working assumption (conservative) | Design mitigation |
| --- | --- | --- | --- | --- | --- |
| WA-1 | **Persisted-state size (bytes)** | `state` "works well for small amounts of data"; it is JSON serialized/deserialized per execution; large data should move to File Manager / `@Field` ([best-practices][best]) | No published maximum byte size for `state`/`atomicState` | Treat the practical safe budget as **small — target well under ~100 KB serialized**, and assume cost grows with size every execution | Compact schema v2 (short keys, 4-sig-digit rounding, droppable shared breakpoints), O(rooms+vents) growth, ordered LRU-prune shrink strategy; never store unbounded history in `state` |
| WA-2 | **Per-execution time budget** | Timing of scheduled handlers is approximate; sync work blocks ([common][common]); async HTTP exists to avoid blocking ([common][common]) | No published hard wall-clock limit per app/driver execution | Assume executions are **watchdog-bounded to a few tens of seconds**; long synchronous work is unsafe | Allocation is O(rooms) pure arithmetic; **all** Flair I/O is async (`asynchttp*`); no `pauseExecution` busy-waits in the hot path |
| WA-3 | **Per-app scheduled-job cap** | `runIn` `overwrite` defaults to `true` (same-handler reschedule replaces prior job); `overwrite:false` creates duplicates ([common][common]) | No published maximum number of concurrently scheduled jobs per app | Assume a **small practical cap**; do not rely on many simultaneous timers | Exactly **one** self-rescheduling evaluate job per app (rely on `overwrite` default); `unschedule` specific handlers before re-scheduling |
| WA-4 | **HTTP timeout interaction with execution budget** | `timeout` max is **300 s** ([common][common]) | Whether a 300 s sync timeout can exceed the (undocumented) execution budget | Assume a long **sync** timeout could exceed the execution budget and be killed | Use **async** HTTP with a **short** per-request `timeout` (seconds), and fail-toward-more-open on timeout |
| WA-5 | **Exact sandbox allow/deny list** | Sandbox provides safe alternatives (e.g. `getObjectClassName` vs `getClass()`) ([common][common]); reuse via Libraries, not external jars ([best-practices][best]) | No single authoritative, exhaustive list of allowed/blocked classes & methods | Assume **no** external imports, **no** general reflection, **no** direct `java.time`; pass time/random in as parameters | Pure modules are sandbox-safe by construction and compiled verbatim by the off-device JVM harness (same source both places) |

> If any of these is later contradicted by an authoritative Hubitat source, update the
> affected row here and re-evaluate the corresponding design mitigation; do not silently
> treat the assumption as proven.

---

## Quick reference for the DAB v2 port

- **Pure-core isolation:** keep `Allocator` / `Safety_Floor` / `Learning_Model` /
  `Context_Mapper` / model-I/O in `libraries/*.groovy` with **no** platform APIs, time,
  randomness, or `state` — so the same source runs under Spock (off-device) and in the
  sandbox (on-device) (§2, §4).
- **State discipline:** compact + bounded model in `state`; `atomicState` only for the
  re-entrancy guard and cycle id (§4, WA-1).
- **Scheduling discipline:** one self-rescheduling evaluate job; `overwrite` default does
  the replacement; clamp cadence to `runEvery*`-friendly intervals (§5, WA-3).
- **I/O discipline:** async HTTP only, short timeouts, fail-toward-more-open, treat
  unconfirmed closes as not-yet-reduced airflow (§7, WA-2/WA-4).
- **Boundary conversions:** Celsius internally; convert with
  `fahrenheitToCelsius`/`celsiusToFahrenheit`/`getTemperatureScale` at the edges (§2,
  [common][common]).

---

## Source index

[appov]: https://docs2.hubitat.com/developer/app/overview "App Overview (for Developers)"
[simple]: https://docs2.hubitat.com/developer/app/building-a-simple-app "Building a Simple App"
[prefs]: https://docs2.hubitat.com/developer/app/preferences "App Preferences"
[drvov]: https://docs2.hubitat.com/developer/driver/overview "Driver Overview (for Developers)"
[common]: https://docs2.hubitat.com/developer/common-methods-object "Common Methods Object"
[best]: https://docs2.hubitat.com/developer/best-practices "Best Practices (for Developers)"
[appobj]: https://docs2.hubitat.com/developer/app/app-object "App Object"

- App Overview — <https://docs2.hubitat.com/developer/app/overview>
- Building a Simple App — <https://docs2.hubitat.com/developer/app/building-a-simple-app>
- App Preferences — <https://docs2.hubitat.com/developer/app/preferences>
- Driver Overview — <https://docs2.hubitat.com/developer/driver/overview>
- Common Methods Object — <https://docs2.hubitat.com/developer/common-methods-object>
- Best Practices — <https://docs2.hubitat.com/developer/best-practices>
- App Object — <https://docs2.hubitat.com/developer/app/app-object>
- Community — `singleThreaded` for apps/drivers — <https://community.hubitat.com/t/2-2-9-singlethreaded-option-for-apps-drivers/80969>
- Community — scheduling periodic execution — <https://community.hubitat.com/t/scheduling-periodic-execution-with-specified-first-execution-time/39800>

> Content from the official Hubitat documentation was rephrased and summarized for
> compliance with licensing restrictions; see the linked source pages for the
> authoritative text.
