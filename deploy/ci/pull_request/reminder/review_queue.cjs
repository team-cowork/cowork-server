'use strict';

const { execFileSync } = require('node:child_process');

const JEB_URL = 'https://jeb.https.gsmsv.site/v1/systemone';
const JEB_TIMEOUT_MS = 60_000;
// jeb rejects input over about 8,000 tokens with HTTP 502, so the second attempt halves the diff.
const DIFF_CHAR_LIMITS = [12_000, 6_000];
const BODY_CHAR_LIMIT = 2_000;
const STAT_CHAR_LIMIT = 2_000;
const RETRYABLE_STATUSES = new Set([502, 503]);
const FACTOR_THRESHOLD = 0.5;
// Cap the reminder at 15 PRs and split long groups at Discord's embed description limit.
const MAX_LISTED = 15;
const EMBED_DESCRIPTION_CHAR_LIMIT = 4_096;
const TITLE_CHAR_LIMIT = 100;
const HOUR_MS = 3_600_000;
const KST_OFFSET_MS = 9 * HOUR_MS;
const MORNING_END_HOUR_KST = 12;
const HIGH_REMINDER_MIN_AGE_MS = 4 * HOUR_MS;
const LOW_REMINDER_INTERVAL_DAYS = 3;
const ROLE_ID_PATTERN = /^\d{17,20}$/;
const NEW_PULL_REQUEST_COLOR = 0x3e63dd;

const GRADES = [
  { key: 'high', label: '높음', color: 0xe5484d },
  { key: 'normal', label: '보통', color: 0xf5a524 },
  { key: 'low', label: '낮음', color: 0x46a758 },
  { key: 'unrated', label: '미평가', color: 0x8b8d98 },
];
const SCORE_GRADES = ['low', 'normal', 'high'];
const FACTORS = { security: '보안', data: '데이터', availability: '가용성' };

const QUESTIONS = {
  importance: {
    type: 'score',
    instructions: '이 Pull Request를 다른 Pull Request보다 먼저 리뷰해야 하는 정도는 어느 단계입니까?',
    criteria: [
      '이 변경을 늦게 리뷰해도 사용자, 데이터, 운영에 영향이 없습니다. 문서, 주석, 이름 변경, 동작이 같은 정리 작업이 여기에 해당합니다.',
      '이 변경을 늦게 리뷰하면 일부 기능의 제공이 늦어집니다. 일반 기능 추가, 일반 버그 수정, 의존성 갱신이 여기에 해당합니다.',
      '이 변경을 늦게 리뷰하면 장애, 보안 문제, 데이터 손상이 계속될 수 있습니다. 인증과 권한 수정, 데이터 구조 변경, 배포 장애 수정이 여기에 해당합니다.',
    ],
  },
  security: { type: 'noul', instructions: '이 변경은 인증, 권한, 비밀값, 입력 검증의 동작을 바꿉니까?' },
  data: { type: 'noul', instructions: '이 변경은 데이터베이스 구조, 저장되는 데이터, 메시지 형식을 바꿉니까?' },
  availability: { type: 'noul', instructions: '이 변경은 서비스 시작, 배포, 서비스 간 통신의 동작을 바꿉니까?' },
};

const OPEN_PULL_REQUESTS_QUERY = `
  query($owner: String!, $repo: String!, $after: String) {
    repository(owner: $owner, name: $repo) {
      pullRequests(states: OPEN, first: 100, after: $after) {
        pageInfo { hasNextPage endCursor }
        nodes { number title body url baseRefName isDraft createdAt reviewDecision author { login } }
      }
    }
  }
`;

function truncate(text, limit) {
  return text.length > limit ? `${text.slice(0, limit)}…` : text;
}

function readRoleId(env, core) {
  const roleId = env.DISCORD_REVIEW_MENTION_ROLE_ID?.trim();
  if (!roleId) return null;
  if (ROLE_ID_PATTERN.test(roleId)) return roleId;
  core.warning('Ignored invalid DISCORD_REVIEW_MENTION_ROLE_ID');
  return null;
}

function kstDayNumber(date) {
  return Math.floor((date.getTime() + KST_OFFSET_MS) / (24 * HOUR_MS));
}

function isDue(item, now) {
  const created = new Date(item.createdAt);
  if (item.grade === 'high') return now.getTime() - created.getTime() >= HIGH_REMINDER_MIN_AGE_MS;

  const morning = new Date(now.getTime() + KST_OFFSET_MS).getUTCHours() < MORNING_END_HOUR_KST;
  const elapsedDays = kstDayNumber(now) - kstDayNumber(created);
  if (!morning || elapsedDays <= 0) return false;
  return item.grade !== 'low' || elapsedDays % LOW_REMINDER_INTERVAL_DAYS === 0;
}

