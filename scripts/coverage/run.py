#!/usr/bin/env python3
"""Run identical coverage tooling against either source checkout."""

import argparse
import json
import subprocess
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent


def build_matrix(manifest, source):
    registered = [entry["module"] for entry in manifest["modules"] + manifest["excluded"]]
    if len(set(registered)) != len(registered):
        raise ValueError("Duplicate coverage module registration")
    discovered = {path.name for path in source.glob("cowork-*") if path.is_dir()}
    unknown = discovered - set(registered)
    if unknown:
        raise ValueError(f"Register coverage for new modules in modules.json: {', '.join(sorted(unknown))}")
    return {"include": [
        {"module": entry["module"], "runner": entry["runner"], "snapshot": snapshot}
        for entry in manifest["modules"]
        for snapshot in ("base", "pr")
    ]}


def measure(entry, snapshot, source, output):
    if not source.is_dir():
        raise ValueError(f"Source checkout does not exist: {source}")
    output.mkdir(parents=True, exist_ok=True)
    report = output / entry["report"]
    result = output / "result.json"
    # Never reuse a previous measurement, even when a local output directory is reused.
    report.unlink(missing_ok=True)
    result.unlink(missing_ok=True)
    collect = [
        sys.executable, str(TOOLS / "report.py"), "collect",
        "--module", entry["module"], "--snapshot", snapshot,
        "--format", entry["format"], "--metric", entry["metric"],
        "--input", str(report), "--output", str(result),
    ]
    if not (source / entry["module"]).is_dir():
        return subprocess.run(collect + ["--absent"], check=False).returncode

    adapter = "run-jvm.sh" if entry["runner"] in {"gradle", "maven", "amper"} else "run-native.sh"
    completed = subprocess.run([
        "bash", str(TOOLS / adapter), entry["runner"], entry["module"], str(source), str(output),
    ], check=False)
    exit_code = completed.returncode if completed.returncode >= 0 else 128 - completed.returncode
    normalized = subprocess.run(collect + ["--exit-code", str(exit_code)], check=False)
    return exit_code or normalized.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    matrix = commands.add_parser("matrix")
    matrix.add_argument("--source", type=Path, required=True)
    run = commands.add_parser("measure")
    run.add_argument("--module", required=True)
    run.add_argument("--snapshot", choices=["base", "pr"], required=True)
    run.add_argument("--source", type=Path, required=True)
    run.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads((TOOLS / "modules.json").read_text())
    if args.command == "matrix":
        print(json.dumps(build_matrix(manifest, args.source.resolve()), separators=(",", ":")))
        return 0
    entries = {entry["module"]: entry for entry in manifest["modules"]}
    if args.module not in entries:
        parser.error(f"Unregistered module: {args.module}")
    return measure(entries[args.module], args.snapshot, args.source.resolve(), args.output.resolve())


if __name__ == "__main__":
    sys.exit(main())
