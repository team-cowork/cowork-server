#!/usr/bin/env python3
"""Generate bootstrap credentials without printing secrets or replacing an existing directory."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets

SERVICES = "gateway authorization user team channel project roadmap notification preference chat voice monitoring".split()


def write_private(path, value):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", newline="\n") as stream:
        stream.write(value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("local", "prod"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--suffix", default="", help="Optional account generation, e.g. -v2 for rotation")
    args = parser.parse_args()
    if not re.fullmatch(r"(?:-[a-z0-9-]{1,20})?", args.suffix):
        parser.error("Use a lowercase account suffix beginning with a hyphen")
    args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    accounts, compose = [], []
    for service in SERVICES:
        username = f"cowork-{service}{args.suffix}"
        password = secrets.token_urlsafe(32)
        salt = secrets.token_bytes(16)
        digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 310000, dklen=32)
        accounts.append({"username": username, "passwordHash": (salt + digest).hex(),
                         "application": "monitoring" if service == "monitoring" else f"cowork-{service}",
                         "profile": args.profile})
        values = {"CONFIG_CLIENT_USERNAME": username, "CONFIG_CLIENT_PASSWORD": password}
        write_private(args.output / f"{service}.env", "".join(f"{key}='{value}'\n" for key, value in values.items()))
        write_private(args.output / f"{service}.json", json.dumps(values, indent=2) + "\n")
        compose.extend(f"CONFIG_CLIENT_{service.upper()}_{key}='{value}'\n"
                       for key, value in (("USERNAME", username), ("PASSWORD", password)))
        if service == "monitoring":
            # Docker secrets are mounted read-only; do not mount the full credential directory.
            write_private(args.output / "monitoring-username", username)
            write_private(args.output / "monitoring-password", password)
    document = json.dumps(accounts, separators=(",", ":"))
    write_private(args.output / "accounts.json", document + "\n")
    server_env = f"CONFIG_SERVER_ACCOUNTS_JSON='{document}'\n"
    write_private(args.output / "config.env", server_env)
    write_private(args.output / "compose.env", server_env + "".join(compose))
    print(f"Created credentials for {len(accounts)} accounts in {args.output}; keep these files private")


if __name__ == "__main__":
    main()