function gradeFromScore(score) {
  return SCORE_GRADES[Math.min(Math.max(Math.round(score), 0), SCORE_GRADES.length - 1)];
}

function parseEvaluation(body) {
  const answers = body?.answers;
  const score = answers?.importance?.score;
  if (!Number.isFinite(score)) throw new Error('jeb response has no importance score');
  return {
    grade: gradeFromScore(score),
    factors: Object.keys(FACTORS).filter(key => answers[key]?.noul >= FACTOR_THRESHOLD),
  };
}

function buildState(pullRequest, diff, diffLimit) {
  return {
    title: pullRequest.title,
    description: truncate((pullRequest.body ?? '').replace(/<!--[\s\S]*?-->/g, '').trim(), BODY_CHAR_LIMIT),
    changed_files: truncate(diff.stat, STAT_CHAR_LIMIT),
    diff: truncate(diff.patch, diffLimit),
  };
}

async function requestJeb(state, apiKey, fetchImpl) {
  const response = await fetchImpl(JEB_URL, {
    method: 'POST',
    headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: 'jeb', state, questions: QUESTIONS }),
    signal: AbortSignal.timeout(JEB_TIMEOUT_MS),
  });
  if (!response.ok) {
    throw Object.assign(new Error(`jeb returned HTTP ${response.status}`), { status: response.status });
  }
  return response.json();
}

async function evaluate(pullRequest, diff, apiKey, fetchImpl) {
  if (!diff.patch) return { grade: 'low', factors: [] };

  let lastError;
  for (const diffLimit of DIFF_CHAR_LIMITS) {
    try {
      return parseEvaluation(await requestJeb(buildState(pullRequest, diff, diffLimit), apiKey, fetchImpl));
    } catch (error) {
      lastError = error;
      if (!RETRYABLE_STATUSES.has(error.status)) break;
    }
  }
  throw lastError;
}

function sortQueue(queue) {
  const rank = item => GRADES.findIndex(grade => grade.key === item.grade);
  return [...queue].sort((left, right) =>
    rank(left) - rank(right)
    || Date.parse(left.createdAt) - Date.parse(right.createdAt)
    || left.number - right.number);
}

function formatWaiting(createdAt, now) {
  const hours = Math.max(Math.floor((now.getTime() - Date.parse(createdAt)) / 3_600_000), 0);
  return hours < 24 ? `${hours}시간 대기` : `${Math.floor(hours / 24)}일 ${hours % 24}시간 대기`;
}

function formatTitle(item) {
  // Preserve complete code spans; escape formatting only outside them.
  const title = truncate(item.title, TITLE_CHAR_LIMIT).replace(
    /(?<!`)(`+)(?!`)[\s\S]*?(?<!`)\1(?!`)|[\\`*_~|>[\]()]/g,
    (match, codeDelimiter) => codeDelimiter ? match : `\\${match}`,
  );
  return `[#${item.number}](${item.url}) ${title}`;
}

function formatLine(item, now) {
  const details = [item.author, formatWaiting(item.createdAt, now)];
  if (item.factors.length > 0) details.push(item.factors.map(key => FACTORS[key]).join(', '));
  return `${formatTitle(item)}\n└ ${details.join(' · ')}`;
}

function formatMention(roleId) {
  return roleId ? `<@&${roleId}>` : '';
}

function allowedMentions(roleId) {
  return { parse: [], roles: roleId ? [roleId] : [] };
}

function buildNewPullRequestPayload(item, roleId) {
  return {
    content: `${formatMention(roleId)} 새 PR의 리뷰를 요청합니다.`.trim(),
    embeds: [{
      title: '새 PR',
      color: NEW_PULL_REQUEST_COLOR,
      description: `${formatTitle(item)}\n└ ${item.author}`,
    }],
    allowed_mentions: allowedMentions(roleId),
  };
}

