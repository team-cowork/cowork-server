#!/usr/bin/env python3
"""Inspect the shared runtime contract without starting or classifying the application."""
import io
import json
import subprocess
import sys
import tarfile


def named_user_uid(image, user):
    container = subprocess.check_output(
        ["docker", "create", "--network", "none", "--entrypoint", "/bin/true", image], text=True,
    ).strip()
    try:
        passwd = subprocess.check_output(["docker", "cp", "-L", f"{container}:/etc/passwd", "-"])
        with tarfile.open(fileobj=io.BytesIO(passwd)) as archive:
            for entry in archive:
                if not entry.isfile():
                    continue
                for line in archive.extractfile(entry).read().decode().splitlines():
                    fields = line.split(":")
                    if len(fields) >= 3 and fields[0] == user:
                        return int(fields[2])
        raise ValueError(f"The runtime user {user!r} does not resolve in /etc/passwd")
    finally:
        subprocess.run(["docker", "rm", container], check=True, stdout=subprocess.DEVNULL)


def main():
    service, image = sys.argv[1:]
    metadata = json.loads(subprocess.check_output(["docker", "image", "inspect", image]))[0]
    config = metadata["Config"]
    if metadata["Os"] != "linux" or metadata["Architecture"] != "amd64":
        raise ValueError("Production jobs require linux/amd64 images")
    user = config.get("User", "").split(":", 1)[0]
    if not user:
        raise ValueError("The image must declare a non-root runtime user")
    uid = int(user) if user.isdecimal() else named_user_uid(image, user)
    if uid <= 0:
        raise ValueError("The image must run as a non-root UID")
    command = config.get("Entrypoint") or config.get("Cmd")
    if (not isinstance(command, list) or not command or not command[0]
            or not all(isinstance(part, str) for part in command)):
        raise ValueError("The image must declare an executable ENTRYPOINT or CMD")
    # Runtime files and migrations belong to Dockerfile packaging assertions. The
    # runtime contract does not know whether a command starts .NET, Java, Go, or Node.
    print(f"{service}: platform, non-root identity and command valid (application not started)")


if __name__ == "__main__":
    main()
