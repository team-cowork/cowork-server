#!/usr/bin/env python3
"""Normalize native coverage reports and render the PR coverage comment.

Only Python's standard library is required. Coverage results deliberately retain
integer counts; percentages and percentage-point changes are rounded at display
time, after aggregation and subtraction.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP, localcontext
import html
import json
from pathlib import Path
import re
from typing import Any
from urllib.parse import urlencode
import xml.etree.ElementTree as ET


MARKER = "<!-- coverage-report-bot -->"
STATUSES = {"ok", "failed", "missing", "absent"}
METRICS = {"line", "statement"}
SNAPSHOTS = {"base", "pr"}


class CoverageError(ValueError):
    """A report cannot be interpreted without inventing coverage counts."""


def integer(value: Any, name: str) -> int:
    if isinstance(value, str) and re.fullmatch(r"[0-9]+", value):
        return int(value)
    if type(value) is int and value >= 0:
        return value
    raise CoverageError(f"{name} must be a non-negative integer")


def counts(covered: Any, total: Any) -> tuple[int, int]:
    if type(covered) is not int or type(total) is not int:
        raise CoverageError("covered and total must be JSON integers")
    covered = integer(covered, "covered")
    total = integer(total, "total")
    if covered > total:
        raise CoverageError("covered exceeds total")
    return covered, total


def read_json(path: Path) -> Any:
    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise CoverageError(f"duplicate JSON key: {key}")
            result[key] = value
        return result

    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)


def parse_jacoco(path: Path) -> tuple[int, int]:
    root = ET.parse(path).getroot()
    if root.tag != "report":
        raise CoverageError("JaCoCo document root must be report")
    # Package/class/method counters repeat the same lines. Only the direct
    # report-level counter is the aggregate for the entire module.
    counters = [counter for counter in root.findall("counter") if counter.get("type") == "LINE"]
    if len(counters) != 1:
        raise CoverageError("JaCoCo must contain exactly one report-level LINE counter")
    covered = integer(counters[0].get("covered"), "covered")
    missed = integer(counters[0].get("missed"), "missed")
    return covered, covered + missed


def parse_go(path: Path) -> tuple[int, int]:
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0].strip() not in {"mode: set", "mode: count", "mode: atomic"}:
        raise CoverageError("Go profile has no valid mode header")
    blocks: dict[tuple[str, tuple[int, int], tuple[int, int]], tuple[int, bool]] = {}
    pattern = re.compile(r"(.+):([0-9]+)\.([0-9]+),([0-9]+)\.([0-9]+)\s+([0-9]+)\s+([0-9]+)")
    for index, line in enumerate(lines[1:], 2):
        if not line.strip():
            continue
        match = pattern.fullmatch(line.strip())
        if match is None:
            raise CoverageError(f"invalid Go coverage block at line {index}")
        filename = match[1]
        start = (int(match[2]), int(match[3]))
        end = (int(match[4]), int(match[5]))
        statements, executions = int(match[6]), int(match[7])
        if min(*start, *end) <= 0 or start > end or (start == end and statements != 0):
            raise CoverageError(f"invalid Go source range at line {index}")
        key = (filename, start, end)
        if key in blocks:
            old_statements, old_covered = blocks[key]
            if old_statements != statements:
                raise CoverageError("repeated Go block has inconsistent statement counts")
            blocks[key] = (statements, old_covered or executions > 0)
        else:
            blocks[key] = (statements, executions > 0)

    covered = total = 0
    # -coverpkg can emit the same source block from several test binaries.
    # Match Go's profile parser: merge identical locations, then sum NumStmt
    # for distinct blocks even when their displayed source ranges overlap.
    for statements, hit in blocks.values():
        total += statements
        if hit:
            covered += statements
    return covered, total


def parse_istanbul(path: Path, metric: str = "line") -> tuple[int, int]:
    report = read_json(path)
    key = "lines" if metric == "line" else "statements"
    try:
        summary = report["total"][key]
        return counts(summary["covered"], summary["total"])
    except (KeyError, TypeError) as error:
        raise CoverageError(f"Istanbul JSON summary has no total.{key} counts") from error


def parse_lcov(path: Path) -> tuple[int, int]:
    sources: dict[str, dict[int, bool]] = {}
    filename: str | None = None
    source_lines: dict[int, bool] = {}
    summary: dict[str, int] = {}
    records = 0
    for index, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line:
            continue
        if line.startswith("SF:"):
            if filename is not None or not line[3:]:
                raise CoverageError(f"invalid LCOV source record at line {index}")
            filename, source_lines, summary = line[3:], {}, {}
        elif line.startswith("DA:"):
            if filename is None:
                raise CoverageError("LCOV DA outside a source record")
            values = line[3:].split(",")
            if len(values) not in {2, 3}:
                raise CoverageError(f"invalid LCOV DA at line {index}")
            number = integer(values[0], "line number")
            executions = integer(values[1], "execution count")
            if number == 0:
                raise CoverageError("LCOV line numbers must be positive")
            source_lines[number] = source_lines.get(number, False) or executions > 0
        elif line.startswith(("LF:", "LH:")):
            key = line[:2]
            if filename is None or key in summary:
                raise CoverageError(f"invalid LCOV {key} at line {index}")
            summary[key] = integer(line[3:], key)
        elif line == "end_of_record":
            if filename is None:
                raise CoverageError("LCOV end_of_record without SF")
            measured = {"LF": len(source_lines), "LH": sum(source_lines.values())}
            for key, value in summary.items():
                if measured[key] != value:
                    raise CoverageError(f"LCOV {key} does not match its DA records")
            combined = sources.setdefault(filename, {})
            for number, hit in source_lines.items():
                combined[number] = combined.get(number, False) or hit
            filename, source_lines, summary = None, {}, {}
            records += 1
        elif ":" not in line:
            raise CoverageError(f"invalid LCOV entry at line {index}")
        # Function and branch records do not contribute to line coverage.
    if filename is not None:
        raise CoverageError("LCOV source record is missing end_of_record")
    if records == 0:
        raise CoverageError("LCOV contains no source records")
    return sum(sum(lines.values()) for lines in sources.values()), sum(map(len, sources.values()))


def parse_counts(path: Path) -> tuple[int, int]:
    report = read_json(path)
    try:
        return counts(report["covered"], report["total"])
    except (KeyError, TypeError) as error:
        raise CoverageError("counts JSON requires covered and total") from error


def collect(args: argparse.Namespace) -> dict[str, Any]:
    metric = args.metric or ("statement" if args.format == "go" else "line")
    result: dict[str, Any] = {
        "module": args.module,
        "snapshot": args.snapshot,
        "metric": metric,
        "status": "ok",
        "covered": None,
        "total": None,
    }
    if args.absent:
        result.update(status="absent", reason="Module does not exist at this revision")
    elif args.exit_code != 0:
        result.update(status="failed", reason=f"Test or coverage collection failed (exit code {args.exit_code})")
    elif args.input is None or not args.input.is_file():
        result.update(status="missing", reason="Coverage report was not generated")
    else:
        try:
            if args.format == "istanbul":
                measured = parse_istanbul(args.input, metric)
            else:
                if args.format == "go" and metric != "statement":
                    raise CoverageError("Go cover profiles measure statements, not lines")
                if args.format in {"jacoco", "lcov"} and metric != "line":
                    raise CoverageError(f"{args.format} collector measures lines")
                measured = {
                    "jacoco": parse_jacoco,
                    "go": parse_go,
                    "lcov": parse_lcov,
                    "counts": parse_counts,
                }[args.format](args.input)
            result["covered"], result["total"] = measured
        except (CoverageError, OSError, UnicodeError, ET.ParseError, json.JSONDecodeError) as error:
            result.update(status="failed", reason=f"Invalid coverage report: {error}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


@dataclass(frozen=True)
class Result:
    module: str
    snapshot: str
    metric: str
    status: str
    covered: int | None = None
    total: int | None = None
    reason: str = ""

    @property
    def percent(self) -> Decimal | None:
        if self.status != "ok" or not self.total:
            return None
        with localcontext() as context:
            context.prec = 50
            return Decimal(self.covered) * 100 / Decimal(self.total)


def normalize_result(raw: Any, module: str, snapshot: str, metric: str) -> Result:
    if not isinstance(raw, dict):
        raise CoverageError("result must be a JSON object")
    if raw.get("module") != module or raw.get("snapshot") != snapshot:
        raise CoverageError("result identity does not match the expected module and snapshot")
    if raw.get("metric") != metric:
        raise CoverageError("result metric does not match the module manifest")
    status = raw.get("status")
    if not isinstance(status, str) or status not in STATUSES:
        raise CoverageError("unknown coverage status")
    reason = raw.get("reason", "")
    if not isinstance(reason, str):
        raise CoverageError("coverage reason must be text")
    if status == "ok":
        covered, total = counts(raw.get("covered"), raw.get("total"))
    else:
        if raw.get("covered") is not None or raw.get("total") is not None:
            raise CoverageError("unavailable coverage must not contain numeric counts")
        covered = total = None
    return Result(module, snapshot, metric, status, covered, total, reason)


def load_results(artifacts: Path, modules: list[dict[str, Any]]) -> tuple[dict[tuple[str, str], Result], list[str]]:
    expected = {module["module"]: module["metric"] for module in modules}
    candidates: dict[tuple[str, str], list[Any]] = {}
    diagnostics = []
    for path in sorted(artifacts.rglob("result.json")):
        try:
            raw = read_json(path)
            if not isinstance(raw, dict):
                raise CoverageError("result must be a JSON object")
            module, snapshot = raw.get("module"), raw.get("snapshot")
            if not isinstance(module, str) or not isinstance(snapshot, str):
                raise CoverageError("result is missing module or snapshot")
            if module not in expected or snapshot not in SNAPSHOTS:
                raise CoverageError("result has an unknown module or snapshot")
            candidates.setdefault((module, snapshot), []).append(raw)
        except (CoverageError, OSError, UnicodeError, json.JSONDecodeError) as error:
            diagnostics.append(f"{path.relative_to(artifacts)}: {error}")

    results = {}
    for module, metric in expected.items():
        for snapshot in ("base", "pr"):
            key = (module, snapshot)
            values = candidates.get(key, [])
            if not values:
                results[key] = Result(module, snapshot, metric, "missing", reason="Coverage artifact is missing")
            elif len(values) != 1:
                results[key] = Result(module, snapshot, metric, "failed", reason="Duplicate coverage artifacts")
            else:
                try:
                    results[key] = normalize_result(values[0], module, snapshot, metric)
                except CoverageError as error:
                    results[key] = Result(module, snapshot, metric, "failed", reason=f"Invalid coverage artifact: {error}")
    return results, diagnostics


def round_one(value: Decimal) -> Decimal:
    rounded = value.quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
    return Decimal("0.0") if rounded == 0 else rounded


def percentage(value: Decimal | None) -> str:
    return "N/A" if value is None else f"{round_one(value):.1f}%"


def delta(base: Result, pr: Result) -> Decimal | None:
    if base.percent is None or pr.percent is None:
        return None
    with localcontext() as context:
        context.prec = 50
        return round_one(pr.percent - base.percent)


def delta_text(value: Decimal | None) -> str:
    if value is None:
        return "N/A"
    value = round_one(value)
    return f"{'+' if value > 0 else ''}{value:.1f}%p"


def aggregate(results: list[Result], snapshot: str) -> Result:
    if any(result.status not in {"ok", "absent"} for result in results):
        return Result("전체", snapshot, "mixed", "missing", reason="Incomplete coverage")
    return Result(
        "전체", snapshot, "mixed", "ok",
        sum(result.covered or 0 for result in results),
        sum(result.total or 0 for result in results),
    )


def text(value: Any) -> str:
    """Keep metadata and native error text inside one Markdown table cell."""
    return html.escape(" ".join(str(value).split()), quote=False).replace("|", "&#124;").replace("`", "&#96;")


def measurement(result: Result) -> str:
    if result.percent is None:
        return "N/A"
    return f"{percentage(result.percent)} ({result.covered:,}/{result.total:,})"


def row_status(base: Result, pr: Result) -> str:
    problems = []
    for result in (base, pr):
        if result.status in {"failed", "missing"}:
            label = "실패" if result.status == "failed" else "누락"
            problems.append(f"{result.snapshot}: {label} — {text(result.reason[:500])}")
        elif result.status == "ok" and result.total == 0:
            problems.append(f"{result.snapshot}: 측정 항목 없음")
    if problems:
        return "; ".join(problems)
    if base.status == "absent" and pr.status == "absent":
        return "양쪽 리비전에 모듈 없음"
    if base.status == "absent":
        return "모듈 추가 (base 없음)"
    if pr.status == "absent":
        return "모듈 삭제 (PR 없음)"
    return "완료"


def donut_url(covered: int, total: int) -> str:
    center_label = percentage(Result("", "pr", "mixed", "ok", covered, total).percent)
    chart = {
        "type": "doughnut",
        "data": {
            "labels": ["Covered", "Missed"],
            "datasets": [{"data": [covered, total - covered], "backgroundColor": ["#2da44e", "#cf222e"], "borderWidth": 0}],
        },
        "options": {
            "cutoutPercentage": 70,
            "legend": {"position": "bottom"},
            "plugins": {
                "datalabels": {"display": False},
                "doughnutlabel": {"labels": [{"text": center_label, "font": {"size": 28}, "color": "#24292f"}]},
            },
        },
    }
    return "https://quickchart.io/chart?" + urlencode({
        "version": "2", "width": 360, "height": 220, "backgroundColor": "white",
        "c": json.dumps(chart, separators=(",", ":")),
    })


def read_manifest(path: Path) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    manifest = read_json(path)
    if not isinstance(manifest, dict) or not isinstance(manifest.get("modules"), list) or not manifest["modules"]:
        raise CoverageError("manifest requires a nonempty modules array")
    modules = manifest["modules"]
    seen = set()
    for module in modules:
        if not isinstance(module, dict) or not isinstance(module.get("module"), str) or not module["module"]:
            raise CoverageError("manifest module requires a nonempty module name")
        if module["module"] in seen or not isinstance(module.get("metric"), str) or module["metric"] not in METRICS:
            raise CoverageError("manifest contains a duplicate module or invalid metric")
        seen.add(module["module"])
    excluded = manifest.get("excluded", [])
    if not isinstance(excluded, list):
        raise CoverageError("manifest excluded must be an array")
    for module in excluded:
        if not isinstance(module, dict) or not isinstance(module.get("module"), str) or not isinstance(module.get("reason"), str):
            raise CoverageError("excluded module requires module and reason strings")
        if module["module"] in seen:
            raise CoverageError("manifest contains a duplicate excluded module")
        seen.add(module["module"])
    return modules, excluded


def render(args: argparse.Namespace) -> str:
    modules, excluded = read_manifest(args.manifest)
    results, diagnostics = load_results(args.artifacts, modules)
    base = aggregate([results[(module["module"], "base")] for module in modules], "base")
    pr = aggregate([results[(module["module"], "pr")] for module in modules], "pr")
    complete = base.status == pr.status == "ok" and not diagnostics
    lines = [MARKER, "## 테스트 커버리지", ""]
    if complete:
        lines += [f"**전체 (라인/구문 가중 합산): {percentage(base.percent)} → {percentage(pr.percent)} · {delta_text(delta(base, pr))}**", ""]
        lines += [f"Base: {base.covered:,}/{base.total:,} · PR: {pr.covered:,}/{pr.total:,} (covered/total)", ""]
        if pr.total:
            lines += [f"![PR 전체 커버리지 {percentage(pr.percent)} — covered {pr.covered}, total {pr.total}]({donut_url(pr.covered, pr.total)})", ""]
    else:
        lines += ["**전체: N/A · 증감: N/A**", "", "일부 측정이 실패하거나 누락되어 전체 수치·증감·도넛 차트를 표시하지 않았습니다.", ""]

    lines += ["| 모듈 | 단위 | Base | PR | 증감 | 상태 |", "| --- | --- | ---: | ---: | ---: | --- |"]
    changes: list[tuple[str, Decimal | None]] = [("전체", delta(base, pr) if complete else None)]
    for module in modules:
        name = module["module"]
        previous, current = results[(name, "base")], results[(name, "pr")]
        change = delta(previous, current)
        metric = "라인" if module["metric"] == "line" else "구문"
        lines.append(f"| {text(name)} | {metric} | {measurement(previous)} | {measurement(current)} | {delta_text(change)} | {row_status(previous, current)} |")
        changes.append((name, change))
    for module in excluded:
        lines.append(f"| {text(module['module'])} | — | N/A | N/A | N/A | {text(module['reason'])} |")

    lines += ["", "```diff"]
    for name, change in changes:
        prefix = "-" if change is not None and change < 0 else "+" if change is not None and change > 0 else " "
        lines.append(f"{prefix} {text(name)}: {delta_text(change)}")
    lines += ["```", "", "증감은 PR − Base의 퍼센트포인트(%p)이며, 반올림 전 수치로 계산한 뒤 소수점 첫째 자리까지 표시합니다. 표시값이 0.0%p인 경우 부호와 색을 붙이지 않습니다.", ""]
    lines += ["전체는 모듈별 퍼센트의 단순 평균이 아니라 covered/total 합산값입니다. JVM·JavaScript·Elixir의 라인과 Go의 구문을 합친 혼합 지표입니다. 분모가 0이면 N/A로 표시하며, 한쪽 리비전에 없는 모듈은 해당 리비전 합산에서 제외합니다.", ""]
    if any(result.status == "absent" for result in results.values()):
        lines += ["모듈 추가·삭제로 두 리비전의 측정 범위가 달라질 수 있습니다. 해당 모듈의 개별 증감은 N/A입니다.", ""]
    if diagnostics:
        lines += ["보고서 검증 오류:", ""] + [f"- {text(message[:500])}" for message in diagnostics] + [""]
    # Workflow metadata is display-only; escape syntax before embedding it.
    lines += [f"Base: `{text(args.base_sha)}` · PR 병합: `{text(args.pr_sha)}` · PR head: `{text(args.head_sha)}`", ""]
    run_url = args.run_url.replace("(", "%28").replace(")", "%29").replace("\n", "").replace("\r", "")
    lines += [f"[CI 실행 및 원본 보고서]({run_url}) · 커버리지 하락과 최소 수치는 CI 실패 조건으로 사용하지 않습니다.", ""]
    body = "\n".join(lines)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(body, encoding="utf-8")
    return body


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    collector = commands.add_parser("collect", help="normalize a native coverage report")
    collector.add_argument("--module", required=True)
    collector.add_argument("--snapshot", choices=sorted(SNAPSHOTS), required=True)
    collector.add_argument("--format", choices=["jacoco", "go", "istanbul", "lcov", "counts"], required=True)
    collector.add_argument("--metric", choices=sorted(METRICS))
    collector.add_argument("--input", type=Path)
    collector.add_argument("--output", type=Path, required=True)
    collector.add_argument("--exit-code", type=int, default=0)
    collector.add_argument("--absent", action="store_true", help="module source is absent at this revision")
    collector.set_defaults(handler=collect)
    renderer = commands.add_parser("render", help="render all module results as a Markdown comment")
    renderer.add_argument("--artifacts", type=Path, required=True)
    renderer.add_argument("--manifest", type=Path, required=True)
    renderer.add_argument("--base-sha", required=True)
    renderer.add_argument("--pr-sha", required=True)
    renderer.add_argument("--head-sha", required=True)
    renderer.add_argument("--run-url", required=True)
    renderer.add_argument("--output", type=Path, required=True)
    renderer.set_defaults(handler=render)
    args = parser.parse_args()
    try:
        result = args.handler(args)
        if args.command == "collect" and result["status"] in {"failed", "missing"}:
            return 1
        return 0
    except (CoverageError, OSError, UnicodeError, json.JSONDecodeError) as error:
        parser.exit(1, f"coverage: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
