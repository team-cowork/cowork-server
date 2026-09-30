#!/usr/bin/env python3
"""기존 팀의 채널 역할 정책 운영 전환 도구.

절차와 export SQL은 docs/channel-role-policy-transition.md를 따른다.
plan은 아무 상태도 바꾸지 않고, apply는 같은 manifest와 export로 계산한 operation만 제출한다.
"""
import argparse
import csv
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

OWNER = "OWNER"
FINAL_STATUSES = {"SUCCEEDED", "FAILED"}
DECISIONS = {"APPLY", "DEFAULT_DENY_APPROVED"}
EXPORT_COLUMNS = {
    "team-members": ("team_id", "user_id", "role"),
    "roles": ("id", "team_id", "priority"),
    "role-assignments": ("team_id", "account_id", "role_id"),
    "policies": ("team_id", "channel_id", "role_id", "message_read"),
    "channels": ("id", "team_id", "is_private"),
    "channel-members": ("channel_id", "user_id"),
}
PROJECTION_COLUMNS = ("teamId", "channelId", "roleId", "messageRead")


def read_rows(path, columns):
    with open(path, newline="", encoding="utf-8") as file:
        first = file.readline()
        file.seek(0)
        rows = list(csv.DictReader(file, delimiter="\t" if "\t" in first else ","))
    missing = [column for column in columns if rows and column not in rows[0]]
    if missing:
        raise SystemExit(f"{path}: 누락된 컬럼 {missing}")
    return rows


def parse_bool(value):
    normalized = (value or "").strip().lower()
    if normalized in ("1", "true", "t"):
        return True
    if normalized in ("0", "false", "f"):
        return False
    if normalized in ("", "null"):
        return None
    raise SystemExit(f"boolean 값이 아닙니다: {value!r}")


def read_policy_rows(path, team, channel, role, message_read):
    policies = {}
    for row in read_rows(path, (team, channel, role, message_read)):
        value = parse_bool(row[message_read])
        if value is not None:
            policies[(int(row[team]), int(row[channel]), int(row[role]))] = value
    return policies


def load_snapshot(export_dir):
    tables = {
        name: read_rows(Path(export_dir) / f"{name}.csv", columns)
        for name, columns in EXPORT_COLUMNS.items()
        if name != "policies"
    }
    assignments = {}
    for row in tables["role-assignments"]:
        assignments.setdefault((int(row["team_id"]), int(row["account_id"])), set()).add(int(row["role_id"]))
    channel_members = {}
    for row in tables["channel-members"]:
        channel_members.setdefault(int(row["channel_id"]), set()).add(int(row["user_id"]))
    return {
        "members": {(int(r["team_id"]), int(r["user_id"])): r["role"] for r in tables["team-members"]},
        "roles": {int(r["id"]): (int(r["team_id"]), int(r["priority"])) for r in tables["roles"]},
        "assignments": assignments,
        "policies": read_policy_rows(
            Path(export_dir) / "policies.csv", "team_id", "channel_id", "role_id", "message_read"
        ),
        "channels": {
            int(r["id"]): (int(r["team_id"]), parse_bool(r["is_private"])) for r in tables["channels"]
        },
        "channel_members": channel_members,
    }


def teams_of(snapshot):
    """활성 멤버가 있는 팀만 전환 대상이다. 삭제된 팀의 정책은 cowork-preference가 TEAM_DELETED로 거부한다."""
    return {team for team, _ in snapshot["members"]}


def target_policies(manifest, snapshot):
    """manifest를 적용한 뒤의 authoritative 정책. null은 정책 부재, 적지 않은 조합은 현재 상태 유지."""
    target = dict(snapshot["policies"])
    for team, spec in manifest["teams"].items():
        for policy in spec.get("policies", []):
            key = (int(team), policy["channelId"], policy["roleId"])
            if policy["messageRead"] is None:
                target.pop(key, None)
            else:
                target[key] = policy["messageRead"]
    return target


