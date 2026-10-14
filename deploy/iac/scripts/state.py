#!/usr/bin/env python3
"""Serialize a Grafana apply and persist its encrypted state outside the managed VMs."""
import argparse
import base64
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import uuid

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parents[3]
BRANCH = "infra-state"
LOCK = "infra-state-lock"
STATE_FILE = "grafana.tfstate.aesgcm"


def command(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def api(repo, endpoint, method="GET", payload=None, allow_missing=False):
    args = ["gh", "api", f"repos/{repo}/{endpoint}", "--method", method]
    if payload is not None:
        args += ["--input", "-"]
    result = subprocess.run(args, input=json.dumps(payload) if payload is not None else None,
                            text=True, capture_output=True)
    if result.returncode:
        if allow_missing and "HTTP 404" in result.stderr:
            return None
        raise RuntimeError(f"GitHub state operation failed: {method} {endpoint}")
    return json.loads(result.stdout) if result.stdout.strip() else None


def git(directory, args, env, capture=False):
    return command(["git", "-C", str(directory), *args], env=env, text=True,
                   stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                   stderr=subprocess.PIPE)


def envelope(data, key, aad):
    nonce = os.urandom(12)
    return json.dumps({"version": 1, "nonce": base64.b64encode(nonce).decode(),
                       "ciphertext": base64.b64encode(AESGCM(key).encrypt(nonce, data, aad)).decode()},
                      separators=(",", ":")).encode() + b"\n"


def decrypt(data, key, aad):
    value = json.loads(data)
    if set(value) != {"version", "nonce", "ciphertext"} or value["version"] != 1:
        raise ValueError("Unsupported encrypted state format")
    return AESGCM(key).decrypt(base64.b64decode(value["nonce"], validate=True),
                               base64.b64decode(value["ciphertext"], validate=True), aad)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--initialize", action="store_true", help="Allow the first state branch creation")
    parser.add_argument("--recovery-file", type=Path, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    child_args = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not child_args:
        parser.error("A Terraform apply command is required")
    repo = os.environ["GITHUB_REPOSITORY"]
    import re
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Invalid repository")
    key = base64.b64decode(os.environ["IAC_STATE_ENCRYPTION_KEY"].strip(), validate=True)
    if len(key) != 32:
        raise ValueError("State encryption requires a 32-byte key")
    token = os.environ.get("GH_TOKEN") or command(["gh", "auth", "token"], capture_output=True, text=True).stdout.strip()
    # Authentication is process-local; never persist it in repository configuration.
    env = {**os.environ, "GH_TOKEN": token, "GIT_CONFIG_COUNT": "1",
           "GIT_CONFIG_KEY_0": "http.https://github.com/.extraheader",
           "GIT_CONFIG_VALUE_0": "AUTHORIZATION: basic " + base64.b64encode(("x-access-token:" + token).encode()).decode()}
    aad = f"cowork-terraform-state-v1:{repo}:grafana".encode()
    os.umask(0o077)
    args.recovery_file.parent.mkdir(parents=True, exist_ok=True)
    if args.recovery_file.exists():
        raise ValueError("Recovery output already exists; inspect it before another apply")
    head = api(repo, "git/ref/heads/main")["object"]["sha"]
    # A unique tag object identifies the owner, even for two runs of the same source SHA.
    lease = api(repo, "git/tags", "POST", {
        "tag": LOCK, "message": "Terraform state lease " + str(uuid.uuid4()),
        "object": head, "type": "commit",
        "tagger": {"name": "박채은", "email": "189111559+chaeeun-09@users.noreply.github.com"},
    })["sha"]
    # Reference creation is atomic. A stale lock is never expired automatically.
    api(repo, "git/refs", "POST", {"ref": f"refs/tags/{LOCK}", "sha": lease})
    persisted = True
    status = 1
    try:
        remote = api(repo, f"git/ref/heads/{BRANCH}", allow_missing=True)
        if remote is None and not args.initialize:
            raise RuntimeError("State branch is absent; first initialization must be explicit")
        with tempfile.TemporaryDirectory(prefix="cowork-iac-state-") as temp:
            directory = Path(temp)
            checkout = directory / "repository"
            checkout.mkdir()
            git(checkout, ["init", "-b", BRANCH], env)
            git(checkout, ["remote", "add", "origin", f"https://github.com/{repo}.git"], env)
            if remote is not None:
                git(checkout, ["fetch", "--depth=1", "origin", BRANCH], env)
                git(checkout, ["reset", "--hard", "FETCH_HEAD"], env)
            encrypted = checkout / STATE_FILE
            state = directory / "grafana.tfstate"
            before = None
            if remote is not None:
                if not encrypted.is_file():
                    raise RuntimeError("Existing state branch has no encrypted state; refusing an empty apply")
                before = decrypt(encrypted.read_bytes(), key, aad)
                json.loads(before)
                state.write_bytes(before)
            child_env = {**env, "TF_STATE_PATH": str(state), "TF_DATA_DIR": str(directory / "terraform-data")}
            persisted = False
            process = subprocess.Popen(child_args, env=child_env, start_new_session=True)

            def stop(signum, frame):
                os.killpg(process.pid, signal.SIGTERM)
                raise KeyboardInterrupt

            handlers = {sig: signal.signal(sig, stop) for sig in (signal.SIGTERM, signal.SIGINT)}
            try:
                status = process.wait()
            except KeyboardInterrupt:
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                status = 130
            finally:
                for sig, handler in handlers.items():
                    signal.signal(sig, handler)
                # Partial applies also update state. Persist before unlocking, even on failure.
                if state.exists():
                    current = state.read_bytes()
                    data = envelope(current, key, aad)
                    with args.recovery_file.open("xb") as recovery:
                        recovery.write(data)
                    parsed = json.loads(current)
                    if not isinstance(parsed, dict) or parsed.get("version") != 4:
                        raise RuntimeError("Terraform produced an invalid state; remote lock retained")
                    if current != before:
                        encrypted.write_bytes(data)
                        git(checkout, ["add", "--", STATE_FILE], env)
                        git(checkout, ["diff", "--cached", "--check"], env)
                        message = directory / "commit-message.txt"
                        message.write_text("ci/cd(monitoring): Terraform 상태 갱신\n")
                        helper = ROOT / ".agents/skills/commit-as-github-user/scripts/github_identity.py"
                        try:
                            command([sys.executable, str(helper), "commit", "chaeeun-09", "--message-file", str(message)],
                                    cwd=checkout, env=env)
                        finally:
                            message.unlink(missing_ok=True)
                        git(checkout, ["push", "origin", f"HEAD:refs/heads/{BRANCH}"], env)
                    persisted = True
                    args.recovery_file.unlink()
                    print("[iac-state] Encrypted state persisted; no plaintext state was published")
                else:
                    raise RuntimeError("Apply produced no state; remote lock retained for inspection")
    finally:
        if persisted:
            current_lock = api(repo, f"git/ref/tags/{LOCK}", allow_missing=True)
            if current_lock is not None and current_lock["object"]["sha"] == lease:
                api(repo, f"git/refs/tags/{LOCK}", "DELETE")
        else:
            print(f"[iac-state] State persistence failed; retain remote lock and encrypted recovery file: {args.recovery_file}", file=sys.stderr)
    return status


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"[iac-state] {error.__class__.__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
