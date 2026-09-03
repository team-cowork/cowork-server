#!/usr/bin/env python3
"""Run with python3 -m unittest discover -s scripts/coverage -p 'test_*.py'."""

import argparse
from decimal import Decimal
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import parse_qs, urlparse

import report


class CoverageFixtures(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def fixture(self, body, name="coverage.txt"):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
        return path

    def json_fixture(self, body, name="coverage.json"):
        return self.fixture(json.dumps(body), name)

    def collect(self, **values):
        options = {
            "module": "cowork-test", "snapshot": "pr", "metric": None,
            "format": "counts", "input": None, "output": self.root / "result.json",
            "absent": False, "exit_code": 0,
        }
        options.update(values)
        return report.collect(argparse.Namespace(**options))


class ParserTests(CoverageFixtures):
    def test_jacoco_uses_only_report_level_line_counter(self):
        path = self.fixture('''<report>
          <package><class><counter type="LINE" covered="8" missed="2"/></class>
            <counter type="LINE" covered="8" missed="2"/></package>
          <counter type="INSTRUCTION" covered="40" missed="20"/>
          <counter type="LINE" covered="8" missed="2"/>
        </report>''')
        self.assertEqual(report.parse_jacoco(path), (8, 10))

    def test_jacoco_rejects_nested_only_or_duplicate_aggregate(self):
        for xml in [
            '<report><package><counter type="LINE" covered="8" missed="2"/></package></report>',
            '<report><counter type="LINE" covered="8" missed="2"/><counter type="LINE" covered="8" missed="2"/></report>',
            '<report><counter type="LINE" covered="8" missed="-2"/></report>',
        ]:
            with self.subTest(xml=xml), self.assertRaises(report.CoverageError):
                report.parse_jacoco(self.fixture(xml))

    def test_go_weights_statements_and_merges_repeated_blocks(self):
        path = self.fixture('''mode: atomic
example/file.go:1.1,3.2 4 0
example/file.go:4.1,5.2 1 1
example/file.go:1.1,3.2 4 7
example/other.go:1.1,2.2 5 0
example/file.go:4.1,5.2 1 8
''')
        self.assertEqual(report.parse_go(path), (5, 10))

    def test_go_rejects_incompatible_or_malformed_blocks(self):
        for profile in [
            'mode: atomic\na.go:1.1,3.2 4 1\na.go:1.1,3.2 3 1\n',
            'mode: atomic\na.go:3.2,1.1 4 1\n',
            'mode: atomic\na.go:0.1,3.2 4 1\n',
            'mode: atomic\na.go:1.1,3.2 4 -1\n',
            'mode: invalid\n',
        ]:
            with self.subTest(profile=profile), self.assertRaises(report.CoverageError):
                report.parse_go(self.fixture(profile))

    def test_go_header_only_is_zero_denominator(self):
        self.assertEqual(report.parse_go(self.fixture('mode: atomic\n')), (0, 0))

    def test_go_accepts_empty_zero_statement_source_ranges(self):
        path = self.fixture('''mode: atomic
github.com/cowork/authorization/cmd/main.go:215.20,215.20 0 0
github.com/cowork/authorization/cmd/main.go:219.2,220.5 3 1
''')
        self.assertEqual(report.parse_go(path), (3, 3))

    def test_go_counts_distinct_blocks_with_overlapping_source_ranges(self):
        path = self.fixture('''mode: atomic
a.go:1.1,3.2 4 1
a.go:2.1,4.2 3 0
''')
        self.assertEqual(report.parse_go(path), (4, 7))

    def test_istanbul_uses_total_line_counts_not_file_or_pct(self):
        path = self.json_fixture({
            "total": {"lines": {"covered": 2, "total": 3, "pct": 99}, "statements": {"covered": 4, "total": 9}},
            "file.js": {"lines": {"covered": 10, "total": 10}},
        })
        self.assertEqual(report.parse_istanbul(path), (2, 3))
        self.assertEqual(report.parse_istanbul(path, "statement"), (4, 9))

    def test_json_counts_reject_invented_or_invalid_values(self):
        for body in [
            {"covered": 2, "total": 1}, {"covered": -1, "total": 1},
            {"covered": True, "total": 1}, {"covered": 0.5, "total": 1},
            {"covered": "1", "total": 1}, {"covered": 1}, [], None,
        ]:
            with self.subTest(body=body), self.assertRaises(report.CoverageError):
                report.parse_counts(self.json_fixture(body))

    def test_json_rejects_duplicate_keys(self):
        with self.assertRaises(report.CoverageError):
            report.parse_counts(self.fixture('{"covered":1,"covered":0,"total":1}'))

    def test_istanbul_requires_the_requested_metric(self):
        with self.assertRaises(report.CoverageError):
            report.parse_istanbul(self.json_fixture({"total": {"statements": {"covered": 1, "total": 1}}}))

    def test_lcov_merges_source_lines_without_double_counting(self):
        path = self.fixture('''TN:first
SF:src/app.js
DA:1,0
DA:2,1
LF:2
LH:1
BRDA:1,0,0,-
end_of_record
TN:second
SF:src/app.js
DA:1,2,checksum
DA:2,0
LF:2
LH:1
end_of_record
SF:src/other.js
DA:1,0
LF:1
LH:0
end_of_record
''')
        self.assertEqual(report.parse_lcov(path), (2, 3))

    def test_lcov_rejects_mismatched_or_incomplete_records(self):
        for body in [
            'SF:a.js\nDA:1,1\nLF:2\nLH:1\nend_of_record\n',
            'SF:a.js\nDA:1,1\n',
            'DA:1,1\nend_of_record\n',
            'SF:a.js\nDA:0,1\nend_of_record\n',
            'SF:a.js\nDA:1,-1\nend_of_record\n',
            'SF:a.js\nLF:10\nLH:1\nend_of_record\n',
            '',
        ]:
            with self.subTest(body=body), self.assertRaises(report.CoverageError):
                report.parse_lcov(self.fixture(body))

    def test_collect_preserves_absent_missing_failed_and_zero(self):
        valid = self.json_fixture({"covered": 0, "total": 0})
        for options, status, measured in [
            ({"absent": True}, "absent", (None, None)),
            ({}, "missing", (None, None)),
            ({"input": valid, "exit_code": 7}, "failed", (None, None)),
            ({"input": valid}, "ok", (0, 0)),
            ({"input": self.fixture("not json")}, "failed", (None, None)),
        ]:
            with self.subTest(status=status, options=options):
                result = self.collect(**options)
                self.assertEqual(result["status"], status)
                self.assertEqual((result["covered"], result["total"]), measured)
                self.assertEqual(json.loads((self.root / "result.json").read_text()), result)

    def test_collect_go_declares_statement_metric(self):
        result = self.collect(format="go", input=self.fixture('mode: set\na.go:1.1,2.1 3 1\n'))
        self.assertEqual(result["metric"], "statement")
        self.assertEqual((result["covered"], result["total"]), (3, 3))
        invalid = self.collect(format="go", metric="line", input=self.fixture('mode: atomic\n'))
        self.assertEqual(invalid["status"], "failed")

    def test_collector_cli_exits_nonzero_for_unavailable_measurement_after_saving_it(self):
        for arguments, status, exit_code in [
            ([], "missing", 1), (["--absent"], "absent", 0),
            (["--exit-code", "7"], "failed", 1),
        ]:
            with self.subTest(status=status):
                result = self.root / "result.json"
                completed = subprocess.run([
                    sys.executable, str(Path(report.__file__)), "collect", "--module", "cowork-large",
                    "--snapshot", "base", "--format", "counts", "--output", str(result), *arguments,
                ], capture_output=True, text=True)
                self.assertEqual(completed.returncode, exit_code, completed.stderr)
                self.assertEqual(json.loads(result.read_text())["status"], status)


class RenderTests(CoverageFixtures):
    def setUp(self):
        super().setUp()
        self.modules = [
            {"module": "cowork-large", "runner": "linux", "metric": "line"},
            {"module": "cowork-small", "runner": "linux", "metric": "statement"},
        ]
        self.manifest = self.json_fixture({
            "modules": self.modules,
            "excluded": [{"module": "cowork-monitoring", "reason": "설정 전용"}],
        }, "manifest.json")
        self.artifacts = self.root / "artifacts"
        self.artifacts.mkdir()

    def result(self, module, snapshot, covered=None, total=None, status="ok", **extra):
        metric = next(item["metric"] for item in self.modules if item["module"] == module)
        raw = {"module": module, "snapshot": snapshot, "metric": metric, "status": status, "covered": covered, "total": total}
        raw.update(extra)
        return self.json_fixture(raw, f"artifacts/{module}-{snapshot}/result.json")

    def valid_results(self):
        self.result("cowork-large", "base", 900, 1000)
        self.result("cowork-large", "pr", 800, 1000)
        self.result("cowork-small", "base", 0, 10)
        self.result("cowork-small", "pr", 10, 10)

    def render(self):
        return report.render(argparse.Namespace(
            artifacts=self.artifacts, manifest=self.manifest, base_sha="a" * 40,
            pr_sha="b" * 40, head_sha="c" * 40,
            run_url="https://github.com/example/repo/actions/runs/1", output=self.root / "comment.md",
        ))

    def test_render_weighted_mixed_aggregate_and_colored_changes(self):
        self.valid_results()
        body = self.render()
        self.assertIn("89.1% → 80.2% · -8.9%p", body)
        self.assertIn("Base: 900/1,010 · PR: 810/1,010", body)
        self.assertIn("| cowork-large | 라인 | 90.0% (900/1,000) | 80.0% (800/1,000) | -10.0%p", body)
        self.assertIn("\n- cowork-large: -10.0%p\n", body)
        self.assertIn("\n+ cowork-small: +100.0%p\n", body)
        self.assertIn("혼합 지표", body)
        self.assertIn("| cowork-monitoring | — | N/A | N/A | N/A | 설정 전용 |", body)
        self.assertEqual(body.count(report.MARKER), 1)
        self.assertEqual((self.root / "comment.md").read_text(), body)

    def test_delta_is_calculated_before_rounding_percentages(self):
        base = report.Result("x", "base", "line", "ok", 1004, 10000)
        pr = report.Result("x", "pr", "line", "ok", 1006, 10000)
        self.assertEqual(report.percentage(base.percent), "10.0%")
        self.assertEqual(report.percentage(pr.percent), "10.1%")
        self.assertEqual(report.delta_text(report.delta(base, pr)), "0.0%p")

    def test_rounding_eliminates_negative_zero_and_uses_half_up(self):
        self.assertEqual(report.delta_text(Decimal("-0.049")), "0.0%p")
        self.assertEqual(report.delta_text(Decimal("-0.05")), "-0.1%p")
        self.assertEqual(report.delta_text(Decimal("0.05")), "+0.1%p")
        self.assertEqual(report.percentage(Decimal("12.25")), "12.3%")
        self.assertEqual(report.percentage(None), "N/A")

    def test_missing_or_failed_result_suppresses_all_whole_numbers_and_chart(self):
        for status in ("failed", "missing"):
            with self.subTest(status=status):
                self.valid_results()
                self.result("cowork-small", "base", status=status, reason="test failure | <tag>\n```bad")
                body = self.render()
                self.assertIn("**전체: N/A · 증감: N/A**", body)
                self.assertNotIn("quickchart.io", body)
                self.assertNotIn("89.1%", body)
                self.assertIn("80.0% (800/1,000)", body)
                self.assertIn("test failure &#124; &lt;tag&gt; &#96;&#96;&#96;bad", body)

    def test_absent_module_is_excluded_and_does_not_become_zero(self):
        self.valid_results()
        self.result("cowork-small", "base", status="absent")
        body = self.render()
        self.assertIn("90.0% → 80.2% · -9.8%p", body)
        self.assertIn("| cowork-small | 구문 | N/A | 100.0% (10/10) | N/A | 모듈 추가", body)
        self.assertIn("측정 범위가 달라질 수", body)

    def test_zero_denominator_is_na_without_donut_or_failure(self):
        for module in self.modules:
            for snapshot in ("base", "pr"):
                self.result(module["module"], snapshot, 0, 0)
        body = self.render()
        self.assertIn("N/A → N/A · N/A", body)
        self.assertIn("측정 항목 없음", body)
        self.assertNotIn("100.0%", body)
        self.assertNotIn("quickchart.io", body)
        self.assertNotIn("일부 측정이 실패", body)

    def test_missing_duplicate_and_wrong_metric_are_unavailable(self):
        self.valid_results()
        (self.artifacts / "cowork-small-base/result.json").unlink()
        duplicate = self.artifacts / "duplicate"
        duplicate.mkdir()
        (duplicate / "result.json").write_text((self.artifacts / "cowork-large-base/result.json").read_text())
        self.result("cowork-large", "pr", 1, 1, metric="statement")
        results, diagnostics = report.load_results(self.artifacts, self.modules)
        self.assertFalse(diagnostics)
        self.assertEqual(results[("cowork-small", "base")].status, "missing")
        self.assertEqual(results[("cowork-large", "base")].reason, "Duplicate coverage artifacts")
        self.assertIn("metric", results[("cowork-large", "pr")].reason)
        self.assertNotIn("quickchart.io", self.render())

    def test_malformed_artifact_adds_diagnostic_and_suppresses_whole(self):
        self.valid_results()
        self.fixture("not json", "artifacts/bad/result.json")
        body = self.render()
        self.assertIn("보고서 검증 오류", body)
        self.assertIn("**전체: N/A", body)

    def test_normalization_rejects_noninteger_or_inconsistent_results(self):
        original = {"module": "x", "snapshot": "pr", "metric": "line", "status": "ok", "covered": 1, "total": 2}
        for extra in [{"covered": True}, {"total": "2"}, {"status": []}, {"status": "absent"}, {"reason": []}]:
            with self.subTest(extra=extra), self.assertRaises(report.CoverageError):
                report.normalize_result(original | extra, "x", "pr", "line")

    def test_donut_contains_only_numeric_coverage_and_static_chart_metadata(self):
        first = report.donut_url(81, 101)
        self.assertEqual(first, report.donut_url(81, 101))
        query = parse_qs(urlparse(first).query)
        chart = json.loads(query["c"][0])
        self.assertEqual(query["version"], ["2"])
        self.assertEqual(chart["type"], "doughnut")
        self.assertEqual(chart["data"]["datasets"][0]["data"], [81, 20])
        self.assertEqual(chart["data"]["labels"], ["Covered", "Missed"])
        self.assertEqual(chart["options"]["plugins"]["doughnutlabel"]["labels"][0]["text"], "80.2%")

    def test_cli_collect_and_render(self):
        source = self.json_fixture({"covered": 1, "total": 3})
        collector = subprocess.run([
            sys.executable, str(Path(report.__file__)), "collect", "--module", "cowork-large",
            "--snapshot", "base", "--format", "counts", "--input", str(source),
            "--output", str(self.artifacts / "cowork-large-base/result.json"), "--exit-code", "0",
        ], capture_output=True, text=True)
        self.assertEqual(collector.returncode, 0, collector.stderr)
        renderer = subprocess.run([
            sys.executable, str(Path(report.__file__)), "render", "--artifacts", str(self.artifacts),
            "--manifest", str(self.manifest), "--base-sha", "a" * 40, "--pr-sha", "b" * 40,
            "--head-sha", "c" * 40, "--run-url", "https://github.com/example/repo/actions/runs/1",
            "--output", str(self.root / "cli.md"),
        ], capture_output=True, text=True)
        self.assertEqual(renderer.returncode, 0, renderer.stderr)
        self.assertIn("33.3% (1/3)", (self.root / "cli.md").read_text())


if __name__ == "__main__":
    unittest.main()
