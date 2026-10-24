#!/usr/bin/env python3
"""Check the deployed consumers before retiring declared Kafka groups; dry-run by default."""
import argparse
import json
import os
from pathlib import Path
import re
import runpy
import signal
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[3]
PREPARE = runpy.run_path(str(ROOT / "deploy/iac/scripts/prepare.py"))
VAULT = PREPARE["VAULT"]
SLOTS = PREPARE["SLOTS"]
require = VAULT["demand"]
BROKER_SCRIPT = ROOT / "deploy/prod/kafka/retire-consumer-groups.py"
CATALOG = BROKER_SCRIPT.with_name("retirements.json")

CONSUMER_CHECK = r'''
import json
import re
import subprocess
import urllib.error
import urllib.request


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def inspect():
    template = '\n'.join([
        '{{json .Id}}', '{{json .State.Running}}',
        '{{json (index .Config.Labels "cowork.release-sha")}}',
        '{{json (index .Config.Labels "cowork.health-base-url")}}',
        '{{json (index .Config.Labels "cowork.health-path")}}',
    ])
    result = subprocess.run(['docker', 'container', 'inspect', '--format', template,
                             'cowork-' + payload['service']],
                            capture_output=True, text=True, timeout=30)
    require(result.returncode == 0, 'Consumer container could not be inspected')
    try:
        values = [json.loads(line) for line in result.stdout.splitlines()]
    except ValueError:
        raise SystemExit('Invalid consumer inspection response') from None
    require(len(values) == 5, 'Incomplete consumer inspection response')
    identity, running, release, base, path = values
    require(isinstance(identity, str) and re.fullmatch('[0-9a-f]{64}', identity),
            'Invalid consumer container identity')
    require(running is True and release == payload['sha'],
            'Consumer is stopped or no longer runs this release; retirement refused')
    require(base == payload['health_base'] and path == payload['health_path'],
            'Consumer readiness endpoint differs from the reviewed slot')
    return values


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


before = inspect()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
try:
    with opener.open(payload['health_base'] + payload['health_path'], timeout=5) as response:
        require(response.status == 200, 'Consumer readiness did not return HTTP 200')
except (OSError, urllib.error.URLError):
    raise SystemExit('Consumer readiness failed; response body withheld') from None
require(inspect() == before, 'Consumer changed during readiness verification')
print('[retirement] ' + payload['service'] + ': release is running and ready', flush=True)
'''

BROKER_RUN = r'''
import json
import os
from pathlib import Path
import subprocess
import tempfile


with tempfile.TemporaryDirectory(prefix='cowork-kafka-retirement-') as directory:
    script = Path(directory) / 'retire-consumer-groups.py'
    catalog = Path(directory) / 'retirements.json'
    for path, content in ((script, payload['source']), (catalog, payload['catalog'])):
        with path.open('x') as stream:
            os.chmod(path, 0o600)
            stream.write(content)
    command = ['sudo', '-n', 'timeout', '--signal=TERM', '--kill-after=15s', '9m',
               'python3', str(script), '--catalog', str(catalog),
               '--service', payload['service']]
    if payload['apply']:
        command.append('--apply')
    result = subprocess.run(command, timeout=9 * 60 + 30, check=False)
raise SystemExit(result.returncode)
'''


def selected_consumers(raw, entries):
    plan = VAULT["parse_json"](raw)
    require(isinstance(plan, dict) and set(plan) == {"include"} and isinstance(plan["include"], list),
            "Applications must be the deployment plan include matrix")
    services = {entry["consumer_service"] for entry in entries}
    selected, targets = {}, set()
    for application in plan["include"]:
        require(isinstance(application, dict) and {"service", "target", "image_sha"} <= set(application),
                "Deployment entries require service, target and image_sha")
        service, target = application["service"], application["target"]
        require(all(isinstance(value, str) and VAULT["NAME"].fullmatch(value) for value in (service, target)),
                "Invalid deployed service or target")
        require(isinstance(application["image_sha"], str)
                and re.fullmatch(r"[0-9a-f]{40}", application["image_sha"]), "Invalid deployed image SHA")
        require(target not in targets, "Duplicate deployment target")
        targets.add(target)
        if service not in services:
            continue
        slot = SLOTS["load"](target)
        require(slot["kind"] == "application" and slot["service"] == service,
                "Retirement consumer must match its deployed application slot")
        container = slot["container"]
        host = container["bind_address"]
        if host == "0.0.0.0":
            host = "127.0.0.1"
        selected.setdefault(service, []).append({
            "target": target,
            "health_base": f"http://{host}:{container['host_port']}",
            "health_path": container["readiness"]["path"],
        })
    return selected