def read_decision(snapshot, policies, team, user, channel):
    """cowork-channel·cowork-chat 평가기와 같은 규칙: OWNER만 정책을 우회하고, 최고 priority에서 deny가 이긴다."""
    if snapshot["members"].get((team, user)) == OWNER:
        return True, False
    applicable = []
    for role in snapshot["assignments"].get((team, user), ()):
        role_team, priority = snapshot["roles"].get(role, (None, None))
        value = policies.get((team, channel, role))
        if role_team == team and value is not None:
            applicable.append((priority, value))
    if not applicable:
        return False, False
    highest = max(priority for priority, _ in applicable)
    values = {value for priority, value in applicable if priority == highest}
    return values == {True}, len(values) > 1


def check(manifest, snapshot):
    errors, warnings = [], []
    teams = teams_of(snapshot)
    manifest_teams = {int(team) for team in manifest["teams"]}
    for team in sorted(teams - manifest_teams):
        errors.append(f"team {team}: manifest에 없는 전환 누락 팀입니다")
    for team_text, spec in manifest["teams"].items():
        team = int(team_text)
        if team not in teams:
            errors.append(f"team {team}: export에 없는 팀입니다")
        if spec.get("decision") not in DECISIONS:
            errors.append(f"team {team}: decision은 {sorted(DECISIONS)} 중 하나여야 합니다")
        if spec.get("decision") == "DEFAULT_DENY_APPROVED" and spec.get("policies"):
            errors.append(f"team {team}: DEFAULT_DENY_APPROVED 팀은 policies를 가질 수 없습니다")
        if snapshot["members"].get((team, spec.get("actorId"))) != OWNER:
            errors.append(f"team {team}: actorId {spec.get('actorId')}는 팀의 활성 OWNER가 아닙니다")
        seen = set()
        for policy in spec.get("policies", []):
            channel, role = policy.get("channelId"), policy.get("roleId")
            if (channel, role) in seen:
                errors.append(f"team {team}: channel {channel} role {role} 항목이 중복됩니다")
            seen.add((channel, role))
            if snapshot["channels"].get(channel, (None,))[0] != team:
                errors.append(f"team {team}: channel {channel}은 이 팀의 채널이 아닙니다")
            if snapshot["roles"].get(role, (None,))[0] != team:
                errors.append(f"team {team}: role {role}은 이 팀의 사용자 정의 역할이 아닙니다")
            if "messageRead" not in policy or policy["messageRead"] not in (True, False, None):
                errors.append(f"team {team}: channel {channel} role {role}의 messageRead는 true, false, null이어야 합니다")
    if errors:
        return errors, warnings

    policies = target_policies(manifest, snapshot)
    for (team, user), role in sorted(snapshot["members"].items()):
        if role == OWNER:
            continue
        visible = [
            channel
            for channel, (channel_team, private) in snapshot["channels"].items()
            if channel_team == team and (not private or user in snapshot["channel_members"].get(channel, ()))
        ]
        readable = 0
        for channel in visible:
            allowed, conflict = read_decision(snapshot, policies, team, user, channel)
            readable += allowed
            if conflict:
                warnings.append(f"team {team} user {user} channel {channel}: 동일 priority allow/deny 충돌로 거부됩니다")
        if visible and not readable:
            decision = manifest["teams"][str(team)]["decision"]
            warnings.append(f"team {team} user {user} ({role}, {decision}): 읽을 수 있는 채널이 0개입니다")
    return errors, warnings


def idempotency_key(version, team, channel, role, message_read):
    permissions = "DELETE" if message_read is None else json.dumps({"message_read": message_read})
    digest = hashlib.sha256(f"{version}|{team}|{channel}|{role}|{permissions}".encode()).hexdigest()
    return f"crp-transition-{digest}"


def operations(manifest, snapshot):
    """현재 정책과 다른 manifest 항목만 operation으로 만든다."""
    result = []
    for team_text, spec in sorted(manifest["teams"].items(), key=lambda item: int(item[0])):
        team = int(team_text)
        for policy in spec.get("policies", []):
            channel, role, desired = policy["channelId"], policy["roleId"], policy["messageRead"]
            if snapshot["policies"].get((team, channel, role)) == desired:
                continue
            result.append({
                "teamId": team,
                "channelId": channel,
                "roleId": role,
                "actorId": spec["actorId"],
                "messageRead": desired,
                "idempotencyKey": idempotency_key(manifest["version"], team, channel, role, desired),
            })
    return result


