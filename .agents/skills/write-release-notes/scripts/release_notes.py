#!/usr/bin/env python3
"""Collect release-PR evidence, then update only an existing release's notes via gh."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import quote


def gh(args):
    result = subprocess.run(["gh", *args], text=True, encoding="utf-8", capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "GitHub CLI request failed")
    return result.stdout


def api(endpoint, paginate=False):
    args = ["api", "--hostname", "github.com", endpoint]
    if paginate:
        args.extend(["--paginate", "--slurp"])
    return json.loads(gh(args))


def repo_path(repo):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Repository must be OWNER/REPO on github.com.")
    return f"repos/{repo}"


def summarize_pr(pr):
    return {
        "number": pr["number"], "title": pr["title"], "body": pr.get("body") or "",
        "url": pr["html_url"], "merged_at": pr["merged_at"],
        "merge_sha": pr["merge_commit_sha"], "base": pr["base"]["ref"], "head": pr["head"]["ref"],
    }


def collect(repo, tag, previous_pr, release_pr, output):
    prefix = repo_path(repo)
    destination = Path(output).resolve()
    if destination.exists():
        raise ValueError("Output directory already exists; choose a new directory to preserve earlier drafts.")
    release = api(f"{prefix}/releases/tags/{quote(tag, safe='')}")
    if release["tag_name"] != tag:
        raise ValueError("The release tag does not match the requested tag.")
    target_sha = api(f"{prefix}/commits/{quote(tag, safe='')}")["sha"]
    previous = api(f"{prefix}/pulls/{previous_pr}")
    if not previous.get("merged_at") or not previous.get("merge_commit_sha"):
        raise ValueError("The previous release PR must be merged.")
    current = None
    if release_pr:
        current = api(f"{prefix}/pulls/{release_pr}")
        if (not current.get("merged_at") or current.get("merge_commit_sha") != target_sha
                or current["base"]["ref"] != previous["base"]["ref"]):
            raise ValueError("The current release PR must be merged into the same base branch and match the target tag's commit.")
    base_sha = previous["merge_commit_sha"]
    pages = api(f"{prefix}/compare/{base_sha}...{target_sha}?per_page=100", paginate=True)
    if not pages or pages[0]["status"] != "ahead":
        raise ValueError("Target must be strictly ahead of the previous release PR; inspect the ancestry.")
    commits = [commit for page in pages for commit in page["commits"]]
    shas = {commit["sha"] for commit in commits}
    if len(commits) != len(shas) or len(shas) != pages[0]["total_commits"]:
        raise ValueError("Incomplete or inconsistent commit pagination; do not publish from this inventory.")
    pr_pages = api(f"{prefix}/pulls?state=closed&per_page=100", paginate=True)
    prs = sorted(
        [summarize_pr(pr) for page in pr_pages for pr in page
         if pr.get("merged_at") and pr.get("merge_commit_sha") in shas],
        key=lambda pr: (pr["merged_at"], pr["number"]),
    )
    if current and not any(pr["number"] == current["number"] for pr in prs):
        raise ValueError("The current release PR is absent from the inventory; retry collection after inspecting API consistency.")
    snapshot = {
        "schema_version": 1, "repo": repo, "release": release,
        "previous_release_pr": summarize_pr(previous),
        "current_release_pr": summarize_pr(current) if current else None,
        "base_sha": base_sha, "target_sha": target_sha,
        "comparison_url": f"https://github.com/{repo}/compare/{base_sha}...{target_sha}",
        "prs": prs,
        "commits": [{"sha": c["sha"], "url": c["html_url"], "message": c["commit"]["message"]} for c in commits],
        "coverage_note": "Merge-SHA inventory: inspect direct commits, squash/rebase/cherry-pick history, enclosing release PRs, and net reverts before drafting.",
    }
    destination.mkdir(parents=True)
    (destination / "context.json").write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (destination / "original-notes.md").write_text(release.get("body") or "", encoding="utf-8")
    return {"context": str(destination / "context.json"), "commits": len(commits), "prs": len(prs), "comparison_url": snapshot["comparison_url"]}


def release_state(release):
    # Fields outside the body must survive the edit. Assets are identified by stable IDs.
    keys = ("id", "tag_name", "name", "target_commitish", "draft", "prerelease", "published_at")
    return {**{key: release.get(key) for key in keys}, "asset_ids": sorted(asset["id"] for asset in release.get("assets", []))}


def update(context_file, notes_file):
    context = json.loads(Path(context_file).read_text(encoding="utf-8"))
    if context.get("schema_version") != 1:
        raise ValueError("Unsupported context schema; collect a fresh snapshot.")
    repo = context["repo"]
    prefix = repo_path(repo)
    original = context["release"]
    tag = original["tag_name"]
    notes_path = Path(notes_file).resolve(strict=True)
    with notes_path.open(encoding="utf-8", newline="") as source:
        notes = source.read()
    if not notes.strip():
        raise ValueError("Release notes must not be empty.")
    endpoint = f"{prefix}/releases/tags/{quote(tag, safe='')}"
    current = api(endpoint)
    current_sha = api(f"{prefix}/commits/{quote(tag, safe='')}")["sha"]
    if current_sha != context["target_sha"] or release_state(current) != release_state(original):
        raise ValueError("Release metadata or tag changed after collection. Inspect and collect a fresh snapshot.")
    if current.get("body") != original.get("body") or current.get("updated_at") != original.get("updated_at"):
        raise ValueError("The release changed after collection. Reconcile the current notes before retrying.")
    if current.get("body") == notes:
        return {"updated": False, "url": current["html_url"], "reason": "Body already matches."}

    write_error = None
    try:
        gh(["release", "edit", tag, "--repo", f"github.com/{repo}", "--notes-file", str(notes_path)])
    except RuntimeError as error:
        write_error = error
    try:
        verified = api(endpoint)
    except (RuntimeError, OSError, ValueError) as error:
        raise RuntimeError("Write outcome is uncertain. Read the release before retrying; no automatic retry was made.") from error
    if verified.get("body") != notes or release_state(verified) != release_state(original):
        detail = f" CLI reported: {write_error}" if write_error else ""
        raise RuntimeError("Release read-back did not match the intended body and metadata. Inspect before retrying." + detail)
    result = {"updated": True, "url": verified["html_url"], "backup": str(Path(context_file).resolve().parent / "original-notes.md")}
    if write_error:
        result["warning"] = "CLI reported an error, but read-back verified the intended body and unchanged metadata; no retry was needed."
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    collector = subparsers.add_parser("collect", help="Read-only: save PR evidence and the existing release body")
    collector.add_argument("--repo", required=True)
    collector.add_argument("--tag", required=True)
    collector.add_argument("--previous-pr", required=True, type=int)
    collector.add_argument("--release-pr", type=int)
    collector.add_argument("--output", required=True, help="A new directory for the snapshot and original body")
    updater = subparsers.add_parser("update", help="Edit only the existing release body, then verify it")
    updater.add_argument("--context", required=True)
    updater.add_argument("--notes-file", required=True)
    args = parser.parse_args()
    try:
        if args.command == "collect":
            result = collect(args.repo, args.tag, args.previous_pr, args.release_pr, args.output)
        else:
            result = update(args.context, args.notes_file)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