def require_release(sha):
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() == sha,
            "Checkout must match the selected immutable release")
    require(subprocess.run(["git", "diff", "--quiet", sha, "--", "deploy"], cwd=ROOT).returncode == 0,
            "Commit deployment source changes before running retirement")
    require(not subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "--", "deploy"], cwd=ROOT).strip(),
        "Untracked deployment sources cannot enter retirement")


def ssh_inputs(directory, selected):
    VAULT["authenticate_process"]("prod-iac")
    document = VAULT["read_document"]("iac/hosts")["data"]
    require(isinstance(document, dict) and set(document) == {"hosts"}
            and isinstance(document["hosts"], dict), "Invalid IaC host inventory")
    hosts = document["hosts"]
    require(isinstance(hosts.get("kafka"), dict) and hosts["kafka"].get("service") == "kafka",
            "Missing Kafka host")
    selected_hosts = {"kafka": hosts["kafka"]}
    for service, applications in selected.items():
        for application in applications:
            matches = [(host_id, entry) for host_id, entry in hosts.items()
                       if isinstance(entry, dict) and entry.get("slot") == application["target"]
                       and entry.get("service") == service]
            require(len(matches) == 1, "Each deployed consumer needs exactly one matching IaC host")
            host_id, entry = matches[0]
            application["host"] = host_id
            selected_hosts[host_id] = entry

    keys = directory / "keys"
    keys.mkdir(mode=0o700)
    known_hosts = directory / "known_hosts"
    config = ["Host *", "  BatchMode yes", "  StrictHostKeyChecking yes", "  IdentitiesOnly yes",
              "  IdentityAgent none", "  GlobalKnownHostsFile /dev/null", "  UpdateHostKeys no",
              "  ConnectTimeout 15", "  ServerAliveInterval 15", "  ServerAliveCountMax 4",
              "  UserKnownHostsFile " + PREPARE["ssh_quote"](known_hosts)]
    trusted_keys = []
    for host_id, entry in selected_hosts.items():
        ssh = PREPARE["validate_host"](host_id, entry)
        identity = keys / host_id
        PREPARE["write_private"](identity, ssh["key"].rstrip() + "\n")
        trusted_keys.append(ssh["known_hosts"].rstrip())
        config.extend([f"Host cowork-iac-{host_id}", f"  HostName {ssh['host']}", f"  Port {ssh['port']}",
                       f"  User {ssh['user']}", "  IdentityFile " + PREPARE["ssh_quote"](identity)])
    PREPARE["write_private"](known_hosts, "\n".join(trusted_keys) + "\n")
    path = directory / "ssh-config"
    PREPARE["write_private"](path, "\n".join(config) + "\n")
    return path


def remote(config, host, source, payload, timeout):
    script = "import json\npayload = json.loads(" + repr(json.dumps(payload)) + ")\n" + source
    env = {name: os.environ[name] for name in ("PATH", "HOME", "LANG", "LC_ALL") if name in os.environ}
    subprocess.run(["ssh", "-F", str(config), "-T", "--", "cowork-iac-" + host, "python3 -"],
                   input=script, text=True, env=env, timeout=timeout, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sha", required=True, help="Immutable release source commit")
    parser.add_argument("--applications", required=True, help="Successfully deployed application matrix JSON")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true", help="Delete eligible groups (default: dry-run)")
    mode.add_argument("--validate", action="store_true", help="Validate inputs without Vault or SSH access")
    args = parser.parse_args()
    require(re.fullmatch(r"[0-9a-f]{40}", args.sha), "A full release commit is required")
    broker = runpy.run_path(str(BROKER_SCRIPT))
    entries = broker["load_catalog"](CATALOG)
    selected = selected_consumers(args.applications, entries)
    if args.validate:
        print(f"[retirement] Validated {len(entries)} declarations; no Vault read or SSH connection")
        return
    require_release(args.sha)
    if not selected:
        print("[retirement] No declared consumers were deployed; nothing to retire")
        return
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix="cowork-kafka-retirement-", dir=os.environ.get("RUNNER_TEMP")) as work:
        config = ssh_inputs(Path(work), selected)
        for service, applications in selected.items():
            for application in applications:
                remote(config, application["host"], CONSUMER_CHECK,
                       {**application, "service": service, "sha": args.sha}, timeout=90)
            remote(config, "kafka", BROKER_RUN,
                   {"source": BROKER_SCRIPT.read_text(), "catalog": CATALOG.read_text(),
                    "service": service, "apply": args.apply}, timeout=10 * 60)


if __name__ == "__main__":
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        main()
    except KeyboardInterrupt:
        print("[retirement] Interrupted; private inputs removed", file=sys.stderr)
        sys.exit(130)
    except (ValueError, OSError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"[retirement] Failed: {error}", file=sys.stderr)
        sys.exit(1)
