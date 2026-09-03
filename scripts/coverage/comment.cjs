'use strict';

const MARKER = '<!-- coverage-report-bot -->';

/** Update this bot's coverage comment only while the event's PR is current. */
module.exports = async function publishCoverageComment({ github, context, core, body }) {
  const pullRequest = context.payload.pull_request;
  if (!pullRequest?.head?.sha || !pullRequest?.base?.sha) {
    core.warning('Coverage comment skipped: the event has no PR head/base SHA.');
    return { status: 'skipped' };
  }
  if (typeof body !== 'string' || !body.trim()) {
    throw new Error('Coverage comment body must not be empty.');
  }
  const { owner, repo } = context.repo;
  const number = pullRequest.number ?? context.issue.number;
  const parameters = { owner, repo };
  const markedBody = body.includes(MARKER) ? body : `${MARKER}\n${body}`;

  async function isCurrent() {
    const { data } = await github.rest.pulls.get({ ...parameters, pull_number: number });
    if (data.head.sha !== pullRequest.head.sha || data.base.sha !== pullRequest.base.sha) {
      core.info('Coverage comment skipped: the PR head or base changed after this run started.');
      return false;
    }
    return true;
  }

  try {
    if (!(await isCurrent())) return { status: 'stale' };
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
    if (!(await isCurrent())) return { status: 'stale' };
    let response;
    let status;
    if (existing.length) {
      response = await github.rest.issues.updateComment({
        ...parameters, comment_id: existing[0].id, body: markedBody,
      });
      status = 'updated';
    } else {
      response = await github.rest.issues.createComment({
        ...parameters, issue_number: number, body: markedBody,
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
    return { status, id: response.data.id };
  } catch (error) {
    if (error.status !== 403) throw error;
    core.warning('Coverage comment could not be published (403). Fork PR tokens may be read-only; see the job summary and coverage artifacts.');
    return { status: 'forbidden' };
  }
};

module.exports.MARKER = MARKER;
