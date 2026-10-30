#!/usr/bin/env python3
"""Reserve immutable releases, pin image/secret versions, and publish committed releases."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import subprocess
from zoneinfo import ZoneInfo

from deploy.ci.build_matrix import affected
from deploy.production.version_fields import VERSION, changed_paths, module_files, normalized, rewrite

ROOT = Path(__file__).resolve().parents[2]

MANIFEST = Path("deploy/production/config/releases.json")
MARKER = "Cowork CD release reservation\n"
SHA = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
INFRASTRUCTURE = {
    "chat-search": "deploy/images/elasticsearch/Dockerfile",
    "kafka-init": "deploy/images/kafka-init/Dockerfile",
    "user-migrations": "deploy/images/user-migrations/Dockerfile",
}


def git(*args, cwd=ROOT, token=None):
    env = dict(os.environ)
    if token:
        env["GH_TOKEN"] = token
    return subprocess.check_output(
        ["git", "-c", "credential.helper=", "-c", "credential.helper=!gh auth git-credential", *args],
        cwd=cwd, env=env, text=True,
    ).strip()


def api(path, payload=None, missing=False):
    command = ["gh", "api", f"repos/{os.environ['GITHUB_REPOSITORY']}/{path}"]
    if payload is not None:
        command += ["--method", "POST", "--input", "-"]
    result = subprocess.run(command, input=json.dumps(payload) if payload is not None else None,
                            capture_output=True, text=True, timeout=60)
    if result.returncode:
        if missing and "(HTTP 404)" in result.stderr:
            return None
        raise RuntimeError(result.stderr)
    return json.loads(result.stdout)


def emit(**values):
    if "GITHUB_OUTPUT" not in os.environ:
        return
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        for key, value in values.items():
            if isinstance(value, (dict, list)):
                value = json.dumps(value, separators=(",", ":"))
            output.write(f"{key}={value}\n")


def save(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


def services():
    return json.loads((ROOT / "deploy/production/config/services.json").read_text())


def application_catalog():
    return {name: spec for name, spec in json.loads((ROOT / "deploy/images/catalog.json").read_text()).items()
            if name in services() and name not in {"monitoring", "kafka"}}


def source_sha():
    sha = os.environ["RELEASE_SHA"]
    if not SHA.fullmatch(sha):
        raise ValueError("Release source must be a full commit SHA")
    return sha


def reservation(tag, sha):
    if git("cat-file", "-t", f"refs/tags/{tag}") != "tag":
        return None
    message = git("cat-file", "-p", f"refs/tags/{tag}").split("\n\n", 1)[1]
    if not message.startswith(MARKER):
        return None
    data = json.loads(message[len(MARKER):])
    if data.get("source") != sha or data.get("version") != tag[1:]:
        raise ValueError(f"Invalid release reservation: {tag}")
    modules = data["modules"]
    if not isinstance(modules, list) or not modules or modules != sorted(set(modules)):
        raise ValueError("Reservation must contain unique sorted modules")
    for module in modules:
        module_files(module)
    return data


def reserve(sha, modules):
    git("fetch", "--quiet", "--tags", "origin")
    tags = [tag for tag in git("tag", "--list", "v*").splitlines() if VERSION.fullmatch(tag[1:])]
    owned = [data for tag in git("tag", "--points-at", sha).splitlines() if tag in tags
             if (data := reservation(tag, sha))]
    if len(owned) > 1:
        raise ValueError("Multiple release reservations identify this source")
    if owned:
        data = owned[0]
        if not set(modules) <= set(data["modules"]):
            raise ValueError("Release scope changed after reservation; use a new source commit")
        return data["version"]
    if not modules:
        return ""
    date = datetime.now(ZoneInfo("Asia/Seoul")).strftime("%Y%m%d")
    sequence = max((int(tag.split(".")[1]) for tag in tags if tag.startswith(f"v{date}.")), default=-1) + 1
    data = {"version": f"{date}.{sequence}", "source": sha, "modules": modules}
    tag = api("git/tags", {"tag": "v" + data["version"], "object": sha, "type": "commit",
                           "message": MARKER + json.dumps(data, separators=(",", ":"))})
    api("git/refs", {"ref": "refs/tags/v" + data["version"], "sha": tag["sha"]})
    return data["version"]


def validate_manifest(data):
    if data.get("schema_version") != 1 or not SHA.fullmatch(data.get("source_sha", "")):
        raise ValueError("Invalid release manifest source or schema")
    if set(data.get("applications", {})) != set(application_catalog()):
        raise ValueError("Manifest must contain every deployed application")
    if set(data.get("infrastructure_images", {})) != set(INFRASTRUCTURE):
        raise ValueError("Manifest must contain every infrastructure image")
    for name, app in data["applications"].items():
        if not SHA.fullmatch(app.get("image_source_sha", "")) or not VERSION.fullmatch(app.get("version", "")):
            raise ValueError(f"Invalid image source/version for {name}")
        if not isinstance(app.get("secret_version"), int) or app["secret_version"] < 1:
            raise ValueError(f"Missing pinned secret version for {name}")
        if not isinstance(app.get("data_secret_version"), int) or app["data_secret_version"] < 1:
            raise ValueError(f"Missing pinned data secret version for {name}")
    images = [app["image"] for app in data["applications"].values()] + list(data["infrastructure_images"].values())
    for image in images:
        if not re.fullmatch(r"ghcr\.io/[a-z0-9_.-]+/cowork-[a-z0-9-]+@sha256:[0-9a-f]{64}", image):
            raise ValueError("Every image must be a pinned GHCR digest")
    platform_versions = data.get("platform_secret_versions", {})
    if set(platform_versions) != {"monitoring"} or type(platform_versions["monitoring"]) is not int or platform_versions["monitoring"] < 1:
        raise ValueError("Manifest must pin the monitoring application secret version")
    catalog = services()
    data_paths = set(catalog) | {f"{name}/{group}" for name, spec in catalog.items()
                                for group in spec.get("data_secret_groups", {})}
    if set(data.get("data_secret_versions", {})) != data_paths:
        raise ValueError("Manifest must pin data secrets for every target")
    if any(not isinstance(version, int) or version < 1 for version in data["data_secret_versions"].values()):
        raise ValueError("Data secret versions must be positive integers")


def manifest_version(data):
    return data.get("release_version") or max(
        (app["version"] for app in data.get("applications", {}).values()),
        key=lambda version: tuple(map(int, version.split("."))), default="",
    )


def prepare(args):
    sha = source_sha()
    if git("rev-parse", "HEAD") != sha:
        raise ValueError("Checkout must match the CI-validated source")
    mode = os.environ.get("DEPLOY_MODE", "release")
    emit(mode=mode, owner=os.environ["GITHUB_REPOSITORY_OWNER"].lower(), source_sha=sha)
    if mode != "release":
        manifest = json.loads((ROOT / MANIFEST).read_text())
        validate_manifest(manifest)
        emit(matrix={"include": []}, sync_matrix={"include": []},
             version=manifest_version(manifest), source_sha=manifest["source_sha"])
        return
    if not os.environ.get("RELEASE_APP_CLIENT_ID") or not os.environ.get("RELEASE_APP_PRIVATE_KEY"):
        raise ValueError("Release App credentials are required before reserving a version or changing secrets")
    if os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch":
        runs = api(f"actions/workflows/cowork-prod-ci.yml/runs?head_sha={sha}&event=push&status=success&per_page=100")
        if not any(run["head_sha"] == sha and run["head_branch"] == "main" and run["conclusion"] == "success"
                   for run in runs["workflow_runs"]):
            raise ValueError("Manual release requires successful main CI for this exact source")
    previous = json.loads((ROOT / MANIFEST).read_text()) if (ROOT / MANIFEST).exists() else {}
    catalog = application_catalog()
    selected = []
    for name, context in sorted(catalog.items()):
        prior = previous.get("applications", {}).get(name)
        if not prior or affected(name, changed_paths(prior["image_source_sha"], sha)):
            selected.append({"service": name, "context": context,
                             "dockerfile": f"cowork-{name}/Dockerfile", "application": True})
    version = reserve(sha, [item["service"] for item in selected]) or manifest_version(previous)
    changed = changed_paths(previous["source_sha"], sha) if previous.get("source_sha") else None
    for name, dockerfile in INFRASTRUCTURE.items():
        prefixes = {
            "chat-search": ("deploy/images/elasticsearch/",),
            "kafka-init": ("deploy/images/kafka-init/", "deploy/runtime/kafka/"),
            "user-migrations": ("deploy/images/user-migrations/", "cowork-user/src/db/"),
        }[name]
        if name not in previous.get("infrastructure_images", {}) or changed is None or any(
            path == ".dockerignore" or path.startswith(prefixes) for path in changed
        ):
            selected.append({"service": name, "context": ".", "dockerfile": dockerfile, "application": False})
    sync = []
    for name, spec in services().items():
        names = set(spec.get("secret_bindings", {}).values()) | set(spec.get("data_secret_bindings", {}).values())
        if spec.get("kafka_principal"):
            names.add("KAFKA_CREDENTIALS_JSON")
        variables = set(spec.get("variable_bindings", {}).values()) | set(spec.get("data_variable_bindings", {}).values())
        sync.append({"service": name, "environment": spec["environment"],
                     "secret_names": sorted(names), "variable_names": sorted(variables)})
    plan = {"source_sha": sha, "version": version, "selected": selected, "previous": previous}
    save(args.output, plan)
    emit(matrix={"include": selected}, sync_matrix={"include": sync}, version=version)


def stamp(args):
    version = os.environ["RELEASE_VERSION"]
    if not VERSION.fullmatch(version):
        raise ValueError("Release version must be YYYYMMDD.n")
    updates = {path: rewrite(path, (ROOT / path).read_text(), version) for path in module_files(args.service)}
    for path, content in updates.items():
        (ROOT / path).write_text(content)


def image_reference(service):
    if service not in application_catalog() and service not in INFRASTRUCTURE:
        raise ValueError("Unknown image service")
    owner = os.environ["GITHUB_REPOSITORY_OWNER"].lower()
    return f"ghcr.io/{owner}/cowork-{service}:sha-{source_sha()}"


def inspect_image(reference, missing=False):
    result = subprocess.run(["docker", "buildx", "imagetools", "inspect", reference,
                             "--format", "{{json .Manifest}}"], capture_output=True, text=True)
    if result.returncode:
        if missing and any(message in result.stderr.lower() for message in ("manifest unknown", "not found", "no such manifest", "name unknown")):
            return None
        raise RuntimeError(result.stderr)
    result = json.loads(result.stdout)
    if not DIGEST.fullmatch(result.get("digest", "")):
        raise ValueError("Registry did not return an immutable image digest")
    return result["digest"]


def reuse_image(args):
    image = image_reference(args.service)
    version = os.environ.get("RELEASE_VERSION", "") if args.service in application_catalog() else ""
    alias = image.rsplit(":", 1)[0] + ":v" + version if version else None
    existing = inspect_image(image, missing=True)
    published = inspect_image(alias, missing=True) if alias else None
    if existing and published and existing != published:
        raise ValueError("Reserved version tag identifies a different image")
    if not existing and not published:
        emit(reuse="false")
        return
    reference = image if existing else alias
    subprocess.run(["docker", "pull", "--platform", "linux/amd64", reference], check=True)
    labels = json.loads(subprocess.check_output(
        ["docker", "image", "inspect", "--format", "{{json .Config.Labels}}", reference], text=True))
    if labels.get("org.opencontainers.image.revision") != source_sha():
        raise ValueError("Published image has a different source revision")
    if version and labels.get("org.opencontainers.image.version") != "v" + version:
        raise ValueError("Published image has a different reserved version")
    for target, exists in [(image, existing), (alias, published)]:
        if target and not exists:
            subprocess.run(["docker", "buildx", "imagetools", "create", "--tag", target, reference], check=True)
    emit(reuse="true")


def record_image(args):
    image = image_reference(args.service)
    digest = inspect_image(image)
    save(args.output, {"service": args.service, "image": image.rsplit(":", 1)[0] + "@" + digest,
                       "image_source_sha": source_sha(), "version": os.environ.get("RELEASE_VERSION", "")})


def assemble(args):
    plan = json.loads(Path(args.plan).read_text())
    if plan["source_sha"] != source_sha():
        raise ValueError("Release artifacts identify a different source")
    manifest = plan["previous"]
    manifest.update(schema_version=1, source_sha=source_sha(), release_version=plan["version"])
    apps = manifest.setdefault("applications", {})
    infra = manifest.setdefault("infrastructure_images", {})
    records = {}
    for path in Path(args.artifacts).glob("image-*.json"):
        record = json.loads(path.read_text())
        name = record["service"]
        if name in records or record["image_source_sha"] != source_sha():
            raise ValueError("Duplicate or stale image artifact")
        records[name] = record
    if set(records) != {item["service"] for item in plan["selected"]}:
        raise ValueError("Missing or unexpected image artifacts")
    for name, record in records.items():
        if name in INFRASTRUCTURE:
            infra[name] = record["image"]
        else:
            apps[name] = {key: record[key] for key in ("image", "image_source_sha", "version")}
    versions = {}
    platform_versions = {}
    for name, spec in services().items():
        record = json.loads((Path(args.artifacts) / f"secrets-{name}.json").read_text())
        versions[name] = int(record["data_secret_version"])
        group_versions = record.get("data_secret_versions", {})
        expected_paths = {f"{name}/{group}" for group in spec.get("data_secret_groups", {})}
        if set(group_versions) != expected_paths:
            raise ValueError("Missing or unexpected data secret versions for " + name)
        versions.update(group_versions)
        if name == "monitoring":
            platform_versions[name] = int(record["secret_version"])
        if name in apps:
            apps[name].update(secret_version=int(record["secret_version"]), data_secret_version=versions[name])
    manifest["data_secret_versions"] = versions
    manifest["platform_secret_versions"] = platform_versions
    validate_manifest(manifest)
    save(ROOT / MANIFEST, manifest)


def current_main():
    git("fetch", "--quiet", "origin", "refs/heads/main")
    head = git("rev-parse", "FETCH_HEAD")
    sha = source_sha()
    git("merge-base", "--is-ancestor", sha, head)
    if any(path != str(MANIFEST) for path in changed_paths(sha, head)):
        raise ValueError("Main changed after source validation; release the newer source instead")
    return head


def bot_identity():
    slug = os.environ["RELEASE_APP_SLUG"]
    if not re.fullmatch(r"[a-zA-Z0-9-]+", slug):
        raise ValueError("Invalid release App slug")
    bot = json.loads(subprocess.check_output(["gh", "api", f"users/{slug}[bot]"], text=True))
    return ["-c", f"user.name={slug}[bot]", "-c", f"user.email={bot['id']}+{slug}[bot]@users.noreply.github.com"]


def commit_manifest(args):
    content = (ROOT / MANIFEST).read_text()
    data = json.loads(content)
    validate_manifest(data)
    if data["source_sha"] != source_sha():
        raise ValueError("Manifest source does not match this release")
    head = current_main()
    git("restore", "--worktree", "--", str(MANIFEST)) if git("ls-files", "--", str(MANIFEST)) else (ROOT / MANIFEST).unlink()
    git("checkout", "--detach", head)
    (ROOT / MANIFEST).write_text(content)
    files = {path: app["version"] for module, app in data["applications"].items() for path in module_files(module)}
    for path, version in files.items():
        (ROOT / path).write_text(rewrite(path, (ROOT / path).read_text(), version))
    git("add", "--", str(MANIFEST), *sorted(files))
    staged = set(git("diff", "--cached", "--name-only").splitlines())
    if not staged <= set(files) | {str(MANIFEST)}:
        raise ValueError("Release commit contains unexpected files")
    for path in staged - {str(MANIFEST)}:
        if normalized(path, git("show", f"HEAD:{path}")) != normalized(path, git("show", f":{path}")):
            raise ValueError(f"Release commit changes application code: {path}")
    if staged:
        git(*bot_identity(), "commit", "-m", f"update(ci/cd): 배포 버전 고정\n\nRelease-Source: {source_sha()}")
        git("push", "origin", "HEAD:refs/heads/main", token=os.environ["RELEASE_PUSH_TOKEN"])
    emit(manifest_sha=git("rev-parse", "HEAD"))


def publish(args):
    data = json.loads((ROOT / MANIFEST).read_text())
    validate_manifest(data)
    if data["source_sha"] != source_sha():
        raise ValueError("Release manifest identifies another source")
    version = manifest_version(data)
    if not version:
        return
    git("fetch", "--quiet", "--tags", "origin")
    tag = "v" + version
    release_source = git("rev-parse", f"refs/tags/{tag}^{{commit}}")
    reserved = reservation(tag, release_source)
    if not reserved:
        raise ValueError("Release tag must identify the original source")
    for module in reserved["modules"]:
        app = data["applications"][module]
        if app["image_source_sha"] != release_source or app["version"] != version:
            raise ValueError("Release manifest does not contain the reserved image versions")
    release = api("releases/tags/" + tag, missing=True)
    if release and not release["draft"]:
        return
    if release:
        if release["draft"]:
            subprocess.run(["gh", "release", "edit", tag, "--draft=false"], check=True)
    else:
        subprocess.run(["gh", "release", "create", tag, "--verify-tag", "--title", tag, "--generate-notes"], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("prepare", "stamp", "reuse-image", "record-image", "assemble", "commit-manifest", "publish", "validate"):
        sub = commands.add_parser(command)
        if command in {"prepare", "record-image"}:
            sub.add_argument("--output", required=True)
        if command in {"stamp", "reuse-image", "record-image"}:
            sub.add_argument("--service", required=True)
        if command == "assemble":
            sub.add_argument("--plan", required=True)
            sub.add_argument("--artifacts", required=True)
    args = parser.parse_args()
    if args.command == "validate":
        validate_manifest(json.loads((ROOT / MANIFEST).read_text()))
    else:
        globals()[args.command.replace("-", "_")](args)


if __name__ == "__main__":
    main()
