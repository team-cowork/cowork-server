#!/usr/bin/env python3
"""PR이 병합되면 기여도가 얼마나 증가하는지 계산해 PR 코멘트(markdown)를 생성.

contrib_report.py 의 집계 로직을 재사용한다. base 브랜치 기준 현재 기여도와
base + PR 커밋 기준 기여도를 비교해, PR 기여자별 증가분 / 랭킹 변화 / 모듈별
증가량을 산출한다. 커밋 수와 라인 수를 모두 표기하며, GitHub 가 네이티브
렌더링하는 Mermaid 차트와 색상 텍스트(diff 블록·텍스트 막대)를 함께 출력한다.

CI(GitHub Actions)에서 다음 환경변수로 동작:
    GITHUB_TOKEN   GitHub API 토큰 (실 계정 검증/레이트리밋)
    BASE_REF       PR base 브랜치명 (예: develop, main)
    PR_AUTHOR      PR 작성자 login (이메일 미연결 커밋의 폴백)
출력:
    scripts/pr_comment.md   PR 코멘트 본문
"""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

import contrib_report as cr

OUT_MD = cr.REPO_ROOT / "scripts" / "pr_comment.md"
MARKER = "<!-- contrib-delta-bot -->"  # 기존 코멘트 식별용


def rev_parse(ref: str) -> str | None:
    try:
        return cr.run_git(["rev-parse", ref]).strip()
    except subprocess.CalledProcessError:
        return None


def resolve_base() -> str:
    """base 커밋 SHA 결정: BASE_REF 와 HEAD 의 merge-base."""
    base_ref = os.environ.get("BASE_REF", "main")
    for cand in (f"origin/{base_ref}", base_ref, "origin/main", "main"):
        if rev_parse(cand):
            try:
                return cr.run_git(["merge-base", cand, "HEAD"]).strip()
            except subprocess.CalledProcessError:
                continue
    raise SystemExit("base 브랜치를 찾을 수 없습니다. BASE_REF 를 확인하세요.")


def rank_of(author: str, totals: dict[str, float]) -> int | None:
    order = sorted(totals, key=lambda a: totals[a], reverse=True)
    return order.index(author) + 1 if author in order else None


def text_bar(value: float, vmax: float, width: int = 12) -> str:
    if vmax <= 0:
        return "─" * 0
    filled = round(value / vmax * width)
    return "█" * filled + "░" * (width - filled)


