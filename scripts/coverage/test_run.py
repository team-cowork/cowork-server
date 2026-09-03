import json
import tempfile
import unittest
from pathlib import Path

import run


class CoveragePlanTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((run.TOOLS / "modules.json").read_text())

    def test_every_code_module_has_both_snapshots_and_monitoring_is_excluded(self):
        matrix = run.build_matrix(self.manifest, run.TOOLS.parent.parent)
        expected = {entry["module"] for entry in self.manifest["modules"]}
        self.assertEqual(
            {(entry["module"], entry["snapshot"]) for entry in matrix["include"]},
            {(module, snapshot) for module in expected for snapshot in ("base", "pr")},
        )
        self.assertEqual(len(matrix["include"]), 2 * len(expected))
        self.assertNotIn("cowork-monitoring", expected)
        self.assertEqual(next(e["runner"] for e in matrix["include"] if e["module"] == "cowork-project"), "maven")

    def test_new_unregistered_module_cannot_be_silently_omitted(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)
            (source / "cowork-new").mkdir()
            with self.assertRaisesRegex(ValueError, "cowork-new"):
                run.build_matrix(self.manifest, source)

    def test_duplicate_module_registration_is_rejected(self):
        self.manifest["modules"].append(self.manifest["modules"][0])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            run.build_matrix(self.manifest, run.TOOLS.parent.parent)

    def test_absent_module_replaces_stale_result_without_running_tests(self):
        with tempfile.TemporaryDirectory() as temp:
            source, output = Path(temp) / "source", Path(temp) / "output"
            source.mkdir()
            output.mkdir()
            (output / "result.json").write_text('{"status":"ok","covered":100,"total":100}')
            (output / "report.xml").write_text("stale report")
            exit_code = run.measure(self.manifest["modules"][0], "base", source, output)
            result = json.loads((output / "result.json").read_text())
            self.assertEqual(exit_code, 0)
            self.assertEqual(result["status"], "absent")
            self.assertIsNone(result["covered"])
            self.assertFalse((output / "report.xml").exists())


if __name__ == "__main__":
    unittest.main()
