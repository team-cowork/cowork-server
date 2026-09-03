#!/usr/bin/env python3
"""Offline release boundary and body-update checks; no live writes."""

import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release_notes as helper


RELEASE = {
    "id": 10, "tag_name": "v2", "name": "Version 2", "body": "Original notes\n",
    "target_commitish": "main", "draft": False, "prerelease": False,
    "published_at": "2026-09-01T00:00:00Z", "updated_at": "2026-09-01T00:00:00Z",
    "html_url": "https://github.com/team/repo/releases/tag/v2", "assets": [{"id": 55}],
}


def pr(number, sha, merged=True):
    return {"number": number, "title": f"PR {number}", "body": "Description", "html_url": f"https://github.com/team/repo/pull/{number}", "merged_at": "2026-09-01T00:00:00Z" if merged else None, "merge_commit_sha": sha, "base": {"ref": "main"}, "head": {"ref": "develop"}}


class CollectionTests(unittest.TestCase):
    def test_paginated_ancestry_excludes_unmerged_and_out_of_range_prs(self):
        def response(endpoint, paginate=False):
            if "/releases/tags/" in endpoint:
                return RELEASE
            if "/commits/" in endpoint:
                return {"sha": "target"}
            if endpoint.endswith("/pulls/1"):
                return pr(1, "base")
            if endpoint.endswith("/pulls/4"):
                return pr(4, "target")
            self.assertTrue(paginate)
            if "/compare/" in endpoint:
                return [{"status": "ahead", "total_commits": 2, "commits": [{"sha": sha, "html_url": f"https://github.com/team/repo/commit/{sha}", "commit": {"message": sha}}]} for sha in ("feature", "target")]
            return [[pr(2, "feature"), pr(3, "unrelated")], [pr(4, "target"), pr(5, "feature", merged=False)]]

        with tempfile.TemporaryDirectory() as directory, patch.object(helper, "api", side_effect=response):
            result = helper.collect("team/repo", "v2", 1, 4, Path(directory) / "snapshot")
            context = json.loads(Path(result["context"]).read_text())
            self.assertEqual([item["number"] for item in context["prs"]], [2, 4])
            self.assertEqual(context["base_sha"], "base")
            self.assertEqual(context["target_sha"], "target")
            self.assertEqual((Path(result["context"]).parent / "original-notes.md").read_text(), RELEASE["body"])

    def test_incomplete_comparison_stops_before_snapshot(self):
        replies = [RELEASE, {"sha": "target"}, pr(1, "base"), [{"status": "ahead", "total_commits": 251, "commits": []}]]
        with tempfile.TemporaryDirectory() as directory, patch.object(helper, "api", side_effect=replies):
            output = Path(directory) / "snapshot"
            with self.assertRaisesRegex(ValueError, "pagination"):
                helper.collect("team/repo", "v2", 1, None, output)
            self.assertFalse(output.exists())


class UpdateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.context = self.root / "context.json"
        self.context.write_text(json.dumps({"schema_version": 1, "repo": "team/repo", "release": RELEASE, "target_sha": "target"}))
        self.notes = self.root / "notes.md"
        self.body = "## Changes\n\n- A user-visible improvement.\n"
        self.notes.write_text(self.body)

    def tearDown(self):
        self.temporary.cleanup()

    def test_update_uses_notes_file_only_and_verifies_body(self):
        updated = {**RELEASE, "body": self.body, "updated_at": "later"}
        with patch.object(helper, "api", side_effect=[RELEASE, {"sha": "target"}, updated]), patch.object(helper, "gh", return_value="") as write:
            result = helper.update(self.context, self.notes)
        self.assertTrue(result["updated"])
        write.assert_called_once_with(["release", "edit", "v2", "--repo", "github.com/team/repo", "--notes-file", str(self.notes.resolve())])

    def test_concurrent_body_edit_or_retag_prevents_write(self):
        for current, sha in (({**RELEASE, "body": "Another editor"}, "target"), (RELEASE, "different")):
            with self.subTest(sha=sha), patch.object(helper, "api", side_effect=[current, {"sha": sha}]), patch.object(helper, "gh") as write:
                with self.assertRaises(ValueError):
                    helper.update(self.context, self.notes)
                write.assert_not_called()

    def test_metadata_change_in_readback_is_reported(self):
        updated = {**RELEASE, "body": self.body, "draft": True}
        with patch.object(helper, "api", side_effect=[RELEASE, {"sha": "target"}, updated]), patch.object(helper, "gh", return_value=""):
            with self.assertRaisesRegex(RuntimeError, "read-back"):
                helper.update(self.context, self.notes)

    def test_uncertain_cli_result_is_read_back_without_retry(self):
        updated = {**RELEASE, "body": self.body}
        with patch.object(helper, "api", side_effect=[RELEASE, {"sha": "target"}, updated]), patch.object(helper, "gh", side_effect=RuntimeError("connection lost")) as write:
            result = helper.update(self.context, self.notes)
        self.assertTrue(result["updated"])
        self.assertIn("warning", result)
        self.assertEqual(write.call_count, 1)

    def test_matching_body_is_noop(self):
        self.notes.write_text(RELEASE["body"])
        with patch.object(helper, "api", side_effect=[copy.deepcopy(RELEASE), {"sha": "target"}]), patch.object(helper, "gh") as write:
            self.assertFalse(helper.update(self.context, self.notes)["updated"])
            write.assert_not_called()


if __name__ == "__main__":
    unittest.main()
