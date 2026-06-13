# gen_parity_fixtures — Reference parity fixture generator (R17.4, D9)

Regenerates the `*.expected.json` parity fixtures from the **validated Python
Reference** (`hvac_vent_optimizer`) so the Groovy DAB v2 allocator can be
checked against it (the parity evidence gate, decision D9). Both sides consume
the *same* `*.scenario.json`; this tool runs the Reference allocator over those
scenarios and writes the matching expected outputs, each stamped with the
Reference git SHA it was generated from.

## What it drives

It loads the Reference's **HA-free** pure modules directly by file path:

- `hvac_vent_optimizer/learning.py` — the learned `VentCurve`
- `hvac_vent_optimizer/balance.py` — `allocate`, `apply_safety_floor`,
  `combined_open_pct`

These modules import nothing from Home Assistant at runtime, so the HA-dependent
package `__init__.py` is never imported and **no Home Assistant install is
required**. For each scenario the tool calls `allocate(...)` then
`apply_safety_floor(...)` and serializes the commanded apertures,
`airflowLimited`, `floorBinding`, `predictedSpreadC`, and `combinedOpenPct`.

## Regenerate command

Run from the `hubitat-flair-vents` working copy, pointing `--reference` at your
local `hvac_vent_optimizer` checkout:

```bash
python tools/gen_parity_fixtures \
    --reference  ../hvac_vent_optimizer \
    --scenarios  tests/resources/parity \
    --out        tests/resources/parity
```

Defaults: `--reference ../hvac_vent_optimizer`, `--scenarios`/`--out`
`tests/resources/parity`, so the short form usually suffices:

```bash
python tools/gen_parity_fixtures
```

Options:

| Option | Default | Meaning |
|---|---|---|
| `--reference DIR` | `../hvac_vent_optimizer` | The Reference working copy. |
| `--scenarios DIR` | `tests/resources/parity` | Directory of `*.scenario.json` inputs. |
| `--out DIR` | same as `--scenarios` | Where `*.expected.json` are written. |
| `--granularity N` | (per-scenario) | Override every scenario's aperture granularity. |
| `--tolerance N` | `1` | Aperture-point tolerance stamped into each expected file. |

> The design also documents the `python -m tools.gen_parity_fixtures` form; it
> works when the repo root is on `PYTHONPATH`. The `python tools/gen_parity_fixtures`
> form above is the canonical, install-free invocation.

## Reference SHA stamping

Every `*.expected.json` carries `"referenceCommit": "<sha>"`, captured via
`git -C <reference> rev-parse HEAD`. This makes the parity evidence reproducible
and traceable: regenerating from a different Reference revision updates the
stamped SHA, and a fixture's provenance is always visible. If the Reference
directory is not a git checkout the stamp falls back to `"unknown"`.

## Portable fixture format

See `tests/support/ParityFixtures.groovy` (the Groovy loader) for the full
`*.scenario.json` / `*.expected.json` schema. The format is the serialized
allocation input plus settings — nothing platform-specific — so the identical
scenario feeds both the Python Reference and the Groovy allocator.
