#!/usr/bin/env python3
"""Plan Dockerfile-owned verification and consume its runtime-neutral result."""
import argparse
import json
import re
import sys
from pathlib import Path

import report


def has_contract(source, module):
    dockerfile = source / module / "Dockerfile.prod"
    return dockerfile.is_file() and re.search(r"^FROM\s+(?:--[^\s]+\s+)*[^\s]+\s+AS\s+verify\s*$", dockerfile.read_text(), re.I | re.M) is not None


def plan(base, head, selection, output):
    modules = sorted({"cowork-" + entry["service"] for entry in selection["include"]})
    manifest = {"modules": [{"module": name, "metric": "line"} for name in modules], "excluded": []}
    output.write_text(json.dumps(manifest, indent=2) + "\n")
    matrix = {"include": [{"module": name, "snapshot": snapshot, "contract": has_contract(source, name),
                            "present": (source / name).is_dir()}
                           for name in modules for snapshot, source in (("base", base), ("pr", head))]}
    print(json.dumps(matrix, separators=(",", ":")))


def collect(module, snapshot, source, artifact, output):
    result = dict(module=module, snapshot=snapshot, metric="line", status="missing", covered=None, total=None)
    if not (source / module).is_dir():
        result.update(status="absent", reason="Module does not exist at this revision")
    elif not has_contract(source, module):
        result.update(reason="This revision has no Dockerfile verify contract; coverage was not measured")
    else:
        try:
            raw = json.loads((artifact / "coverage.json").read_text())
            if not isinstance(raw, dict) or raw.get("metric") not in report.METRICS:
                raise ValueError("coverage.json requires a line or statement metric")
            if raw.get("status") not in {"ok", "untested"}:
                raise ValueError("verify must produce an ok or untested coverage result")
            result.update(metric=raw["metric"], status=raw["status"], covered=raw.get("covered"), total=raw.get("total"))
            if raw["status"] == "untested":
                if not isinstance(raw.get("reason"), str) or not raw["reason"].strip():
                    raise ValueError("untested requires a reason")
                result["reason"] = raw["reason"]
            report.normalize_result(result, module, snapshot, result["metric"])
        except (OSError, ValueError) as error:
            result.update(status="failed", covered=None, total=None, reason=f"Verification or coverage export failed: {error}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(f"{module} ({snapshot}): {result['status']}")
    return 1 if result["status"] == "failed" else 0


def render(artifacts, manifest, output):
    # The metric comes from each Dockerfile's result, including runtime changes
    # between snapshots. Never classify source files to guess it.
    data = json.loads(manifest.read_text())
    candidates = {}
    for path in artifacts.rglob("result.json"):
        try:
            raw = json.loads(path.read_text())
            if isinstance(raw, dict):
                candidates.setdefault((raw.get("module"), raw.get("snapshot")), []).append(raw)
        except (OSError, ValueError):
            pass  # report.render reports malformed artifacts separately.
    for entry in data["modules"]:
        entry["snapshots"] = {}
        for snapshot in ("base", "pr"):
            values = candidates.get((entry["module"], snapshot), [])
            metric = values[0].get("metric") if len(values) == 1 else "line"
            entry["snapshots"][snapshot] = {"metric": metric if metric in report.METRICS else "line"}
    manifest.write_text(json.dumps(data, indent=2) + "\n")
    rendered = report.render(argparse.Namespace(artifacts=artifacts, manifest=manifest, output=output))
    output.write_text(rendered)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    planner = commands.add_parser("plan")
    planner.add_argument("--base", type=Path, required=True)
    planner.add_argument("--head", type=Path, required=True)
    planner.add_argument("--matrix", type=json.loads, required=True)
    planner.add_argument("--output", type=Path, required=True)
    collector = commands.add_parser("collect")
    collector.add_argument("--module", required=True)
    collector.add_argument("--snapshot", choices=("base", "pr"), default="pr")
    collector.add_argument("--source", type=Path, default=Path("."))
    collector.add_argument("--artifact", type=Path, required=True)
    collector.add_argument("--output", type=Path, required=True)
    renderer = commands.add_parser("render")
    renderer.add_argument("--artifacts", type=Path, required=True)
    renderer.add_argument("--manifest", type=Path, required=True)
    renderer.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "plan":
        plan(args.base, args.head, args.matrix, args.output)
    elif args.command == "collect":
        if re.fullmatch(r"cowork-[a-z][a-z0-9-]*", args.module) is None:
            parser.error("invalid module name")
        sys.exit(collect(args.module, args.snapshot, args.source, args.artifact, args.output))
    else:
        render(args.artifacts, args.manifest, args.output)


if __name__ == "__main__":
    main()
