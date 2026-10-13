#!/usr/bin/env python3
"""Inspect the shared slot contract without starting or classifying the application."""
import json
import subprocess
import sys
import tarfile


def main():
    service, image = sys.argv[1:]
    metadata = json.loads(subprocess.check_output(["docker", "image", "inspect", image]))[0]
    config = metadata["Config"]
    if metadata["Os"] != "linux" or metadata["Architecture"] != "amd64":
        raise ValueError("Production slots require linux/amd64 images")
    user = config.get("User", "")
    if user not in {"app", "10001", "10001:10001"}:
        raise ValueError("The slot contract requires UID/GID 10001")
    if not config.get("Entrypoint") or not all(isinstance(part, str) and part for part in config["Entrypoint"]):
        raise ValueError("The image must declare its own executable entrypoint")
    if not config.get("ExposedPorts"):
        raise ValueError("The image must declare its application port")
    # Runtime files and migrations belong to Dockerfile packaging assertions. The
    # slot does not know whether an entrypoint starts BEAM, Java, Go, or Node.
    container = subprocess.check_output(
        ["docker", "create", "--network", "none", "--entrypoint", "/bin/true", image], text=True,
    ).strip()
    try:
        process = subprocess.Popen(["docker", "export", container], stdout=subprocess.PIPE)
        uid_valid = user == "10001:10001"
        with tarfile.open(fileobj=process.stdout, mode="r|*") as archive:
            for entry in archive:
                if entry.name.removeprefix("./") == "etc/passwd":
                    uid_valid = any(line.startswith("app:x:10001:10001:")
                                    for line in archive.extractfile(entry).read().decode().splitlines()) or uid_valid
        process.stdout.close()
        if process.wait():
            raise ValueError("Cannot export the image filesystem")
        if not uid_valid:
            raise ValueError("The runtime identity does not resolve to UID/GID 10001")
        print(f"{service}: platform, non-root identity, entrypoint and exposed port valid (application not started)")
    finally:
        subprocess.run(["docker", "rm", container], check=True, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
