'use strict';

const MARKER = '<!-- coverage-report-bot -->';

/** Update this bot's coverage comment only while the measured PR head is current. */
module.exports = async function publishCoverageComment({ github, context, core, body }) {
  const pullRequest = context.payload.pull_request;
  if (!pullRequest?.head?.sha || !pullRequest?.base?.sha) {
    core.warning('Coverage comment skipped: the event has no PR head/base SHA.');
    return { status: 'skipped' };
  }
  if (typeof body !== 'string') {
    throw new Error('Coverage comment body must be a string.');
  }
  const { owner, repo } = context.repo;
  const number = pullRequest.number ?? context.issue.number;
  const parameters = { owner, repo };
  const markedBody = body.includes(MARKER) ? body : `${MARKER}\n${body}`;
  const snapshot = {
    measuredBaseSha: pullRequest.base.sha,
    measuredHeadSha: pullRequest.head.sha,
    measuredMergeSha: context.sha,
  };
  const shortSha = sha => typeof sha === 'string' && sha ? sha.slice(0, 8) : 'unknown';

  async function getCurrentPullRequest() {
    const { data } = await github.rest.pulls.get({ ...parameters, pull_number: number });
    if (data.head.sha !== pullRequest.head.sha) {
      core.info('Coverage comment skipped: the PR head changed after this run started.');
      return null;
    }
    return data;
  }

  function publication(currentPullRequest) {
    const currentBaseSha = currentPullRequest.base.sha;
    const baseChanged = currentBaseSha !== pullRequest.base.sha;
    const lines = [markedBody.trimEnd(), ''];
    if (baseChanged) {
      lines.push(
        '> [!NOTE]',
        `> 측정 후 base가 \`${shortSha(pullRequest.base.sha)}\`에서 \`${shortSha(currentBaseSha)}\`로 변경되었습니다. 위 결과는 측정 스냅샷 기준입니다.`,
        '',
      );
    }
    lines.push(
      `<sub>coverage snapshot · base <code>${shortSha(pullRequest.base.sha)}</code> · head <code>${shortSha(pullRequest.head.sha)}</code> · merge <code>${shortSha(context.sha)}</code>${baseChanged ? ` · current base <code>${shortSha(currentBaseSha)}</code>` : ''}</sub>`,
    );
    return { body: lines.join('\n'), baseChanged, currentBaseSha };
  }

  try {
    if (!(await getCurrentPullRequest())) return { status: 'stale', ...snapshot };
    const comments = await github.paginate(github.rest.issues.listComments, {
      ...parameters,
      issue_number: number,
      per_page: 100,
    });
    const existing = comments.filter(comment =>
      comment.user?.type === 'Bot' &&
      comment.user?.login === 'github-actions[bot]' &&
      typeof comment.body === 'string' && comment.body.includes(MARKER),
    ).sort((left, right) => left.id - right.id);

    // Pagination can take time. Check again immediately before writing.
    const currentPullRequest = await getCurrentPullRequest();
    if (!currentPullRequest) return { status: 'stale', ...snapshot };
    if (!body.trim()) {
      for (const comment of existing) {
        await github.rest.issues.deleteComment({ ...parameters, comment_id: comment.id });
      }
      return { status: existing.length ? 'deleted' : 'skipped', ...snapshot };
    }
    const published = publication(currentPullRequest);
    let response;
    let status;
    if (existing.length) {
      response = await github.rest.issues.updateComment({
        ...parameters, comment_id: existing[0].id, body: published.body,
      });
      status = 'updated';
    } else {
      response = await github.rest.issues.createComment({
        ...parameters, issue_number: number, body: published.body,
      });
      status = 'created';
    }
    for (const duplicate of existing.slice(1)) {
      try {
        await github.rest.issues.deleteComment({ ...parameters, comment_id: duplicate.id });
      } catch (error) {
        if (error.status !== 403) throw error;
        core.warning('Coverage comment was published, but duplicate cleanup was denied (403).');
        break;
      }
    }
    return {
      status,
      id: response.data.id,
      ...snapshot,
      baseChanged: published.baseChanged,
      currentBaseSha: published.currentBaseSha,
    };
  } catch (error) {
    if (error.status !== 403) throw error;
    core.warning('Coverage comment could not be updated (403). Fork PR tokens may be read-only; see the job summary and coverage artifacts.');
    return { status: 'forbidden', ...snapshot };
  }
};

module.exports.MARKER = MARKER;