def build_comment(base_sha: str) -> str:
    include_merges = False
    base_commits, modules = cr.collect_commits(include_merges, rev_range=base_sha)
    pr_commits, _ = cr.collect_commits(include_merges, rev_range=f"{base_sha}..HEAD")

    if not pr_commits:
        return (f"{MARKER}\n## 📊 기여도 변화 미리보기\n\n"
                "이 PR에는 집계할 신규 커밋이 없습니다 (머지 커밋 제외).")

    # base + PR 커밋을 함께 login 해석 (PR 작성자 일관 매핑)
    email_login = cr.resolve_logins(base_commits + pr_commits)
    pr_author = os.environ.get("PR_AUTHOR")
    if pr_author:
        # 연결 안 된 PR 커밋 이메일은 PR 작성자 login 으로 귀속
        for c in pr_commits:
            if not email_login.get(c["email"]):
                email_login[c["email"]] = pr_author

    sections: list[str] = [MARKER, "## 📊 이 PR이 병합되면 — 기여도 변화 미리보기", ""]

    # 커밋/라인 두 기준 모두 집계
    agg = {}
    for metric in ("commits", "lines"):
        b_contrib, b_totals, _ = cr.aggregate(base_commits, email_login, modules, metric)
        a_contrib, a_totals, cats = cr.aggregate(
            base_commits + pr_commits, email_login, modules, metric)
        agg[metric] = (b_contrib, b_totals, a_contrib, a_totals, cats)

    # PR 기여자: after-before 차이가 있는 login (커밋 기준)
    _, b_tot_c, _, a_tot_c, _ = agg["commits"]
    contributors = sorted(
        (a for a in a_tot_c if a_tot_c[a] - b_tot_c.get(a, 0) > 0),
        key=lambda a: a_tot_c[a] - b_tot_c.get(a, 0), reverse=True)

    if not contributors:
        sections.append("> GitHub 계정에 연결된 신규 기여가 없어 변화를 집계하지 못했습니다.")
        return "\n".join(sections)

    # ── 요약 (diff 블록: 증가는 초록) ─────────────────────────────
    sections.append("### ✏️ 기여자별 증가분 & 랭킹 변화")
    diff_lines = ["```diff"]
    for a in contributors:
        parts = []
        for metric, unit in (("commits", "커밋"), ("lines", "라인")):
            b_c, b_t, a_c, a_t, _ = agg[metric]
            before, after = b_t.get(a, 0), a_t.get(a, 0)
            parts.append(f"{unit} {int(before)}→{int(after)} (+{int(after-before)})")
        # 랭킹 변화 (커밋 기준)
        r_before = rank_of(a, b_tot_c)
        r_after = rank_of(a, a_tot_c)
        if r_before is None:
            rank_txt = f"랭킹 신규진입 → {r_after}위"
        elif r_after < r_before:
            rank_txt = f"랭킹 {r_before}위 🔺 {r_after}위"
        elif r_after > r_before:
            rank_txt = f"랭킹 {r_before}위 🔻 {r_after}위"
        else:
            rank_txt = f"랭킹 {r_after}위 (유지)"
        diff_lines.append(f"+ {a:<18} {' | '.join(parts)} | {rank_txt}")
    diff_lines.append("```")
    sections += diff_lines + [""]

    # ── 대표 기여자 모듈별 증가 (Mermaid 차트) ────────────────────
    top = contributors[0]
    b_c_c, _, a_c_c, _, cats_c = agg["commits"]
    mod_delta = [(cat, a_c_c[top].get(cat, 0) - b_c_c.get(top, {}).get(cat, 0))
                 for cat in cats_c]
    mod_delta = [(c, d) for c, d in mod_delta if d > 0]
    mod_delta.sort(key=lambda x: x[1], reverse=True)

    if mod_delta:
        sections.append(f"### 📦 `{top}` 모듈별 커밋 증가 (Mermaid)")
        xlabels = ", ".join(f'"{c}"' for c, _ in mod_delta)
        yvals = ", ".join(str(int(d)) for _, d in mod_delta)
        sections += [
            "```mermaid",
            "xychart-beta",
            f'    title "이 PR이 병합되면 모듈별 +커밋 ({top})"',
            f"    x-axis [{xlabels}]",
            '    y-axis "+commits"',
            f"    bar [{yvals}]",
            "```",
            "",
        ]

        # 텍스트 막대(색상 미지원 환경 폴백) — 커밋/라인 동시 표기
        b_c_l, _, a_c_l, _, _ = agg["lines"]
        vmax = max(d for _, d in mod_delta)
        sections.append("### 📈 모듈별 증가 상세")
        rows = ["| 모듈 | +커밋 | +라인 | |", "|---|---:|---:|---|"]
        for cat, d in mod_delta:
            dl = a_c_l[top].get(cat, 0) - b_c_l.get(top, {}).get(cat, 0)
            rows.append(f"| `{cat}` | +{int(d)} | +{int(dl)} | `{text_bar(d, vmax)}` |")
        sections += rows + [""]

    sections.append(
        f"<sub>base `{base_sha[:8]}` 기준 · 머지 커밋 제외 · "
        "GitHub 계정에 연결된 커밋만 집계</sub>")
    return "\n".join(sections)


def main() -> None:
    base_sha = resolve_base()
    md = build_comment(base_sha)
    OUT_MD.write_text(md)
    print(f"코멘트 생성: {OUT_MD}")
    print("-" * 60)
    print(md)


if __name__ == "__main__":
    main()