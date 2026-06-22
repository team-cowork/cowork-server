#!/usr/bin/env python3
"""PR이 병합되면 기여도가 얼마나 변하는지 계산해 PR 코멘트(markdown)를 생성.

contrib_report.py 의 집계 로직을 재사용한다. base 브랜치 기준 현재 기여도와
base + PR 커밋 기준 기여도를 비교한다. 전체 기여자 리더보드와 전체 모듈
현황을 함께 노출하며, 이번 PR로 증가한 항목은 diff 블록의 초록색으로 강조한다.
시각화는 GitHub 가 네이티브 렌더링하는 Mermaid 차트를 사용한다 (이미지 호스팅 불필요).

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

import contrib_report as cr

OUT_MD = cr.REPO_ROOT / "scripts" / "pr_comment.md"
CHART_PNG = cr.REPO_ROOT / "scripts" / "pr_chart.png"
CHART_PLACEHOLDER = "__CHART_URL__"  # 워크플로우가 업로드 후 raw URL 로 치환
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


def ranks(totals: dict[str, float]) -> dict[str, int]:
    order = sorted(totals, key=lambda a: totals[a], reverse=True)
    return {a: i + 1 for i, a in enumerate(order)}


def project_totals(contrib: dict[str, dict[str, float]], cats) -> dict[str, int]:
    return {c: int(sum(contrib[a].get(c, 0) for a in contrib)) for c in cats}


def signed(n: int) -> str:
    return f"+{n:,}" if n > 0 else f"{n:,}"


def build_comment(base_sha: str) -> str:
    base_commits, modules = cr.collect_commits(False, rev_range=base_sha)
    pr_commits, _ = cr.collect_commits(False, rev_range=f"{base_sha}..HEAD")

    if not pr_commits:
        return (f"{MARKER}\n## 이 PR이 병합되면 — 기여도 변화 미리보기\n\n"
                "집계할 신규 커밋이 없습니다 (머지 커밋 제외).")

    # base + PR 커밋을 함께 login 해석 (PR 작성자 일관 매핑)
    email_login = cr.resolve_logins(base_commits + pr_commits)
    pr_author = os.environ.get("PR_AUTHOR")
    if pr_author:
        for c in pr_commits:
            if not email_login.get(c["email"]):
                email_login[c["email"]] = pr_author

    # 커밋/라인 두 지표 모두 집계: agg[metric] = (before_contrib, before_totals,
    #                                           after_contrib, after_totals)
    agg = {}
    for metric in ("commits", "lines"):
        bc, bt, _ = cr.aggregate(base_commits, email_login, modules, metric)
        ac, at, _ = cr.aggregate(base_commits + pr_commits, email_login, modules, metric)
        agg[metric] = (bc, bt, ac, at)

    full_cats = list(modules) + ["ai-harness", "etc"]

    # 이 PR 전체 증가량 요약
    _, bt_c, _, at_c = agg["commits"]
    _, bt_l, _, at_l = agg["lines"]
    d_commits = int(sum(at_c.values()) - sum(bt_c.values()))
    d_lines = int(sum(at_l.values()) - sum(bt_l.values()))
    n_contrib = sum(1 for a in at_c if at_c[a] - bt_c.get(a, 0) > 0)

    sections = [
        MARKER,
        "## 이 PR이 병합되면 — 기여도 변화 미리보기",
        "",
        f"**커밋 {signed(d_commits)}** · **라인 {signed(d_lines)}** · "
        f"기여자 {n_contrib}명 증가 · GitHub 계정에 연결된 커밋만 집계",
        "",
    ]
    sections += render_leaderboard(agg)
    sections += render_module_chart(agg, full_cats)
    sections += render_module_detail(agg, full_cats)
    sections.append(
        f"<sub>base <code>{base_sha[:8]}</code> 기준 · 머지 커밋 제외</sub>")
    return "\n".join(sections)


def render_leaderboard(agg) -> list[str]:
    bc_c, bt_c, ac_c, at_c = agg["commits"]
    _, bt_l, _, at_l = agg["lines"]
    r_before, r_after = ranks(bt_c), ranks(at_c)
    authors = sorted(at_c, key=lambda a: at_c[a], reverse=True)

    rows = []
    for a in authors:
        bcc, acc = int(bt_c.get(a, 0)), int(at_c.get(a, 0))
        bll, all_ = int(bt_l.get(a, 0)), int(at_l.get(a, 0))
        rb, ra = r_before.get(a), r_after.get(a)
        move = f"{rb} -> {ra}" if rb else f"new -> {ra}"
        commits_col = f"{bcc:,} -> {acc:,} ({signed(acc - bcc)})"
        lines_col = f"{bll:,} -> {all_:,} ({signed(all_ - bll)})"
        changed = (acc - bcc) > 0 or (all_ - bll) > 0
        rows.append((changed, a, commits_col, lines_col, move))

    wa = max([len("AUTHOR")] + [len(r[1]) for r in rows])
    wc = max([len("COMMITS")] + [len(r[2]) for r in rows])
    wl = max([len("LINES")] + [len(r[3]) for r in rows])

    out = [
        "### 전체 기여자 리더보드 (병합 후 기준)",
        "이번 PR로 기여가 증가한 사람은 초록색으로 강조됩니다.",
        "",
        "```diff",
        f"  {'AUTHOR':<{wa}}  {'COMMITS':<{wc}}  {'LINES':<{wl}}  RANK",
    ]
    for changed, a, c, l, m in rows:
        prefix = "+" if changed else " "
        out.append(f"{prefix} {a:<{wa}}  {c:<{wc}}  {l:<{wl}}  {m}")
    out += ["```", ""]
    return out


def render_module_chart(agg, cats) -> list[str]:
    """이 PR이 작업한 모듈만(Δ>0) 모던 가로 막대 PNG 로 렌더, 이미지 블록 반환."""
    bc_c, _, ac_c, _ = agg["commits"]
    bc_l, _, ac_l, _ = agg["lines"]
    before_c, after_c = project_totals(bc_c, cats), project_totals(ac_c, cats)
    before_l, after_l = project_totals(bc_l, cats), project_totals(ac_l, cats)

    worked = [(c, after_c[c] - before_c[c], after_l[c] - before_l[c]) for c in cats]
    worked = [w for w in worked if w[1] > 0 or w[2] > 0]
    worked.sort(key=lambda w: (w[1], w[2]), reverse=True)
    if not worked or not generate_chart(worked):
        return []
    return [
        "### 이 PR이 추가하는 모듈별 커밋",
        f"![모듈별 증가분]({CHART_PLACEHOLDER})",
        "",
    ]


def _set_korean_font(plt, matplotlib) -> None:
    for cand in ("AppleGothic", "Apple SD Gothic Neo", "Malgun Gothic",
                 "NanumGothic", "DejaVu Sans"):
        if any(cand == f.name for f in matplotlib.font_manager.fontManager.ttflist):
            plt.rcParams["font.family"] = cand
            break
    plt.rcParams["axes.unicode_minus"] = False


def generate_chart(worked) -> bool:
    """worked=[(module, +commits, +lines)] → CHART_PNG 저장. matplotlib 없으면 False."""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        import numpy as np
    except ImportError:
        return False

    _set_korean_font(plt, matplotlib)
    mods = [w[0] for w in worked]
    dcs = [w[1] for w in worked]
    dls = [w[2] for w in worked]
    n = len(mods)
    vmax = max(dcs) or 1

    fig, ax = plt.subplots(figsize=(9, max(2.2, 0.62 * n + 1.2)), dpi=150)
    y = np.arange(n)[::-1]  # 가장 큰 값을 위로
    cmap = plt.get_cmap("viridis")
    colors = [cmap(0.12 + 0.72 * (v / vmax)) for v in dcs]
    ax.barh(y, dcs, color=colors, height=0.62, zorder=3)

    ax.set_yticks(y)
    ax.set_yticklabels(mods, fontsize=11)
    ax.set_xlim(0, vmax * 1.22)
    for sp in ("top", "right", "left"):
        ax.spines[sp].set_visible(False)
    ax.tick_params(left=False)
    ax.xaxis.grid(True, color="#ececec", zorder=0)
    ax.set_axisbelow(True)
    for yi, dc, dl in zip(y, dcs, dls):
        ax.text(dc + vmax * 0.015, yi, f"+{dc:,}  ·  +{dl:,} lines",
                va="center", ha="left", fontsize=9.5, color="#444")
    ax.set_title("이 PR이 추가하는 모듈별 커밋 (증가분)",
                 fontsize=14, fontweight="bold", loc="left", pad=12)
    ax.set_xlabel("+commits", fontsize=10, color="#777")
    fig.savefig(CHART_PNG, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    return True


def render_module_detail(agg, cats) -> list[str]:
    bc_c, _, ac_c, _ = agg["commits"]
    bc_l, _, ac_l, _ = agg["lines"]
    before_c, after_c = project_totals(bc_c, cats), project_totals(ac_c, cats)
    before_l, after_l = project_totals(bc_l, cats), project_totals(ac_l, cats)

    rows = []
    for c in cats:
        dc, dl = after_c[c] - before_c[c], after_l[c] - before_l[c]
        rows.append((dc > 0 or dl > 0, c, after_c[c], dc, after_l[c], dl))
    rows.sort(key=lambda r: (r[3], r[5]), reverse=True)

    def fmt_cell(total: int, delta: int) -> str:
        return f"{total:,} ({signed(delta)})" if delta else f"{total:,}"

    cell_c = [fmt_cell(r[2], r[3]) for r in rows]
    cell_l = [fmt_cell(r[4], r[5]) for r in rows]
    wm = max([len("MODULE")] + [len(r[1]) for r in rows])
    wc = max([len("COMMITS")] + [len(s) for s in cell_c])
    wl = max([len("LINES")] + [len(s) for s in cell_l])

    out = [
        "### 모듈별 변화 상세 (전체 모듈)",
        "이번 PR이 커밋을 추가한 모듈은 초록색으로 강조됩니다.",
        "",
        "```diff",
        f"  {'MODULE':<{wm}}  {'COMMITS':<{wc}}  {'LINES':<{wl}}",
    ]
    for (changed, name, _, _, _, _), cc, lc in zip(rows, cell_c, cell_l):
        prefix = "+" if changed else " "
        out.append(f"{prefix} {name:<{wm}}  {cc:<{wc}}  {lc:<{wl}}")
    out += ["```", ""]
    return out


def main() -> None:
    base_sha = resolve_base()
    md = build_comment(base_sha)
    OUT_MD.write_text(md)
    print(f"코멘트 생성: {OUT_MD}")
    print("-" * 60)
    print(md)


if __name__ == "__main__":
    main()
