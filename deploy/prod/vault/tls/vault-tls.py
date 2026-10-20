#!/usr/bin/env python3
"""Issue, renew and verify the private Vault listener certificate from the root-owned CA."""
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import time


STATE_DIR = Path("/etc/cowork/vault-tls")
SETTINGS_FILE = STATE_DIR / "settings.json"
CA_KEY = STATE_DIR / "ca.key"
CA_CERT = STATE_DIR / "ca.crt"
TIMER = "cowork-vault-tls.timer"
LIFETIME_DAYS = 90
RENEW_BEFORE_SECONDS = 30 * 86400
DEPLOY_MINIMUM_SECONDS = 7 * 86400
# The hashicorp/vault image runs as vault:vault.
VAULT_UID, VAULT_GID = 100, 1000


def demand(condition, message):
    if not condition:
        raise ValueError(message)


def settings(value):
    demand(isinstance(value, dict) and set(value) == {"address", "port", "volume", "container"}, "Invalid Vault TLS settings")
    address = str(ipaddress.IPv4Address(value["address"]))
    demand(ipaddress.IPv4Address(address).is_private, "The Vault TLS listener requires a private address")
    demand(type(value["port"]) is int and 1 <= value["port"] <= 65535, "Invalid Vault TLS port")
    for key in ("volume", "container"):
        demand(isinstance(value[key], str) and re.fullmatch(r"cowork-[a-z0-9][a-z0-9_.-]*", value[key]), "Invalid Vault TLS name")
    return {**value, "address": address}


def check_owned(path, directory=False, private=False):
    info = path.lstat()
    expected_type = stat.S_ISDIR if directory else stat.S_ISREG
    demand(expected_type(info.st_mode) and info.st_uid == 0 and not info.st_mode & (0o077 if private else 0o022),
           "Vault TLS requires root-owned files and directories")


def command(*args, input_text=None):
    result = subprocess.run(args, input=input_text, text=True, capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError(f"Vault TLS command failed: {args[0]} {args[1] if len(args) > 1 else ''}".rstrip())
    return result.stdout


def certificate_valid(path, value, seconds):
    """The served chain must come from this CA, name this address and stay valid for the window."""
    if not path.is_file():
        return False
    for check in (["x509", "-checkend", str(seconds), "-noout", "-in", str(path)],
                  ["verify", "-CAfile", str(CA_CERT), str(path)]):
        if subprocess.run(["openssl", *check], capture_output=True, timeout=60).returncode:
            return False
    names = command("openssl", "x509", "-noout", "-ext", "subjectAltName", "-in", str(path))
    return re.search(rf"IP Address:{re.escape(value['address'])}(?:,|\s|$)", names) is not None


def volume_directory(value):
    directory = Path(command("docker", "volume", "inspect", "--format", "{{.Mountpoint}}", value["volume"]).strip())
    demand(directory.is_absolute() and directory.is_dir() and not directory.is_symlink(), "Invalid Vault TLS volume")
    return directory


def install(directory, name, content, mode):
    descriptor, temporary = tempfile.mkstemp(prefix=f".{name}-", dir=directory)
    try:
        with os.fdopen(descriptor, "wb") as output:
            os.fchmod(output.fileno(), mode)
            os.fchown(output.fileno(), VAULT_UID, VAULT_GID)
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, directory / name)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def issue(value, directory):
    with tempfile.TemporaryDirectory(dir=STATE_DIR) as work:
        work = Path(work)
        extensions = work / "server.ext"
        extensions.write_text(f"subjectAltName=IP:{value['address']}\nbasicConstraints=critical,CA:FALSE\n"
                              "keyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\n")
        command("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", str(work / "server.key"))
        command("openssl", "req", "-new", "-key", str(work / "server.key"), "-subj", f"/CN={value['address']}",
                "-out", str(work / "server.csr"))
        command("openssl", "x509", "-req", "-in", str(work / "server.csr"), "-CA", str(CA_CERT), "-CAkey", str(CA_KEY),
                "-set_serial", "0x" + secrets.token_hex(16), "-days", str(LIFETIME_DAYS), "-sha256",
                "-extfile", str(extensions), "-out", str(work / "leaf.crt"))
        chain = (work / "leaf.crt").read_bytes() + CA_CERT.read_bytes()
        (work / "server.crt").write_bytes(chain)
        demand(certificate_valid(work / "server.crt", value, RENEW_BEFORE_SECONDS), "The issued certificate failed verification")
        # Vault reads both files only on start or SIGHUP, after both replacements.
        install(directory, "server.key", (work / "server.key").read_bytes(), 0o600)
        install(directory, "server.crt", chain, 0o644)
        return ssl.PEM_cert_to_DER_cert((work / "leaf.crt").read_text())


def served_certificate(value):
    context = ssl.create_default_context(cafile=str(CA_CERT))
    with socket.create_connection((value["address"], value["port"]), timeout=5) as connection:
        with context.wrap_socket(connection, server_hostname=value["address"]) as tls:
            return tls.getpeercert(binary_form=True)


def serving(value):
    """Only a running Vault that mounts this volume can reload the certificate."""
    result = subprocess.run(["docker", "container", "inspect", "--format",
                             "{{.State.Running}}{{range .Mounts}} {{.Name}}{{end}}", value["container"]],
                            capture_output=True, text=True, timeout=60)
    fields = result.stdout.split()
    return result.returncode == 0 and fields[:1] == ["true"] and value["volume"] in fields[1:]


def renew(value, force=False):
    directory = volume_directory(value)
    if not force and certificate_valid(directory / "server.crt", value, RENEW_BEFORE_SECONDS):
        print("[vault-tls] Certificate is valid beyond the renewal window")
        return
    leaf = issue(value, directory)
    if not serving(value):
        print("[vault-tls] Certificate issued; Vault loads it when it starts")
        return
    command("docker", "kill", "--signal", "HUP", value["container"])
    for _ in range(15):
        try:
            if served_certificate(value) == leaf:
                print("[vault-tls] Certificate renewed and served")
                return
        except (OSError, ssl.SSLError):
            pass
        time.sleep(2)
    raise RuntimeError("Vault did not serve the renewed certificate")


def check(value, expected):
    demand(settings(expected) == value, "Installed Vault TLS settings do not match the release slot")
    demand(certificate_valid(volume_directory(value) / "server.crt", value, DEPLOY_MINIMUM_SECONDS),
           "The Vault TLS certificate is missing, untrusted or expires within 7 days")
    demand(command("systemctl", "is-enabled", TIMER).strip() == "enabled", "Vault TLS renewal timer is not enabled")
    command("systemctl", "is-active", "--quiet", TIMER)
    print("[vault-tls] Certificate and renewal timer are valid")


def main():
    actions = {"renew", "renew-now", "check"}
    demand(len(sys.argv) == 2 and sys.argv[1] in actions, "Usage: vault-tls.py renew|renew-now|check")
    demand(os.geteuid() == 0, "Vault TLS management requires root")
    os.environ["PATH"] = "/usr/sbin:/usr/bin:/sbin:/bin"
    for directory in (STATE_DIR, *STATE_DIR.parents):
        check_owned(directory, directory=True, private=directory == STATE_DIR)
    check_owned(CA_KEY, private=True)
    check_owned(CA_CERT)
    check_owned(SETTINGS_FILE)
    value = settings(json.loads(SETTINGS_FILE.read_text()))
    if sys.argv[1] == "check":
        check(value, json.loads(sys.stdin.read(4096)))
    else:
        renew(value, force=sys.argv[1] == "renew-now")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        print(f"[vault-tls] {error}", file=sys.stderr)
        sys.exit(1)
