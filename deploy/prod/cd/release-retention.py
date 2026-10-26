#!/usr/bin/env python3
"""Preview, or with --apply remove, old releases, settings snapshots and unused images on this VM.

Kept: the newest 5 unreferenced releases, snapshots per target and unused images per repository,
and anything named by a container (running, stopped, *-previous or *-candidate) or a *-runtime.json
record. Generated Prometheus configuration lives in the monitoring snapshots and follows the same policy.
remote-deploy.sh runs this with --apply after each successful deploy.
Run on the VM: python3 ~/.local/share/cowork/releases/<sha>/deploy/prod/cd/release-retention.py [--apply]
"""
import argparse
import fcntl
import json
import re
import shutil
import subprocess
from pathlib import Path

KEEP = 5
SHA = re.compile(r"[0-9a-f]{40}")


def select(entries, corpus, keep=KEEP):
    """entries: (path, mtime, token). Delete all but the newest `keep` whose token no reference names."""
    unreferenced = [entry for entry in sorted(entries, key=lambda entry: entry[1], reverse=True)
                    if entry[2] not in corpus]
    return [path for path, _, _ in unreferenced[keep:]]


def docker(*arguments):
    return subprocess.run(["docker", *arguments], capture_output=True, text=True, check=True).stdout


def lock(path):
    stream = path.open("a")
    try:
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit(f"[retention] A deployment holds {path}; retry after it finishes") from None
    return stream


def modified(path):
    marker = path / ".source-sha"
    return (marker if marker.is_file() else path).lstat().st_mtime


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apply", action="store_true", help="Delete the previewed items")
    apply = parser.parse_args().apply
    root, state = Path.home() / ".local/share/cowork", Path.home() / ".local/state/cowork"
    (root / "releases").mkdir(parents=True, exist_ok=True)
    # remote-deploy.sh shares retention.lock for its whole run; deploy.sh holds its service lock.
    locks = [lock(root / "retention.lock"), lock(root / "source.lock")]
    locks += [lock(path) for path in sorted(state.glob("*.lock"))]

    containers = docker("container", "ls", "--all", "--quiet", "--no-trunc").split()
    if apply and not containers:
        # A deploy VM always runs containers; none usually means the wrong Docker context, not "nothing is in use".
        raise SystemExit("[retention] Docker reports no containers; refusing to delete")
    inspected = docker("container", "inspect", *containers) if containers else "[]"
    corpus = "\n".join([inspected, str(Path(__file__).resolve())] +
                       [path.read_text() for path in state.glob("*-runtime.json")])

    releases = [(path, modified(path), path.name) for path in (root / "releases").iterdir()
                if SHA.fullmatch(path.name) and path.is_dir() and not path.is_symlink()]
    directories = select(releases, corpus)
    settings = state / "settings"
    for target in sorted(settings.iterdir()) if settings.is_dir() else []:
        if target.is_dir() and not target.is_symlink():
            snapshots = [(path, path.lstat().st_mtime, f"{target.name}/{path.name}") for path in target.iterdir()
                         if path.is_dir() and not path.is_symlink()]
            directories += select(snapshots, corpus)

    used = {container["Image"] for container in json.loads(inspected)}
    unused = [image for image in dict.fromkeys(docker("image", "ls", "--quiet", "--no-trunc").split())
              if image not in used]
    # Previous image versions are rollback material: keep the newest unused ones per repository too.
    groups = {}
    for image in json.loads(docker("image", "inspect", *unused)) if unused else []:
        repository = image["RepoTags"][0].rsplit(":", 1)[0] if image["RepoTags"] else ""
        groups.setdefault(repository, []).append((image, image["Created"], image["Id"]))
    images = [image for group in groups.values() for image in select(group, corpus)]

    failed = False
    for path in directories:
        print(f"[retention] {'delete' if apply else 'would delete'} {path}")
        if apply:
            shutil.rmtree(path)
    for image in images:
        names = image["RepoTags"] or [image["Id"]]
        print(f"[retention] {'delete' if apply else 'would delete'} image {' '.join(names)}")
        # Docker itself refuses images that any container still uses.
        if apply and subprocess.run(["docker", "image", "rm", *names]).returncode != 0:
            failed = True
    if not apply:
        print(f"[retention] Preview of {len(directories)} directories and {len(images)} images; "
              "re-run with --apply to delete")
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        raise SystemExit(f"[retention] {' '.join(error.cmd[:3])} failed: {error.stderr.strip()}") from None
    except OSError as error:
        raise SystemExit(f"[retention] {error}") from None
