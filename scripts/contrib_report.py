#!/usr/bin/env python3
"""모듈별/전체 기여도 분석 리포트.

전체 커밋을 취합한 뒤, 작성자 이메일이 실제 GitHub 계정과 연결돼 있는지
GitHub API로 검증하여 연결된 커밋만 집계한다. GitHub login 기준으로 동일인의
여러 별칭(이름)을 하나로 병합하고, 변경 파일 경로를 모듈로 매핑한다.
모듈에 속하지 않는 변경은 ai-harness 또는 etc 로 분류한다.

사용법:
    # 토큰 없이 (시간당 60회 제한, 고유 이메일 수가 적으면 충분)
    python3 scripts/contrib_report.py

    # 권장: 레이트리밋 여유를 위해 토큰 지정
    GITHUB_TOKEN=ghp_xxx python3 scripts/contrib_report.py

    # 라인 수 기준으로 집계
    python3 scripts/contrib_report.py --metric lines

옵션:
    --metric {commits,lines}  기여 측정 기준 (기본: commits)
    --include-merges          머지 커밋 포함
    --no-verify               GitHub API 검증 생략, 휴리스틱(이메일 도메인)으로만 판별
    --out PATH                그래프 저장 경로 (기본: scripts/contrib_report.png)
"""
from __future__ import annotations

import argparse
import json
import os
import re
import ssl
import subprocess
import sys
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path


def _ssl_context() -> ssl.SSLContext:
    """certifi 가 있으면 그 루트 인증서로, 없으면 시스템 기본으로 컨텍스트 생성."""
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        return ssl.create_default_context()


SSL_CTX = _ssl_context()

REPO_ROOT = Path(__file__).resolve().parent.parent
CACHE_FILE = REPO_ROOT / "scripts" / ".contrib_github_cache.json"

# 모듈 외 변경을 분류할 ai-harness 관련 최상위 디렉터리
AI_HARNESS_DIRS = {".harness", ".claude", ".agents", ".codex", ".gemini"}

# RFC 2606/6761 예약 TLD + 명백한 가짜/내부 도메인 (휴리스틱 판별용)
FAKE_TLDS = {
    "test", "invalid", "example", "localhost", "local", "internal",
    "fake", "nowhere",
}


def run_git(args: list[str]) -> str:
    result = subprocess.run(
        ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=True
    )
    return result.stdout


def detect_modules() -> list[str]:
    """cowork-* 디렉터리를 모듈로 자동 감지."""
    return sorted(
        p.name for p in REPO_ROOT.iterdir()
        if p.is_dir() and p.name.startswith("cowork-")
    )


def get_owner_repo() -> tuple[str, str] | None:
    try:
        url = run_git(["remote", "get-url", "origin"]).strip()
    except subprocess.CalledProcessError:
        return None
    m = re.search(r"github\.com[:/]([^/]+)/(.+?)(?:\.git)?$", url)
    return (m.group(1), m.group(2)) if m else None


def classify_path(path: str, modules: list[str]) -> str:
    """파일 경로를 모듈명 / ai-harness / etc 로 분류."""
    top = path.split("/", 1)[0]
    if top in modules:
        return top
    if top in AI_HARNESS_DIRS:
        return "ai-harness"
    return "etc"


def collect_commits(include_merges: bool, rev_range: str | None = None):
    """git log --numstat 파싱. 커밋 단위 리스트 반환.

    rev_range 가 주어지면 해당 범위(예: "main..HEAD")만 집계한다.
    각 항목: {"sha", "email", "name", "files": {module: lines_changed}}
    """
    fmt = "\x01%H\x02%ae\x02%an"
    args = ["log", f"--pretty=format:{fmt}", "--numstat"]
    if not include_merges:
        args.append("--no-merges")
    if rev_range:
        args.append(rev_range)
    out = run_git(args)

    modules = detect_modules()
    commits = []
    cur = None
    for line in out.splitlines():
        if line.startswith("\x01"):
            if cur is not None:
                commits.append(cur)
            sha, email, name = line[1:].split("\x02")
            cur = {"sha": sha, "email": email.lower(), "name": name,
                   "files": defaultdict(int)}
        elif line.strip() and cur is not None:
            parts = line.split("\t")
            if len(parts) != 3:
                continue
            added, deleted, path = parts
            # 바이너리 파일은 '-' 로 표기됨
            lines = (0 if added == "-" else int(added)) + \
                    (0 if deleted == "-" else int(deleted))
            # rename 표기 "old => new" 정규화
            if "=>" in path:
                path = re.sub(r"\{[^}]*=> ?([^}]*)\}", r"\1", path)
                path = path.split(" => ")[-1].strip()
            cur["files"][classify_path(path, modules)] += lines
    if cur is not None:
        commits.append(cur)
    return commits, modules


