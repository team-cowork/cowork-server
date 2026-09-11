#!/usr/bin/env python3
"""Inspect a built image's filesystem and user without starting any application."""
import json
import subprocess
import sys
import tarfile


def main():
    service, image = sys.argv[1:]
    config = json.loads(subprocess.check_output(["docker", "image", "inspect", image]))[0]["Config"]
    if config["User"] not in {"app", "10001", "10001:10001"}:
        raise ValueError("The runtime must use the dedicated app user")
    if not config.get("Entrypoint"):
        raise ValueError("Missing runtime entrypoint")
    required = {
        "chat": {"app/dist/main.js", "app/public/asyncapi.json"},
        "user": {"app/bin/cowork_user", "flyway/flyway"},
        "preference": {"app/app.jar"},
    }.get(service, {f"usr/local/bin/cowork-{service}"} if service in {"authorization", "notification", "voice"}
          else {"app/org/springframework/boot/loader/launch/JarLauncher.class"})
    container = subprocess.check_output(
        ["docker", "create", "--network", "none", "--entrypoint", "/bin/true", image], text=True,
    ).strip()
    try:
        process = subprocess.Popen(["docker", "export", container], stdout=subprocess.PIPE)
        paths = set()
        writable_logs = False
        uid_valid = False
        with tarfile.open(fileobj=process.stdout, mode="r|*") as archive:
            for entry in archive:
                name = entry.name.removeprefix("./")
                paths.add(name)
                if name == "etc/passwd":
                    uid_valid = any(line.startswith("app:x:10001:10001:")
                                    for line in archive.extractfile(entry).read().decode().splitlines())
                if name == "var/log/cowork":
                    writable_logs = entry.uid == 10001 and bool(entry.mode & 0o200)
        process.stdout.close()
        if process.wait():
            raise ValueError("Cannot export the image filesystem")
        if not uid_valid:
            raise ValueError("The app user must have UID and GID 10001")
        missing = required - paths
        if missing:
            raise ValueError(f"Missing runtime files: {sorted(missing)}")
        if service in {"config", "gateway", "team", "channel", "project", "roadmap", "preference", "user"} and not writable_logs:
            raise ValueError("The app user cannot write its log directory")
        if service in {"authorization", "notification", "user"}:
            prefix = "flyway/sql/" if service == "user" else "app/db/migration/"
            if not any(path.startswith(prefix) and path.endswith(".sql") for path in paths):
                raise ValueError("Missing database migrations")
        print(f"{service}: runtime files, non-root identity and log ownership valid (application not started)")
    finally:
        subprocess.run(["docker", "rm", container], check=True, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