function buildPayload(queue, roleId, now) {
  const listed = queue.slice(0, MAX_LISTED);
  const embeds = GRADES.flatMap(grade => {
    const items = listed.filter(item => item.grade === grade.key);
    if (items.length === 0) return [];
    const groups = [[]];
    for (const item of items) {
      const line = formatLine(item, now);
      if ([...groups.at(-1), line].join('\n').length > EMBED_DESCRIPTION_CHAR_LIMIT) groups.push([]);
      groups.at(-1).push(line);
    }
    return groups.map(lines => ({
      title: `중요도 ${grade.label} (${lines.length})`,
      color: grade.color,
      description: lines.join('\n'),
    }));
  });

  // Discord does not notify a role mentioned inside an embed, so the mention stays in content.
  const hidden = queue.length > listed.length ? ` 상위 ${listed.length}개만 표시합니다.` : '';
  return {
    content: `${formatMention(roleId)} 리뷰 대기 PR ${queue.length}개입니다.${hidden}`.trim(),
    embeds,
    allowed_mentions: allowedMentions(roleId),
  };
}

function readPullRequestDiff(number, baseRefName) {
  const git = args => execFileSync('git', args, {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
  const ref = `refs/review-queue/${number}/head`;
  const baseRef = `refs/review-queue/${number}/base`;
  git(['fetch', '--no-tags', '--quiet', 'origin',
    `+refs/pull/${number}/head:${ref}`, `+refs/heads/${baseRefName}:${baseRef}`]);

  const range = `${baseRef}...${ref}`;
  const diff = ['diff', '--no-ext-diff', '--no-textconv', '-w', '--ignore-blank-lines'];
  return {
    patch: git([...diff, '--unified=1', range]),
    stat: git([...diff, '--stat=120', '--stat-count=50', range]),
  };
}

async function publishReviewQueue({
  github,
  context,
  core,
  env = process.env,
  fetchImpl = fetch,
  readDiff = readPullRequestDiff,
  now = new Date(),
}) {
  const apiKey = env.JEB_API_KEY;
  const webhookUrl = env.DISCORD_REVIEW_QUEUE_WEBHOOK_URL;
  if (!apiKey || !webhookUrl) {
    core.setFailed('JEB_API_KEY and DISCORD_REVIEW_QUEUE_WEBHOOK_URL are required');
    return;
  }

  const pullRequests = [];
  let after = null;
  do {
    const result = await github.graphql(OPEN_PULL_REQUESTS_QUERY, { ...context.repo, after });
    const page = result.repository.pullRequests;
    pullRequests.push(...page.nodes.filter(pullRequest =>
      !pullRequest.isDraft && pullRequest.reviewDecision !== 'APPROVED'));
    after = page.pageInfo.hasNextPage ? page.pageInfo.endCursor : null;
  } while (after);
  if (pullRequests.length === 0) {
    core.info('No pull request is waiting for review');
    return;
  }

  const queue = [];
  for (const pullRequest of pullRequests) {
    let evaluation = { grade: 'unrated', factors: [] };
    try {
      evaluation = await evaluate(pullRequest, readDiff(pullRequest.number, pullRequest.baseRefName), apiKey, fetchImpl);
    } catch (error) {
      core.warning(`Could not evaluate PR #${pullRequest.number}: ${error.message}`);
    }
    queue.push({
      number: pullRequest.number,
      title: pullRequest.title,
      url: pullRequest.url,
      createdAt: pullRequest.createdAt,
      author: pullRequest.author?.login ?? 'ghost',
      ...evaluation,
    });
  }

  const due = sortQueue(queue.filter(item => isDue(item, now)));
  if (due.length === 0) {
    core.info('No pull request is due for a reminder');
    return;
  }
  await sendToDiscord(webhookUrl, buildPayload(due, readRoleId(env, core), now), fetchImpl, core);
}

async function publishNewPullRequest({ context, core, env = process.env, fetchImpl = fetch }) {
  const webhookUrl = env.DISCORD_REVIEW_QUEUE_WEBHOOK_URL;
  if (!webhookUrl) {
    core.warning('DISCORD_REVIEW_QUEUE_WEBHOOK_URL is not set');
    return;
  }

  const pullRequest = context.payload.pull_request;
  const item = {
    number: pullRequest.number,
    title: pullRequest.title,
    url: pullRequest.html_url,
    author: pullRequest.user?.login ?? 'ghost',
  };
  await sendToDiscord(webhookUrl, buildNewPullRequestPayload(item, readRoleId(env, core)), fetchImpl, core);
}

async function sendToDiscord(webhookUrl, payload, fetchImpl, core) {
  const response = await fetchImpl(webhookUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!response.ok) core.setFailed(`Discord returned HTTP ${response.status}`);
}

module.exports = {
  buildNewPullRequestPayload,
  buildPayload,
  evaluate,
  gradeFromScore,
  isDue,
  publishNewPullRequest,
  publishReviewQueue,
  readRoleId,
  sortQueue,
};