def login_from_noreply(email: str) -> str | None:
    m = re.match(r"(?:\d+\+)?([^@]+)@users\.noreply\.github\.com$", email)
    return m.group(1) if m else None


def is_fake_email(email: str) -> bool:
    tld = email.rsplit(".", 1)[-1] if "." in email else ""
    return tld in FAKE_TLDS


def load_cache() -> dict:
    if CACHE_FILE.exists():
        return json.loads(CACHE_FILE.read_text())
    return {}


def save_cache(cache: dict) -> None:
    CACHE_FILE.write_text(json.dumps(cache, indent=2, ensure_ascii=False))


def verify_github_login(email: str, sample_sha: str, owner: str, repo: str,
                        cache: dict, token: str | None) -> str | None:
    """이메일이 GitHub 계정에 연결돼 있으면 login 반환, 아니면 None.

    noreply 이메일은 즉시 login 추출. 그 외는 해당 이메일의 대표 커밋을
    조회해 author.login 이 존재하는지 확인한다 (이메일당 1회, 캐싱).
    """
    if email in cache:
        return cache[email]

    login = login_from_noreply(email)
    if login is None and "[bot]" in email:
        login = email.split("@")[0]
    if login is None:
        url = f"https://api.github.com/repos/{owner}/{repo}/commits/{sample_sha}"
        req = urllib.request.Request(url)
        req.add_header("Accept", "application/vnd.github+json")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req, timeout=30, context=SSL_CTX) as resp:
                data = json.load(resp)
            author = data.get("author")
            login = author.get("login") if author else None
        except urllib.error.HTTPError as e:
            if e.code in (403, 429):
                sys.exit("GitHub API 레이트리밋. GITHUB_TOKEN 을 지정하세요.")
            login = None
        except urllib.error.URLError as e:
            # SSL/네트워크 오류를 조용히 None 처리하면 전원 미연결로 오판됨
            sys.exit(f"GitHub API 요청 실패({email}): {e.reason}")

    cache[email] = login
    return login


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--metric", choices=["commits", "lines"], default="commits")
    ap.add_argument("--include-merges", action="store_true")
    ap.add_argument("--no-verify", action="store_true",
                    help="GitHub API 검증 생략, 이메일 도메인 휴리스틱만 사용")
    ap.add_argument("--out", default=str(REPO_ROOT / "scripts" / "contrib_report.png"))
    args = ap.parse_args()

    print("커밋 수집 중...")
    commits, modules = collect_commits(args.include_merges)
    print(f"  전체 커밋 {len(commits)}개, 모듈 {len(modules)}개")

    email_login = resolve_logins(commits, no_verify=args.no_verify)
    linked = {e: l for e, l in email_login.items() if l}
    n_email = len({c["email"] for c in commits})
    print(f"  GitHub 계정 연결 이메일 {len(linked)}/{n_email}개")
    if not linked:
        sys.exit("연결된 GitHub 계정이 없습니다.")

    contrib, totals, cat_order = aggregate(commits, email_login, modules, args.metric)
    authors = sorted(totals, key=lambda a: totals[a], reverse=True)

    print_summary(authors, totals, contrib, cat_order, args.metric)
    write_csv(authors, contrib, cat_order)
    plot(authors, totals, contrib, cat_order, args.metric, args.out)


