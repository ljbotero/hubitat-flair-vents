"""Regenerate the parity ``*.expected.json`` fixtures from the Python Reference.

Parity evidence gate (decision D9, R17.4): the Groovy DAB v2 allocator is
validated by matching the *validated* Python Reference (`hvac_vent_optimizer`)
allocator on a shared, platform-neutral scenario set. Both sides consume the
SAME ``*.scenario.json``; this script drives the Reference's HA-free
``balance`` / ``learning`` modules over those scenarios and writes the matching
``*.expected.json`` files, each stamped with the Reference git SHA it was
generated from.

The Reference modules (``balance.py`` / ``learning.py``) import nothing from
Home Assistant at runtime, so they are loaded DIRECTLY by file path via
``importlib`` — the HA-dependent package ``__init__.py`` is never imported and
no Home Assistant install is required.

Usage (canonical — run from the hubitat-flair-vents working copy)::

    python tools/gen_parity_fixtures \
        --reference  ../hvac_vent_optimizer \
        --scenarios  tests/resources/parity \
        --out        tests/resources/parity

Options:

    --reference DIR   Path to the hvac_vent_optimizer working copy (the
                      Reference). Default: ``../hvac_vent_optimizer`` relative
                      to this repo root.
    --scenarios DIR   Directory of ``*.scenario.json`` inputs.
                      Default: ``tests/resources/parity``.
    --out DIR         Directory to write ``*.expected.json`` outputs.
                      Default: same as ``--scenarios``.
    --granularity N   Optional override of every scenario's aperture granularity
                      (the design's documented ``--granularity 5``). When
                      omitted, each scenario's own ``settings.granularity`` is
                      used.
    --tolerance N     Aperture-point tolerance stamped into each expected file
                      (default 1, R17.2).

Exit status is non-zero if any scenario fails to process.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import subprocess
import sys
from pathlib import Path
from types import ModuleType

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REFERENCE = REPO_ROOT.parent / "hvac_vent_optimizer"
DEFAULT_PARITY_DIR = REPO_ROOT / "tests" / "resources" / "parity"

SCENARIO_SUFFIX = ".scenario.json"
EXPECTED_SUFFIX = ".expected.json"

MODE_COOLING = "cooling"


def _load_module(name: str, path: Path) -> ModuleType:
    """Load a single ``.py`` file as a standalone module (HA-free).

    The module is registered in ``sys.modules`` BEFORE execution so dataclass
    field-type resolution (which looks the owning module up by name) succeeds.
    """
    spec = importlib.util.spec_from_file_location(name, str(path))
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {name} from {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _reference_sha(reference_dir: Path) -> str:
    """Return the Reference working copy's current git SHA (or 'unknown')."""
    try:
        out = subprocess.run(
            ["git", "-C", str(reference_dir), "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        )
        return out.stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unknown"


def _signed_error(mode: str, setpoint_c: float, temp_c: float) -> float:
    """Signed error toward setpoint (>0 ⇒ still needs conditioning)."""
    if mode == MODE_COOLING:
        return temp_c - setpoint_c
    return setpoint_c - temp_c


def _build_settings(balance: ModuleType, raw: dict, granularity_override: int | None):
    """Map a portable (camelCase) settings block onto the Reference AllocSettings."""
    raw = raw or {}
    granularity = granularity_override if granularity_override is not None else int(raw.get("granularity", 5))
    return balance.AllocSettings(
        safety_floor_pct=float(raw.get("safetyFloorPct", 40.0)),
        conventional_vents=int(raw.get("conventionalVents", 0)),
        conventional_open_pct=float(raw.get("conventionalOpenPct", 50.0)),
        inactive_open_pct_sum=float(raw.get("inactiveOpenPctSum", 0.0)),
        inactive_count=int(raw.get("inactiveCount", 0)),
        granularity=granularity,
        crosscoupling=bool(raw.get("crosscoupling", True)),
        hysteresis_c=float(raw.get("hysteresisC", 0.3)),
        airflow_limited_margin_pct=float(raw.get("airflowLimitedMarginPct", 5.0)),
        airflow_limited_error_c=float(raw.get("airflowLimitedErrorC", 0.5)),
        horizon_min=float(raw.get("horizonMin", 30.0)),
        spread_guardrail_c=float(raw.get("spreadGuardrailC", 1.0)),
        spread_improvement_deadband_c=float(raw.get("spreadImprovementDeadbandC", 0.3)),
    )


def _build_room(balance: ModuleType, learning: ModuleType, raw: dict, mode: str, setpoint_c: float):
    """Map a portable room block onto the Reference RoomAllocInput."""
    temp_c = float(raw["tempC"])
    vent_ids = tuple(str(v) for v in (raw.get("ventIds") or []))
    if "signedErrorC" in raw and raw["signedErrorC"] is not None:
        signed = float(raw["signedErrorC"])
    else:
        signed = _signed_error(mode, setpoint_c, temp_c)
    curve = None
    if isinstance(raw.get("curve"), dict):
        curve = learning.VentCurve.from_dict(raw["curve"])
    return balance.RoomAllocInput(
        room_id=str(raw["roomId"]),
        temp_c=temp_c,
        active=bool(raw.get("active", False)),
        efficiency=float(raw.get("efficiency", 0.0)),
        leak=float(raw.get("leak", 0.0)),
        current_open=float(raw.get("currentOpen", 0.0)),
        vent_ids=vent_ids,
        signed_error_c=signed,
        curve=curve,
    )


def _build_duct(balance: ModuleType, raw: object):
    """Map an optional portable duct block onto the Reference DuctSignals."""
    if not isinstance(raw, dict):
        return None
    return balance.DuctSignals(
        duct_temp_c=(float(raw["ductTempC"]) if raw.get("ductTempC") is not None else None),
        duct_pressure_pa=(
            float(raw["ductPressurePa"])
            if raw.get("ductPressurePa") is not None
            else (float(raw["ductPressure"]) if raw.get("ductPressure") is not None else None)
        ),
    )


def _expand_per_vent(targets: dict[str, float], rooms: list) -> dict[str, float]:
    """Expand per-room targets into one entry per physical vent (R15.4).

    Mirrors the floor's internal expansion so the stamped ``combinedOpenPct``
    matches what the Groovy side computes. Active rooms always contribute their
    vents; an inactive room contributes only while held open (pct > 0, R6.7).
    """
    by_id = {r.room_id: r for r in rooms}
    per_vent: dict[str, float] = {}
    for room_id, pct in targets.items():
        room = by_id.get(room_id)
        if room is None:
            per_vent[room_id] = pct
            continue
        if not room.active and pct <= 0.0:
            continue
        vent_ids = room.vent_ids or (room_id,)
        for i, vid in enumerate(vent_ids):
            per_vent[f"{vid}#{i}"] = pct
    return per_vent


def _process(balance: ModuleType, learning: ModuleType, scenario: dict, granularity_override: int | None) -> dict:
    """Run the Reference allocate + safety floor and assemble the expected output."""
    mode = str(scenario.get("mode", "cooling"))
    setpoint_c = float(scenario.get("setpointC", 0.0))
    settings = _build_settings(balance, scenario.get("settings") or {}, granularity_override)
    rooms = [_build_room(balance, learning, r, mode, setpoint_c) for r in (scenario.get("rooms") or [])]
    duct = _build_duct(balance, scenario.get("duct"))

    result = balance.allocate(rooms, setpoint_c, mode, settings, duct=duct)
    floored, floor_binding = balance.apply_safety_floor(result.targets, rooms, settings)
    combined = balance.combined_open_pct(_expand_per_vent(floored, rooms), settings)

    return {
        "id": scenario.get("id"),
        "targets": {k: round(float(v), 6) for k, v in floored.items()},
        "predictedSpreadC": round(float(result.predicted_spread_c), 6),
        "airflowLimited": sorted(result.airflow_limited),
        "floorBinding": bool(floor_binding),
        "combinedOpenPct": round(float(combined), 6),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="gen_parity_fixtures", description=__doc__)
    parser.add_argument("--reference", type=Path, default=DEFAULT_REFERENCE)
    parser.add_argument("--scenarios", type=Path, default=DEFAULT_PARITY_DIR)
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument("--granularity", type=int, default=None)
    parser.add_argument("--tolerance", type=int, default=1)
    args = parser.parse_args(argv)

    reference_dir = args.reference.resolve()
    scenarios_dir = args.scenarios.resolve()
    out_dir = (args.out or args.scenarios).resolve()

    if not reference_dir.is_dir():
        print(f"ERROR: Reference directory not found: {reference_dir}", file=sys.stderr)
        return 2
    if not scenarios_dir.is_dir():
        print(f"ERROR: scenarios directory not found: {scenarios_dir}", file=sys.stderr)
        return 2

    # Load the Reference's HA-free pure modules directly by path. `learning`
    # first because `balance`'s curve handling consumes it (by duck-typing).
    learning = _load_module("ref_learning", reference_dir / "learning.py")
    balance = _load_module("ref_balance", reference_dir / "balance.py")
    sha = _reference_sha(reference_dir)

    scenario_files = sorted(scenarios_dir.glob(f"*{SCENARIO_SUFFIX}"))
    if not scenario_files:
        print(f"ERROR: no '*{SCENARIO_SUFFIX}' fixtures found under {scenarios_dir}", file=sys.stderr)
        return 1

    out_dir.mkdir(parents=True, exist_ok=True)
    failures = 0
    for path in scenario_files:
        scenario_id = path.name[: -len(SCENARIO_SUFFIX)]
        try:
            scenario = json.loads(path.read_text(encoding="utf-8"))
            expected = _process(balance, learning, scenario, args.granularity)
            expected["referenceCommit"] = sha
            expected["tolerance"] = {"aperturePoints": args.tolerance}
            out_path = out_dir / f"{scenario_id}{EXPECTED_SUFFIX}"
            out_path.write_text(json.dumps(expected, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            print(f"  generated {out_path.name}  (referenceCommit={sha[:12]})")
        except Exception as exc:  # noqa: BLE001 - report and continue
            failures += 1
            print(f"  FAILED {scenario_id}: {exc}", file=sys.stderr)

    print(f"Done: {len(scenario_files) - failures}/{len(scenario_files)} expected fixtures written to {out_dir}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