def load_manifest(path):
    manifest = json.loads(Path(path).read_text(encoding="utf-8"))
    if not manifest.get("version") or not isinstance(manifest.get("teams"), dict):
        raise SystemExit("manifest에는 version과 teams가 필요합니다")
    return manifest


def plan(args):
    manifest, snapshot = load_manifest(args.manifest), load_snapshot(args.export_dir)
    errors, warnings = check(manifest, snapshot)
    for message in errors:
        print(f"ERROR {message}")
    for message in warnings:
        print(f"WARN  {message}")
    if errors:
        return 1
    for op in operations(manifest, snapshot):
        action = "DELETE" if op["messageRead"] is None else f"UPSERT message_read={str(op['messageRead']).lower()}"
        print(f"OP    team {op['teamId']} channel {op['channelId']} role {op['roleId']}: {action}")
    return 0


def request(base_url, method, path, actor, idempotency=None, body=None):
    headers = {"X-User-Id": str(actor), "Accept": "application/json"}
    data = None
    if idempotency:
        headers["Idempotency-Key"] = idempotency
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = urllib.request.Request(base_url.rstrip("/") + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as response:
        payload = json.loads(response.read() or b"{}")
    return payload.get("data", payload)


def describe(error):
    if isinstance(error, urllib.error.HTTPError):
        return f"HTTP {error.code}: {error.read().decode(errors='replace')[:500]}"
    return str(error)


def apply(args):
    manifest, snapshot = load_manifest(args.manifest), load_snapshot(args.export_dir)
    errors, _ = check(manifest, snapshot)
    if errors:
        print("plan에 ERROR가 있어 적용하지 않습니다. plan 결과를 먼저 해결하세요.")
        return 1
    manifest_hash = hashlib.sha256(Path(args.manifest).read_bytes()).hexdigest()
    state_path = Path(args.state or f"{manifest['version']}.state.json")
    state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {
        "version": manifest["version"], "manifestSha256": manifest_hash, "operations": {},
    }
    if state["manifestSha256"] != manifest_hash:
        print("같은 version의 state가 다른 manifest로 만들어졌습니다. manifest를 바꿨다면 version도 바꾸세요.")
        return 1

    def save():
        state_path.write_text(json.dumps(state, indent=2, ensure_ascii=False), encoding="utf-8")

    ops = {op["idempotencyKey"]: op for op in operations(manifest, snapshot)}
    for key, record in state["operations"].items():
        ops.setdefault(key, record["op"])
    for key, op in ops.items():
        record = state["operations"].get(key)
        if record and record.get("operationId"):
            continue
        path = f"/channels/{op['channelId']}/role-policies/{op['roleId']}"
        try:
            if op["messageRead"] is None:
                response = request(args.base_url, "DELETE", path, op["actorId"], key)
            else:
                body = {"permissions": {"message_read": op["messageRead"]}}
                response = request(args.base_url, "PUT", path, op["actorId"], key, body)
            state["operations"][key] = {"op": op, "operationId": response["operationId"], "status": response["status"]}
        except (urllib.error.URLError, KeyError, ValueError) as error:
            state["operations"][key] = {"op": op, "operationId": None, "status": "SUBMIT_ERROR", "error": describe(error)}
        save()

    deadline = time.monotonic() + args.timeout
    while True:
        pending = [r for r in state["operations"].values() if r["operationId"] and r["status"] not in FINAL_STATUSES]
        if not pending or time.monotonic() > deadline:
            break
        for record in pending:
            op = record["op"]
            path = f"/channels/{op['channelId']}/role-policies/{op['roleId']}/operations/{record['operationId']}"
            try:
                response = request(args.base_url, "GET", path, op["actorId"])
                record["status"] = response["status"]
                record["error"] = response.get("errorCode") and f"{response['errorCode']}: {response.get('errorMessage')}"
            except (urllib.error.URLError, KeyError, ValueError) as error:
                record["error"] = describe(error)
        save()
        time.sleep(args.interval)

    counts = {}
    for record in state["operations"].values():
        counts[record["status"]] = counts.get(record["status"], 0) + 1
        if record["status"] != "SUCCEEDED":
            op = record["op"]
            print(f"{record['status']} team {op['teamId']} channel {op['channelId']} role {op['roleId']}: {record.get('error')}")
    print(f"state={state_path} {json.dumps(counts, sort_keys=True)}")
    return 0 if set(counts) <= {"SUCCEEDED"} else 1


def verify(args):
    manifest, snapshot = load_manifest(args.manifest), load_snapshot(args.export_dir)
    authoritative = snapshot["policies"]
    problems = []
    for team, spec in manifest["teams"].items():
        for policy in spec.get("policies", []):
            key = (int(team), policy["channelId"], policy["roleId"])
            if authoritative.get(key) != policy["messageRead"]:
                problems.append(f"authoritative {key}: 기대 {policy['messageRead']}, 실제 {authoritative.get(key)}")
    for name, path in (("cowork-channel", args.channel_projection), ("cowork-chat", args.chat_projection)):
        projection = read_policy_rows(path, *PROJECTION_COLUMNS)
        for key in sorted(authoritative.keys() | projection.keys()):
            if authoritative.get(key) != projection.get(key):
                problems.append(f"{name} {key}: authoritative {authoritative.get(key)}, projection {projection.get(key)}")
    for message in problems:
        print(f"MISMATCH {message}")
    print("verify OK" if not problems else f"verify FAILED ({len(problems)})")
    return 0 if not problems else 1


def rollback_manifest(args):
    """전환 전 export로 manifest가 건드린 조합을 이전 상태로 되돌리는 manifest를 만든다."""
    manifest, before = load_manifest(args.manifest), load_snapshot(args.before_export_dir)
    rollback = {"version": f"{manifest['version']}-rollback", "teams": {}}
    for team, spec in manifest["teams"].items():
        rollback["teams"][team] = {
            "actorId": spec["actorId"],
            "decision": spec["decision"],
            "policies": [
                {
                    "channelId": policy["channelId"],
                    "roleId": policy["roleId"],
                    "messageRead": before["policies"].get((int(team), policy["channelId"], policy["roleId"])),
                }
                for policy in spec.get("policies", [])
            ],
        }
    Path(args.out).write_text(json.dumps(rollback, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"rollback manifest={args.out}")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)

    plan_parser = commands.add_parser("plan", help="사전 점검과 operation 계산 (상태 변경 없음)")
    apply_parser = commands.add_parser("apply", help="plan과 같은 operation을 cowork-channel API로 제출하고 추적")
    for sub in (plan_parser, apply_parser):
        sub.add_argument("--manifest", required=True)
        sub.add_argument("--export-dir", required=True)
    apply_parser.add_argument("--base-url", required=True, help="사설망의 cowork-channel 주소")
    apply_parser.add_argument("--state", help="기본값: <version>.state.json")
    apply_parser.add_argument("--timeout", type=int, default=600, help="operation 추적 제한 시간(초)")
    apply_parser.add_argument("--interval", type=float, default=2.0)

    verify_parser = commands.add_parser("verify", help="authoritative 정책과 두 projection의 일치 확인")
    verify_parser.add_argument("--manifest", required=True)
    verify_parser.add_argument("--export-dir", required=True, help="적용 후 다시 받은 export")
    verify_parser.add_argument("--channel-projection", required=True)
    verify_parser.add_argument("--chat-projection", required=True)

    rollback_parser = commands.add_parser("rollback-manifest", help="전환 전 상태로 되돌리는 manifest 생성")
    rollback_parser.add_argument("--manifest", required=True)
    rollback_parser.add_argument("--before-export-dir", required=True)
    rollback_parser.add_argument("--out", required=True)

    args = parser.parse_args(argv)
    handlers = {"plan": plan, "apply": apply, "verify": verify, "rollback-manifest": rollback_manifest}
    return handlers[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
