#!/usr/bin/env python3
"""
커밋 스팸 시뮬레이터 — 방어 장치 테스트용
팀 전용 git 호스팅에서만 사용할 것.

사용법:
    python3 scripts/gen_spam_commits.py --repo /path/to/test-repo --count 1500
    python3 scripts/gen_spam_commits.py --repo /path/to/test-repo --count 1500 --interval 0
"""

import argparse
import os
import random
import subprocess
import sys
import time
from datetime import datetime, timedelta

FAKE_AUTHORS = [
    # 일반적인 이름 패턴
    ("alice",           "alice@example.local"),
    ("bob",             "bob@example.local"),
    ("carol",           "carol_dev@fake.internal"),
    ("dave",            "dave123@nowhere.test"),
    ("eve",             "eve@ghost.invalid"),
    ("mallory",         "mallory@attacker.test"),
    ("trudy",           "trudy@spam.example"),
    ("oscar",           "oscar@dummy.local"),
    # 숫자 조합 패턴
    ("user1482",        "user1482@mail.test"),
    ("dev_9021",        "dev9021@devnull.local"),
    ("coder77",         "coder77@nowhere.invalid"),
    ("hacker42",        "hacker42@example.internal"),
    # 도트/하이픈/언더스코어 혼합
    ("john.doe",        "john.doe@corp.test"),
    ("jane_doe",        "jane_doe@corp.test"),
    ("alex-kim",        "alex-kim@team.local"),
    ("park.joon",       "park.joon@ghost.example"),
    ("lee_hyun.jun",    "lee_hyun.jun@fake.internal"),
    # 이름+숫자+도메인 변형
    ("mike2024",        "mike2024@tempmail.test"),
    ("sarah_99",        "sarah_99@mailbox.invalid"),
    ("tom.h3",          "tom.h3@nowhere.test"),
    ("jenny2k",         "jenny2k@dummy.local"),
    # 서비스명처럼 보이는 패턴
    ("ci-bot",          "ci-bot@build.internal"),
    ("deploy-agent",    "deploy@pipeline.local"),
    ("auto-commit",     "autocommit@system.test"),
    ("merge-helper",    "merge.helper@tools.internal"),
    # 흔한 가짜 도메인 변형
    ("felix",           "felix@protonmail.fake"),
    ("nina",            "nina@tutanota.invalid"),
    ("kai",             "kai@outlook.nowhere"),
    ("luna",            "luna@gmail.test"),
    ("ren",             "ren@yahoo.local"),
    # 공격자가 실제로 쓰는 난수형 패턴
    ("a8f3d",           "a8f3d@x.test"),
    ("zq91mx",          "zq91mx@y.invalid"),
    ("b2c4e6",          "b2c4e6@z.local"),
    ("x7k2p",           "x7k2p@w.example"),
    # 조직명처럼 위장
    ("ops-team",        "ops@infra.internal"),
    ("dev-lead",        "devlead@company.test"),
    ("qa-engineer",     "qa.eng@testing.local"),
    ("backend-dev",     "backend@service.invalid"),
]

COMMIT_MESSAGES = [
    "fix: typo",
    "chore: update",
    "refactor: cleanup",
    "docs: update readme",
    "test: add test",
    "feat: minor change",
    "style: format",
    "perf: optimize",
]


def run(cmd: list[str], cwd: str, env: dict) -> None:
    result = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[ERROR] {' '.join(cmd)}\n{result.stderr}", file=sys.stderr)
        sys.exit(1)


def generate_commits(repo: str, count: int, interval: float) -> None:
    if not os.path.isdir(os.path.join(repo, ".git")):
        print(f"[ERROR] {repo} 는 git 저장소가 아닙니다.", file=sys.stderr)
        sys.exit(1)

    base_env = os.environ.copy()
    base_env["GIT_CONFIG_NOSYSTEM"] = "1"
    base_env["GIT_CONFIG_GLOBAL"] = os.devnull
    base_env["GIT_TERMINAL_PROMPT"] = "0"

    base_time = datetime.now() - timedelta(days=30)

    print(f"[INFO] {repo} 에 커밋 {count}개 생성 시작")
    for i in range(count):
        author_name, author_email = random.choice(FAKE_AUTHORS)
        message = random.choice(COMMIT_MESSAGES) + f" #{i+1}"

        # 커밋 타임스탬프를 랜덤하게 분산 (탐지 우회 패턴 재현)
        commit_time = base_time + timedelta(
            seconds=random.randint(0, 30 * 24 * 3600)
        )
        git_date = commit_time.strftime("%Y-%m-%dT%H:%M:%S")

        env = base_env.copy()
        env["GIT_AUTHOR_NAME"]     = author_name
        env["GIT_AUTHOR_EMAIL"]    = author_email
        env["GIT_COMMITTER_NAME"]  = author_name
        env["GIT_COMMITTER_EMAIL"] = author_email
        env["GIT_AUTHOR_DATE"]     = git_date
        env["GIT_COMMITTER_DATE"]  = git_date

        run(
            ["git", "commit", "--allow-empty", "--no-verify", "-m", message],
            cwd=repo,
            env=env,
        )

        if (i + 1) % 100 == 0:
            print(f"  {i+1}/{count} 완료")

        if interval > 0:
            time.sleep(interval)

    print(f"[DONE] 커밋 {count}개 생성 완료")


def main() -> None:
    parser = argparse.ArgumentParser(description="커밋 스팸 시뮬레이터 (방어 장치 테스트용)")
    parser.add_argument("--repo",     required=True, help="테스트 git 저장소 경로")
    parser.add_argument("--count",    type=int, default=1500, help="생성할 커밋 수 (기본 1500)")
    parser.add_argument("--interval", type=float, default=0.0, help="커밋 사이 대기 시간(초), 기본 0")
    args = parser.parse_args()

    generate_commits(
        repo=os.path.abspath(args.repo),
        count=args.count,
        interval=args.interval,
    )


if __name__ == "__main__":
    main()