def resolve_logins(commits, no_verify: bool = False) -> dict[str, str | None]:
    """커밋들의 작성자 이메일 → GitHub login 매핑 (실 계정 검증 + 동일인 병합).

    연결되지 않은 이메일은 None. no_verify 면 도메인 휴리스틱만 사용.
    """
    sample_sha: dict[str, str] = {}
    name_freq: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    for c in commits:
        sample_sha.setdefault(c["email"], c["sha"])
        name_freq[c["email"]][c["name"]] += 1

    owner_repo = get_owner_repo()
    token = os.environ.get("GITHUB_TOKEN")
    cache = load_cache()
    email_login: dict[str, str | None] = {}

    if no_verify or owner_repo is None:
        for email in sample_sha:
            login = login_from_noreply(email)
            if login is None and not is_fake_email(email):
                login = max(name_freq[email].items(), key=lambda x: x[1])[0]
            email_login[email] = login
    else:
        owner, repo = owner_repo
        for email, sha in sample_sha.items():
            email_login[email] = verify_github_login(
                email, sha, owner, repo, cache, token)
        save_cache(cache)

    return canonicalize_logins(email_login)


def canonicalize_logins(email_login: dict[str, str | None]) -> dict[str, str | None]:
    """GitHub login 대소문자 차이를 동일 계정으로 병합 (API 정규 표기 우선)."""
    canon: dict[str, str] = {}
    for email, login in email_login.items():
        if not login:
            continue
        key = login.lower()
        prev = canon.get(key)
        if prev is None or (login_from_noreply(email) is None and prev != login):
            canon[key] = login
    return {e: (canon[l.lower()] if l else None) for e, l in email_login.items()}


def aggregate(commits, email_login, modules, metric):
    """집계 → (contrib[login][cat], totals[login], 정렬된 카테고리 목록)."""
    contrib: dict[str, dict[str, float]] = defaultdict(lambda: defaultdict(float))
    totals: dict[str, float] = defaultdict(float)
    seen: dict[str, set] = defaultdict(set)

    for c in commits:
        login = email_login.get(c["email"])
        if not login:
            continue
        if metric == "commits":
            for mod in c["files"]:
                contrib[login][mod] += 1  # 모듈 터치 1회 = 1
            if c["sha"] not in seen[login]:
                seen[login].add(c["sha"])
                totals[login] += 1
        else:
            for mod, lines in c["files"].items():
                contrib[login][mod] += lines
                totals[login] += lines

    used = {m for d in contrib.values() for m in d}
    cats = [m for m in modules if m in used]
    for sp in ("ai-harness", "etc"):
        if sp in used:
            cats.append(sp)
    return contrib, totals, cats


def print_summary(authors, totals, contrib, cats, metric) -> None:
    unit = "커밋" if metric == "commits" else "라인"
    print(f"\n=== 전체 기여 랭킹 ({unit}) ===")
    grand = sum(totals.values())
    for i, a in enumerate(authors, 1):
        share = totals[a] / grand * 100 if grand else 0
        print(f"  {i:2}. {a:<24} {int(totals[a]):>6} {unit}  ({share:4.1f}%)")

    print(f"\n=== 모듈별 상위 기여자 ===")
    for cat in cats:
        ranked = sorted(
            ((a, contrib[a].get(cat, 0)) for a in authors if contrib[a].get(cat, 0)),
            key=lambda x: x[1], reverse=True)
        if not ranked:
            continue
        top = ", ".join(f"{a}({int(v)})" for a, v in ranked[:3])
        print(f"  {cat:<22} {top}")


def write_csv(authors, contrib, cats) -> None:
    out = REPO_ROOT / "scripts" / "contrib_report.csv"
    with out.open("w") as f:
        f.write("author," + ",".join(cats) + ",total\n")
        for a in authors:
            row = [str(int(contrib[a].get(c, 0))) for c in cats]
            total = sum(contrib[a].get(c, 0) for c in cats)
            f.write(f"{a}," + ",".join(row) + f",{int(total)}\n")
    print(f"\nCSV 저장: {out}")


