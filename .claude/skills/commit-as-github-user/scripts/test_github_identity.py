#!/usr/bin/env python3
"""Offline identity checks and real commits in disposable Git repositories."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import github_identity as helper


PROFILE = {
    "login": "TeamMember", "id": 123456, "type": "User", "name": "Team Member",
    "email": "member@example.com", "created_at": "2020-01-01T00:00:00Z",
}


class ResolutionTests(unittest.TestCase):
    def resolve(self, **changes):
        with patch.object(helper, "run", return_value=json.dumps({**PROFILE, **changes})) as request:
            identity = helper.resolve_identity("@teammember")
        self.assertEqual(request.call_args.args[0], ["gh", "api", "--hostname", "github.com", "users/teammember"])
        return identity

    def test_public_profile_preserves_canonical_name_and_email(self):
        identity = self.resolve()
        self.assertEqual(identity["login"], "TeamMember")
        self.assertEqual(identity["name"], "Team Member")
        self.assertEqual(identity["email"], "member@example.com")

    def test_private_email_uses_labelled_modern_noreply(self):
        identity = self.resolve(email=None)
        self.assertEqual(identity["email"], "123456+TeamMember@users.noreply.github.com")
        self.assertEqual(identity["email_source"], "github-id-noreply-derived")

    def test_missing_name_legacy_email_and_organization_stop(self):
        for changes in ({"name": None}, {"email": None, "created_at": "2017-07-18T12:00:00Z"}, {"type": "Organization"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.resolve(**changes)

    def test_person_supplied_overrides_do_not_claim_profile_verification(self):
        with patch.object(helper, "run", return_value=json.dumps({**PROFILE, "name": None, "email": None})):
            identity = helper.resolve_identity("TeamMember", name="Renée Member", email="123456+TeamMember@users.noreply.github.com")
        self.assertEqual(identity["name_source"], "user-supplied")
        self.assertEqual(identity["email_source"], "user-supplied")

    def test_invalid_input_and_network_failure_do_not_fall_back(self):
        with patch.object(helper, "run") as request:
            with self.assertRaises(ValueError):
                helper.resolve_identity("https://github.com/TeamMember")
            request.assert_not_called()
        with patch.object(helper, "run", side_effect=RuntimeError("API unavailable")):
            with self.assertRaisesRegex(RuntimeError, "API unavailable"):
                helper.resolve_identity("TeamMember")
        with self.assertRaises(ValueError):
            helper.identity_field("Member\nOther", "name")


class CommitTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.previous_cwd = Path.cwd()
        self.global_config = self.root / "global.gitconfig"
        self.global_config.write_text('[user]\n name = Global Owner\n email = global@example.com\n', encoding="utf-8")
        self.environment = patch.dict(os.environ, {
            "GIT_CONFIG_GLOBAL": str(self.global_config), "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_AUTHOR_NAME": "Inherited Owner", "GIT_AUTHOR_EMAIL": "owner@example.com",
            "GIT_COMMITTER_NAME": "Inherited Owner", "GIT_COMMITTER_EMAIL": "owner@example.com",
        })
        self.environment.start()
        os.chdir(self.root)
        self.git("init", "-q", "-b", "feature/test")
        for key, value in {"user.name": "Computer Owner", "user.email": "computer@example.com", "author.name": "Configured Author", "committer.email": "configured@example.com", "commit.gpgsign": "false"}.items():
            self.git("config", key, value)
        self.config_before = (self.root / ".git/config").read_bytes()
        self.global_before = self.global_config.read_bytes()
        self.environment_before = dict(os.environ)
        (self.root / "message.txt").write_text("test(identity): fixture\n", encoding="utf-8")
        (self.root / "feature.txt").write_text("first behavior\n", encoding="utf-8")
        self.git("add", "--", "feature.txt")
        self.identity = {"login": "TeamMember", "name": 'Renée "Member" $(echo wrong) `whoami`', "email": "member@example.com"}

    def tearDown(self):
        os.chdir(self.previous_cwd)
        self.environment.stop()
        self.temporary.cleanup()

    def git(self, *args):
        return subprocess.run(["git", *args], text=True, capture_output=True, check=True).stdout

    def test_each_commit_uses_both_identities_without_configuration_or_environment_changes(self):
        for content in ("first behavior\n", "second behavior\n"):
            (self.root / "feature.txt").write_text(content, encoding="utf-8")
            self.git("add", "--", "feature.txt")
            result = helper.create_commit(self.identity, "message.txt")
            self.assertEqual(result["author"], [self.identity["name"], self.identity["email"]])
            self.assertEqual(result["committer"], result["author"])
        self.assertEqual(self.git("rev-list", "--count", "HEAD").strip(), "2")
        self.assertEqual((self.root / ".git/config").read_bytes(), self.config_before)
        self.assertEqual(self.global_config.read_bytes(), self.global_before)
        self.assertEqual(dict(os.environ), self.environment_before)
        self.assertEqual(self.git("ls-files").strip(), "feature.txt")

    def test_dry_run_does_not_create_commit(self):
        result = helper.create_commit(self.identity, "message.txt", dry_run=True)
        self.assertTrue(result["dry_run"])
        self.assertEqual(result["staged_paths"], ["feature.txt"])
        self.assertNotEqual(subprocess.run(["git", "rev-parse", "--verify", "HEAD"], capture_output=True).returncode, 0)

    def test_failing_hook_is_not_bypassed(self):
        hook = self.root / ".git/hooks/pre-commit"
        hook.write_text("#!/bin/sh\necho 'fixture hook rejection' >&2\nexit 1\n", encoding="utf-8")
        hook.chmod(0o755)
        with self.assertRaisesRegex(RuntimeError, "fixture hook rejection"):
            helper.create_commit(self.identity, "message.txt")
        self.assertNotEqual(subprocess.run(["git", "rev-parse", "--verify", "HEAD"], capture_output=True).returncode, 0)


if __name__ == "__main__":
    unittest.main()
