#!/usr/bin/env python3
"""Print the read-only Vault policy for one Config Server environment."""
import argparse
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--profile", choices=("local", "prod"), required=True)
parser.add_argument("--backend", default="secret")
args = parser.parse_args()
if not re.fullmatch(r"[a-z][a-z0-9-]*", args.backend):
    parser.error("Use a single KV mount name")
for service in "gateway authorization user team channel project roadmap notification preference chat voice".split():
    for suffix in ("", "/" + args.profile):
        print(f'path "{args.backend}/data/cowork-{service}{suffix}" {{ capabilities = ["read"] }}')
print('path "auth/token/lookup-self" { capabilities = ["read"] }')
print('path "auth/token/revoke-self" { capabilities = ["update"] }')