def plot(authors, totals, contrib, cats, metric, out_path) -> None:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        import numpy as np
    except ImportError:
        print("\nmatplotlib/numpy 미설치 → 그래프 생략. 설치:")
        print("  python3 -m pip install matplotlib numpy")
        return

    for cand in ("AppleGothic", "Apple SD Gothic Neo", "Malgun Gothic", "NanumGothic"):
        if any(cand == f.name for f in matplotlib.font_manager.fontManager.ttflist):
            plt.rcParams["font.family"] = cand
            break
    plt.rcParams["axes.unicode_minus"] = False

    unit = "commits" if metric == "commits" else "lines"
    cmap = plt.get_cmap("tab20")
    color = {a: cmap(i % 20) for i, a in enumerate(authors)}

    fig = plt.figure(figsize=(18, 11))
    gs = fig.add_gridspec(2, 2, height_ratios=[1, 1.1], hspace=0.32, wspace=0.22)

    # (1) 전체 기여 랭킹 (수평 막대)
    ax1 = fig.add_subplot(gs[0, 0])
    y = np.arange(len(authors))
    vals = [totals[a] for a in authors]
    ax1.barh(y, vals, color=[color[a] for a in authors])
    ax1.set_yticks(y)
    ax1.set_yticklabels(authors, fontsize=9)
    ax1.invert_yaxis()
    ax1.set_title(f"전체 기여 랭킹 ({unit})", fontweight="bold")
    grand = sum(vals)
    for yi, v in zip(y, vals):
        ax1.text(v, yi, f" {int(v)} ({v/grand*100:.0f}%)",
                 va="center", fontsize=8)
    ax1.margins(x=0.12)

    # (2) 전체 기여 비율 (파이) - 상위 8 + 기타
    ax2 = fig.add_subplot(gs[0, 1])
    top_n = authors[:8]
    pie_vals = [totals[a] for a in top_n]
    pie_lbls = list(top_n)
    if len(authors) > 8:
        pie_vals.append(sum(totals[a] for a in authors[8:]))
        pie_lbls.append("기타")
    pie_colors = [color[a] for a in top_n] + (["#cccccc"] if len(authors) > 8 else [])
    ax2.pie(pie_vals, labels=pie_lbls, autopct="%1.0f%%", startangle=90,
            colors=pie_colors, textprops={"fontsize": 8})
    ax2.set_title(f"전체 기여 비율 (상위 8, {unit})", fontweight="bold")

    # (3) 모듈별 기여 구성 (수평 누적 막대)
    ax3 = fig.add_subplot(gs[1, 0])
    yc = np.arange(len(cats))
    left = np.zeros(len(cats))
    for a in authors:
        seg = np.array([contrib[a].get(c, 0) for c in cats], dtype=float)
        if seg.sum() == 0:
            continue
        ax3.barh(yc, seg, left=left, color=color[a], label=a)
        left += seg
    ax3.set_yticks(yc)
    ax3.set_yticklabels(cats, fontsize=9)
    ax3.invert_yaxis()
    ax3.set_title(f"모듈별 기여 구성 ({unit})", fontweight="bold")
    ax3.legend(fontsize=7, ncol=2, loc="lower right")
    for yi, tot in zip(yc, left):
        ax3.text(tot, yi, f" {int(tot)}", va="center", fontsize=8)
    ax3.margins(x=0.1)

    # (4) 기여자 x 모듈 히트맵
    ax4 = fig.add_subplot(gs[1, 1])
    mat = np.array([[contrib[a].get(c, 0) for c in cats] for a in authors],
                   dtype=float)
    im = ax4.imshow(mat, aspect="auto", cmap="YlOrRd")
    ax4.set_xticks(np.arange(len(cats)))
    ax4.set_xticklabels(cats, rotation=45, ha="right", fontsize=8)
    ax4.set_yticks(np.arange(len(authors)))
    ax4.set_yticklabels(authors, fontsize=8)
    ax4.set_title(f"기여자 × 모듈 히트맵 ({unit})", fontweight="bold")
    for i in range(len(authors)):
        for j in range(len(cats)):
            v = mat[i, j]
            if v > 0:
                ax4.text(j, i, int(v), ha="center", va="center", fontsize=6,
                         color="black" if v < mat.max() * 0.6 else "white")
    fig.colorbar(im, ax=ax4, fraction=0.04, pad=0.02)

    fig.suptitle("cowork-server 기여도 분석 (GitHub 계정 연결 커밋 기준)",
                 fontsize=15, fontweight="bold")
    fig.savefig(out_path, dpi=130, bbox_inches="tight")
    print(f"그래프 저장: {out_path}")


if __name__ == "__main__":
    main()