module.exports = async ({ github, context, core }) => {
  const { execSync } = require('child_process');
  const { owner, repo } = context.repo;
  const pr = context.payload.pull_request;
  const issue_number = pr.number;
  const baseRef = pr.base.ref;
  const marker = '<!-- contrib-delta-bot -->';
  const sh = (c) => execSync(c, { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });

  const postComment = async (body) => {
    const comments = await github.paginate(github.rest.issues.listComments,
      { owner, repo, issue_number, per_page: 100 });
    const bots = comments.filter(c => c.body && c.body.includes(marker));
    if (bots.length) {
      await github.rest.issues.updateComment({ owner, repo, comment_id: bots[0].id, body });
      for (const extra of bots.slice(1)) {
        await github.rest.issues.deleteComment({ owner, repo, comment_id: extra.id });
      }
    } else {
      await github.rest.issues.createComment({ owner, repo, issue_number, body });
    }
  };

  const modules = sh('git ls-tree -d --name-only HEAD').trim().split('\n')
    .filter(name => name.startsWith('cowork-')).sort();
  const AI = new Set(['.harness', '.claude', '.agents', '.codex']);
  const classify = (p) => {
    const top = p.split('/')[0];
    if (modules.includes(top)) return top;
    if (top === 'deploy' || top === '.github') return 'ci/cd';
    if (AI.has(top)) return 'ai-harness';
    return 'etc';
  };

  sh(`git fetch --no-tags origin ${baseRef}`);
  const baseSha = sh(`git merge-base origin/${baseRef} HEAD`).trim();
  const S1 = '\x01', S2 = '\x02';
  const parseLog = (range) => {
    const out = sh(`git log --no-merges --pretty=format:"${S1}%H${S2}%ae${S2}%(trailers:key=Co-authored-by,valueonly,separator=%x03)" --numstat ${range}`);
    const commits = []; let cur = null;
    for (const line of out.split('\n')) {
      if (line.startsWith(S1)) {
        if (cur) commits.push(cur);
        const [sha, rawEmail, rawCoAuthors = ''] = line.slice(1).split(S2);
        const email = rawEmail.toLowerCase();
        const coAuthorEmails = [...rawCoAuthors.matchAll(/<([^<>\s]+@[^<>\s]+)>/g)]
          .map(m => m[1].toLowerCase())
          .filter((coAuthorEmail, i, emails) => coAuthorEmail !== email && emails.indexOf(coAuthorEmail) === i);
        cur = { sha, email, coAuthorEmails, files: {} };
      } else if (line.trim() && cur) {
        const parts = line.split('\t');
        if (parts.length !== 3) continue;
        let [add, del, path] = parts;
        const ln = (add === '-' ? 0 : parseInt(add, 10)) + (del === '-' ? 0 : parseInt(del, 10));
        if (path.includes('=>')) {
          path = path.replace(/\{[^}]*=> ?([^}]*)\}/, '$1').split(' => ').pop().trim();
        }
        const cat = classify(path);
        cur.files[cat] = (cur.files[cat] || 0) + ln;
      }
    }
    if (cur) commits.push(cur);
    return commits;
  };

  const baseCommits = parseLog(baseSha);
  const prCommits = parseLog(`${baseSha}..HEAD`);
  if (prCommits.length === 0) {
    await postComment(`${marker}\n## 이 PR이 병합되면 — 기여도 변화 미리보기\n\n집계할 신규 커밋이 없습니다 (머지 커밋 제외).`);
    return;
  }

  const all = baseCommits.concat(prCommits);
  const sampleSha = new Map();
  for (const c of all) if (!sampleSha.has(c.email)) sampleSha.set(c.email, c.sha);
  for (const c of all) {
    for (const email of c.coAuthorEmails) if (!sampleSha.has(email)) sampleSha.set(email, c.sha);
  }
  const coAuthorEmails = new Set(all.flatMap(c => c.coAuthorEmails));
  const FAKE_TLDS = new Set(['test', 'invalid', 'example', 'localhost', 'local', 'internal', 'fake', 'nowhere']);
  const isFake = (email) => FAKE_TLDS.has(email.split('.').pop());
  const emailAccountIds = new Map();
  const accountLogins = new Map();
  const noreplyAccounts = new Map();
  for (const [email, sha] of sampleSha) {
    if (isFake(email)) continue;
    try {
      let actor = null;
      const accountId = email.match(/^(\d+)\+[^@]+@users\.noreply\.github\.com$/)?.[1];
      if (accountId) {
        // The account ID survives renames; the username in the email does not.
        if (!noreplyAccounts.has(accountId)) {
          const { data } = await github.request('GET /user/{account_id}', { account_id: accountId });
          if (String(data.id) !== accountId) throw new Error('GitHub returned a different account ID');
          noreplyAccounts.set(accountId, data);
        }
        actor = noreplyAccounts.get(accountId);
      } else if (coAuthorEmails.has(email)) {
        const data = await github.graphql(`
          query($owner: String!, $repo: String!, $oid: GitObjectID!) {
            repository(owner: $owner, name: $repo) {
              object(oid: $oid) {
                ... on Commit {
                  authors(first: 100) { nodes { email user { login databaseId } } }
                }
              }
            }
          }
        `, { owner, repo, oid: sha });
        const authors = data.repository.object?.authors.nodes || [];
        const user = authors.find(a => a.email.toLowerCase() === email)?.user;
        if (user) actor = { id: user.databaseId, login: user.login };
      } else {
        const { data } = await github.rest.repos.getCommit({ owner, repo, ref: sha });
        actor = data.author;
      }
      if (actor?.id && actor.login) {
        const id = String(actor.id);
        emailAccountIds.set(email, id);
        accountLogins.set(id, actor.login);
      }
    } catch (error) {
      if (error.status === 404) {
        core.warning(`No GitHub account could be resolved for ${email}`);
        continue;
      }
      // Keep the previous comment when lookup fails instead of publishing wrong attribution.
      throw new Error(`Unable to verify the contribution account for ${email}`, { cause: error });
    }
  }
  const emailLogin = new Map([...emailAccountIds].map(([email, id]) => [email, accountLogins.get(id)]));

  const fullCats = modules.concat(['ci/cd', 'ai-harness', 'etc']);
  const aggregate = (commits, metric) => {
    const contrib = {}, totals = {}, seen = {};
    for (const c of commits) {
      if (metric === 'commits') {
        const logins = [emailLogin.get(c.email), ...c.coAuthorEmails.map(e => emailLogin.get(e))]
          .filter(Boolean);
        for (const login of new Set(logins)) {
          contrib[login] = contrib[login] || {};
          for (const cat of Object.keys(c.files)) contrib[login][cat] = (contrib[login][cat] || 0) + 1;
          seen[login] = seen[login] || new Set();
          if (!seen[login].has(c.sha)) { seen[login].add(c.sha); totals[login] = (totals[login] || 0) + 1; }
        }
      } else {
        const login = emailLogin.get(c.email);
        if (!login) continue;
        contrib[login] = contrib[login] || {};
        for (const [cat, ln] of Object.entries(c.files)) {
          contrib[login][cat] = (contrib[login][cat] || 0) + ln;
          totals[login] = (totals[login] || 0) + ln;
        }
      }
    }
    return { contrib, totals };
  };
  const A = {};
  for (const m of ['commits', 'lines']) {
    A[m] = { before: aggregate(baseCommits, m), after: aggregate(all, m) };
  }

  const fmt = (n) => n.toLocaleString('en-US');
  const signed = (n) => n > 0 ? `+${fmt(n)}` : `${fmt(n)}`;
  const ranks = (totals) => {
    const order = Object.keys(totals).sort((a, b) => totals[b] - totals[a]);
    const r = {}; order.forEach((a, i) => r[a] = i + 1); return r;
  };
  const projTotals = (contrib, cats) => {
    const t = {}; for (const cat of cats) t[cat] = 0;
    for (const a of Object.keys(contrib)) for (const cat of cats) t[cat] += (contrib[a][cat] || 0);
    return t;
  };

  const btC = A.commits.before.totals, atC = A.commits.after.totals;
  const btL = A.lines.before.totals, atL = A.lines.after.totals;

  const sum = (o) => Object.values(o).reduce((a, b) => a + b, 0);
  const dCommits = prCommits.length;
  const dLines = sum(atL) - sum(btL);
  const nContrib = Object.keys(atC).filter(a => (atC[a] - (btC[a] || 0)) > 0).length;

  let out = [
    marker,
    '## 이 PR이 병합되면 — 기여도 변화 미리보기',
    '',
    `**커밋 ${signed(dCommits)}** · **라인 ${signed(dLines)}** · 기여자 ${nContrib}명 증가`,
    '',
  ];

  const rB = ranks(btC), rA = ranks(atC);
  const authors = Object.keys(atC).sort((a, b) => atC[b] - atC[a]);
  const lbRows = authors.map(a => {
    const bcc = btC[a] || 0, acc = atC[a] || 0;
    const bll = btL[a] || 0, aLines = atL[a] || 0;
    const rbV = rB[a], raV = rA[a];
    const move = rbV ? `${rbV} -> ${raV}` : `신규 -> ${raV}`;
    return {
      changed: (acc - bcc) > 0 || (aLines - bll) > 0,
      a,
      c: `${fmt(bcc)} -> ${fmt(acc)} (${signed(acc - bcc)})`,
      l: `${fmt(bll)} -> ${fmt(aLines)} (${signed(aLines - bll)})`,
      m: move,
    };
  });
  const displayWidth = (s) => [...s].reduce((width, char) => width + (/[가-힣ㄱ-ㅎㅏ-ㅣ]/u.test(char) ? 2 : 1), 0);
  const pad = (s, w) => s + ' '.repeat(Math.max(0, w - displayWidth(s)));
  const wa = Math.max(displayWidth('기여자'), ...lbRows.map(r => displayWidth(r.a)));
  const wc = Math.max(displayWidth('커밋'), ...lbRows.map(r => r.c.length));
  const wl = Math.max(displayWidth('라인'), ...lbRows.map(r => r.l.length));
  out.push('### 전체 기여자 리더보드 (병합 후 기준)',
    '이번 PR로 기여가 증가한 사람은 초록색으로 강조됩니다.', '', '```diff',
    `  ${pad('기여자', wa)}  ${pad('커밋', wc)}  ${pad('라인', wl)}  순위`);
  for (const r of lbRows) {
    out.push(`${r.changed ? '+' : ' '} ${pad(r.a, wa)}  ${pad(r.c, wc)}  ${pad(r.l, wl)}  ${r.m}`);
  }
  out.push('```', '');

  const bModC = projTotals(A.commits.before.contrib, fullCats);
  const aModC = projTotals(A.commits.after.contrib, fullCats);
  const bModL = projTotals(A.lines.before.contrib, fullCats);
  const aModL = projTotals(A.lines.after.contrib, fullCats);
  let worked = fullCats.map(c => [c, aModC[c] - bModC[c], aModL[c] - bModL[c]])
    .filter(w => w[1] > 0 || w[2] > 0)
    .sort((x, y) => (y[1] - x[1]) || (y[2] - x[2]));
  if (worked.length) {
    const labels = worked.map(w => w[0]);
    const data = worked.map(w => w[1]);
    const vmax = Math.max(...data, 1);
    const stops = [[68, 1, 84], [59, 82, 139], [33, 145, 140], [94, 201, 98], [253, 231, 37]];
    const colorFor = (v) => {
      const t = 0.12 + 0.72 * (v / vmax);
      const x = Math.min(0.999, Math.max(0, t)) * (stops.length - 1);
      const i = Math.floor(x), f = x - i, p = stops[i], q = stops[i + 1] || p;
      const c = (k) => Math.round(p[k] + (q[k] - p[k]) * f);
      return `rgb(${c(0)},${c(1)},${c(2)})`;
    };
    const cfg = {
      type: 'horizontalBar',
      data: { labels, datasets: [{ data, backgroundColor: data.map(colorFor) }] },
      options: {
        legend: { display: false },
        plugins: { datalabels: { anchor: 'end', align: 'right', color: '#444', formatter: '__FMT__' } },
        scales: { xAxes: [{ ticks: { beginAtZero: true, suggestedMax: Math.ceil(vmax * 1.25) } }] },
      },
    };
    let cfgStr = JSON.stringify(cfg).replace('"__FMT__"', 'function(v){return "+"+v;}');
    const h = Math.max(180, 46 * labels.length + 90);
    const chartUrl = `https://quickchart.io/chart?bkg=white&w=820&h=${h}&c=${encodeURIComponent(cfgStr)}`;
    out.push('### 이 PR이 추가하는 모듈별 커밋', `![모듈별 커밋 변화](${chartUrl})`, '');
  }

  const mdRows = fullCats.map(c => {
    const dc = aModC[c] - bModC[c], dl = aModL[c] - bModL[c];
    const cell = (tot, d) => d ? `${fmt(tot)} (${signed(d)})` : `${fmt(tot)}`;
    return { changed: dc > 0 || dl > 0, name: c, dc, dl,
      cc: cell(aModC[c], dc), lc: cell(aModL[c], dl) };
  }).sort((x, y) => (y.dc - x.dc) || (y.dl - x.dl));
  const wm = Math.max(displayWidth('모듈'), ...mdRows.map(r => displayWidth(r.name)));
  const wcc = Math.max(displayWidth('커밋'), ...mdRows.map(r => r.cc.length));
  const wll = Math.max(displayWidth('라인'), ...mdRows.map(r => r.lc.length));
  out.push('### 모듈별 변화 상세 (전체 모듈)',
    '이번 PR이 커밋을 추가한 모듈은 초록색으로 강조됩니다.', '', '```diff',
    `  ${pad('모듈', wm)}  ${pad('커밋', wcc)}  ${pad('라인', wll)}`);
  for (const r of mdRows) {
    out.push(`${r.changed ? '+' : ' '} ${pad(r.name, wm)}  ${pad(r.cc, wcc)}  ${pad(r.lc, wll)}`);
  }
  out.push('```', '');
  out.push(`<sub>기준 커밋 <code>${baseSha.slice(0, 8)}</code> · 머지 커밋 제외</sub>`);

  await postComment(out.join('\n'));
};
