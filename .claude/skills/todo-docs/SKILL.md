---
name: todo-docs
description: Author and maintain docs/todo — items/ detail specs, {YYYYMMDD}_TODO.md session snapshots, and the README index. Covers directory numbering, the Korean document template, writing style, and the completion lifecycle (strike through on completion; delete only once a whole snapshot is done).
allowed-tools: Bash, Read, Write, Edit
---

# TODO Documents

## Layout

```text
docs/todo/
├── README.md                       # index: 진행 중 list, 점검 스냅샷 list, 작성 규칙
├── {YYYYMMDD}_TODO.md              # snapshot of one review session — summary table only
└── items/{순번}-{범주}/{작업명}.md   # the actual work spec
```

- A snapshot records *what one review session found*. It never holds the spec itself — only a
  summary table linking into `items/`.
- An item doc is the single source of truth for one unit of work. It outlives the session that
  found it.

**Every new item goes into a snapshot — there is no such thing as an item outside one.** The
snapshot is keyed by the date the item was raised:

- A snapshot already exists for today's date → append a row to its 요약 테이블, numbering
  continues from the last row.
- No snapshot for today → create `{YYYYMMDD}_TODO.md` with today's date and start at `# 1`, even
  for a single item.

Never backdate an item into an older snapshot to avoid creating a new file. A snapshot's date is
when that review happened, and the completion lifecycle below deletes items per snapshot — mixing
dates would tie an item's cleanup to a session it did not belong to.

## Choosing the directory number

`{순번}` is a **global sequence in creation order**, not a fixed per-category number. Completed
directories are deleted, so gaps are normal and numbers are never reused.

Find the next number from history, not from `ls`:

```bash
git log --all --diff-filter=A --name-only --format='' -- 'docs/todo/items/*' \
  | grep -o 'items/[0-9]*-[a-z]*' | sort -u
```

Take the highest number ever used and add 1. `{범주}` is one lowercase English word — existing ones
are `cleanup`, `security`, `configuration`, `api`, `monitoring`, `storage`, `dependency`.

## Item doc template

```markdown
# {작업명}

- **서비스**: {영향받는 모듈·인프라를 쉼표로 나열}
- **우선순위**: 🔴 높음 | 🟠 중간 | 🟡 낮음 | 🟢 검토
- **현재 상태**: {지금 상태를 한 문장으로}

## 문제

{현재 구성이 실제로 어떤지 사실부터 쓰고, 그것이 왜 불충분한지로 이어간다. 2~4문단.}

## {주제별 중간 섹션}

{결정할 선택지, 정책 범위, 경로 규칙 등. 선택지는 표로 정리한다.}

## 할 일

### {하위 묶음}

- {행위를 `~한다`로 쓴다}

## 검증

- {테스트·확인 항목}

## 완료 조건

- {결과 상태를 `~되어 있다` / `~하지 않는다`로 쓴다}
```

Optional metadata lines, placed with the required three:

- `- **선행 작업**: [{제목}]({경로})` — must finish first
- `- **관련 작업**: [{제목}]({경로})` — runs alongside
- `- **파생 원본**: [{제목}]({경로})` — split out of another item
- `- **결론**: {부분 완료 시 남은 범위}`

Add progress without rewriting the doc — either a quote block right under the metadata, or a
`## 진행 상태 ({YYYY-MM-DD})` table:

```markdown
> **2026-08-28 진척:** {무엇을 했고 무엇이 아직 검증되지 않았는지}
```

## Snapshot doc template

```markdown
# {점검 주제}

{YYYY-MM-DD} 기준으로 {점검 대상}을 점검한 결과, 후속 작업이 필요한 항목입니다.

세부 사항은 [`docs/todo/items/`](./items/) 디렉터리의 하위 문서를 참고하세요.

---

## 요약 테이블

| # | 항목 | 서비스 | 우선순위 | 세부 문서 |
|---|------|--------|----------|-----------|
| 1 | {작업명} | {모듈 나열} | 🔴 | [바로가기](./items/{순번}-{범주}/{작업명}.md) |
```

The H1 names the theme, not the date — `서버 의존성 관리 필요 항목`, `서버 외부 API 계약 필요 항목`.
The date lives in the filename and the intro sentence.

Register a new snapshot in README `## 점검 스냅샷`, newest first:

```markdown
- [{YYYYMMDD}](./{YYYYMMDD}_TODO.md) — {점검 대상} 점검
```

## Writing style

- Plain declarative `~한다` / `~되어 있다`. Never `~하세요`, `~해야 합니다`, `~할 것`.
- `## 할 일` states **actions** (`~을 적용한다`); `## 완료 조건` states **resulting state**
  (`~는 거부된다`). Do not let the two sections repeat each other.
- Backtick every path, config key, endpoint, and command: `` `/api/health` ``,
  `` `cowork-monitoring/prometheus/prometheus.yml` ``.
- Assert only what you verified. When something is unconfirmed, say so in the doc
  (`아직 검증하지 않았다`) rather than writing it as fact.
- Do not put a completion date or an owner in the doc. Git history already records both.

## Completion lifecycle

Detail docs are **kept** until the snapshot they belong to is entirely finished. Never delete an
item doc just because that one item shipped.

### An item is completed

1. In the snapshot's summary table, strike through the **항목** and **서비스** cells only:

   ```markdown
   | 1 | ~~외부 API 모듈 네임스페이스 통일~~ | ~~Gateway, 외부 HTTP API 10개~~ | 🟠 | [바로가기](./items/10-api/public-route-namespace-migration.md) |
   ```

   The 우선순위 emoji and the `[바로가기]` link stay live.
2. Strike through the matching line in README `## 진행 중`, keeping the link:

   ```markdown
   - ~~api: [외부 API 모듈 네임스페이스 통일](./items/10-api/public-route-namespace-migration.md)~~
   ```

3. Leave `items/{순번}-{범주}/{작업명}.md` on disk, untouched or with a progress note appended.

Nothing is deleted at this stage, so no cross-reference can break.

### Every item in a snapshot is completed

Only now, delete:

- every `items/` doc that snapshot owns,
- the `{YYYYMMDD}_TODO.md` file itself,
- its line in README `## 점검 스냅샷`,
- its lines in README `## 진행 중`.

Do **not** add a `# 전체 완료됨.` marker or leave a tombstone snapshot behind — remove the files.

Before deleting, find every inbound reference and rewrite it to plain text so no link dangles:

```bash
grep -rn "{삭제할-파일명}" docs/
```

```diff
-- **파생 원본**: [외부 API 모듈 네임스페이스 통일](../10-api/public-route-namespace-migration.md)
+- **파생 원본**: 외부 API 모듈 네임스페이스 통일 (완료·문서 제거)
```

An item that other live items still list as `선행 작업` cannot be deleted yet, even if its own
snapshot is finished. Resolve the dependency first.

## Creating a new item — checklist

- [ ] Next `{순번}` taken from `git log`, not from the current directory listing
- [ ] Metadata lines present: **서비스**, **우선순위**, **현재 상태**
- [ ] `## 문제` states current configuration as fact before arguing for the change
- [ ] `## 할 일` and `## 완료 조건` are actions vs. states, not duplicates
- [ ] Added to README `## 진행 중` as `- {범주}: [{제목}]({경로})`
- [ ] Added to today's snapshot — appended to the existing one, or a new `{YYYYMMDD}_TODO.md`
      registered in README `## 점검 스냅샷` (newest first)
- [ ] Every referenced path and key verified to exist
