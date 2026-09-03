#!/usr/bin/env python3
"""Resolve a GitHub.com identity and commit the index with process-local attribution."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def run(args, **kwargs):
    result = subprocess.run(args, text=True, encoding="utf-8", capture_output=True, **kwargs)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or f"{args[0]} failed")
    return result.stdout


def identity_field(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"Missing {label}; ask the person to supply --{label}.")
    if value != value.strip() or any(ord(c) < 32 or ord(c) == 127 or c in "<>" for c in value):
        raise ValueError(f"Invalid {label}: whitespace at edges, controls, and angle brackets are not allowed.")
    if label == "email" and (value.count("@") != 1 or any(c.isspace() for c in value)
                             or not all(value.split("@"))):
        raise ValueError("Invalid email: supply a single account email address.")
    return value


def resolve_identity(login, name=None, email=None, noreply=False):
    login = login.removeprefix("@")
    if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?", login):
        raise ValueError("Supply a GitHub login, not a URL, numeric account ID, or shell expression.")
    if email is not None and noreply:
        raise ValueError("--email and --noreply are mutually exclusive.")
    profile = json.loads(run(["gh", "api", "--hostname", "github.com", f"users/{login}"]))
    canonical = profile.get("login", "")
    if canonical.casefold() != login.casefold() or profile.get("type") != "User":
        raise ValueError("The response must identify the requested personal GitHub user.")
    account_id = profile.get("id")
    if type(account_id) is not int or account_id <= 0:
        raise ValueError("GitHub did not return a valid numeric account ID.")

    selected_name = identity_field(name if name is not None else profile.get("name"), "name")
    if email is not None:
        selected_email, email_source = email, "user-supplied"
    elif profile.get("email") and not noreply:
        selected_email, email_source = profile["email"], "github-public-profile"
    else:
        created_date = (profile.get("created_at") or "")[:10]
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", created_date) or created_date <= "2017-07-18":
            raise ValueError("Noreply format is uncertain for this account. Ask the person for their exact GitHub commit email and pass --email.")
        selected_email = f"{account_id}+{canonical}@users.noreply.github.com"
        email_source = "github-id-noreply-derived"
    selected_email = identity_field(selected_email, "email")
    return {
        "login": canonical,
        "id": account_id,
        "name": selected_name,
        "email": selected_email,
        "name_source": "user-supplied" if name is not None else "github-public-profile",
        "email_source": email_source,
        "public_name": profile.get("name"),
        "public_email": profile.get("email"),
        "profile_url": f"https://github.com/{canonical}",
    }


def create_commit(identity, message_file, dry_run=False):
    message_path = Path(message_file).resolve(strict=True)
    message = message_path.read_text(encoding="utf-8")
    if not message.strip() or len(message.strip().splitlines()) != 1 or "\x00" in message:
        raise ValueError("The message file must contain one non-empty subject line.")
    branch = run(["git", "symbolic-ref", "--quiet", "--short", "HEAD"]).strip()
    for state in ("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply"):
        state_path = run(["git", "rev-parse", "--git-path", state]).strip()
        if Path(state_path).exists():
            raise ValueError("Finish the in-progress merge/rebase/cherry-pick/revert before creating a new ordinary commit.")
    staged = subprocess.run(["git", "diff", "--cached", "--quiet"], capture_output=True)
    if staged.returncode == 0:
        raise ValueError("No staged changes. Stage one reviewed logical unit first.")
    if staged.returncode != 1:
        raise RuntimeError("Unable to inspect the staged changes.")
    run(["git", "diff", "--cached", "--check"])
    paths = run(["git", "diff", "--cached", "--name-only", "-z"]).rstrip("\0").split("\0")
    if dry_run:
        return {"dry_run": True, "branch": branch, "identity": identity, "staged_paths": paths}

    commit_env = os.environ.copy()
    for role in ("AUTHOR", "COMMITTER"):
        commit_env[f"GIT_{role}_NAME"] = identity["name"]
        commit_env[f"GIT_{role}_EMAIL"] = identity["email"]
        commit_env.pop(f"GIT_{role}_DATE", None)
    output = run([
        "git", "commit", "--file", str(message_path),
        "--author", f"{identity['name']} <{identity['email']}>",
    ], env=commit_env)
    print(output.rstrip(), file=sys.stderr)
    fields = run(["git", "show", "-s", "--format=%H%x00%an%x00%ae%x00%cn%x00%ce", "HEAD"]).rstrip("\n").split("\0")
    expected = [identity["name"], identity["email"]] * 2
    if len(fields) != 5 or fields[1:] != expected:
        raise RuntimeError(f"Commit {fields[0]} already exists, but identity verification failed. Inspect it before any retry; no history was rewritten.")
    return {"sha": fields[0], "author": fields[1:3], "committer": fields[3:5], "login": identity["login"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for command in ("resolve", "commit"):
        sub = subparsers.add_parser(command)
        sub.add_argument("login", help="GitHub.com login requested by the person responsible for the code")
        sub.add_argument("--name", help="Person-supplied commit name, if different from or absent on the public profile")
        email_group = sub.add_mutually_exclusive_group()
        email_group.add_argument("--email", help="Person-supplied email attached to their GitHub account")
        email_group.add_argument("--noreply", action="store_true", help="Prefer the documented GitHub noreply address")
        if command == "commit":
            sub.add_argument("--message-file", required=True, help="UTF-8 file containing one commit subject")
            sub.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        identity = resolve_identity(args.login, args.name, args.email, args.noreply)
        result = identity if args.command == "resolve" else create_commit(identity, args.message_file, args.dry_run)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, ValueError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
