'use strict';

const { referencedIssueNumbers } = require('./issue_references.cjs');

async function syncPullRequestIssues({ github, context, core, close = false }) {
  const eventPullRequest = context.payload.pull_request;
  if (close && !eventPullRequest.merged) return;

  const { owner, repo } = context.repo;
  const { data: pullRequest } = await github.rest.pulls.get({
    owner, repo, pull_number: eventPullRequest.number,
  });
  if (close ? !pullRequest.merged : pullRequest.state !== 'open') return;

  const repository = `${owner}/${repo}`;
  const numbers = new Set(referencedIssueNumbers(pullRequest.body, repository));
  const failures = [];
  async function attempt(description, operation) {
    try {
      await operation();
    } catch (error) {
      failures.push(description);
      core.warning(`${description}: ${error.message}`);
    }
  }

  await attempt(`Read linked issues for PR #${pullRequest.number}`, async () => {
    let cursor = null;
    do {
      const result = await github.graphql(`
        query($owner: String!, $repo: String!, $number: Int!, $cursor: String) {
          repository(owner: $owner, name: $repo) {
            pullRequest(number: $number) {
              closingIssuesReferences(first: 100, after: $cursor) {
                nodes { number repository { nameWithOwner } }
                pageInfo { hasNextPage endCursor }
              }
            }
          }
        }
      `, { owner, repo, number: pullRequest.number, cursor });
      const connection = result.repository.pullRequest.closingIssuesReferences;
      for (const issue of connection.nodes) {
        if (issue.repository.nameWithOwner.toLowerCase() === repository.toLowerCase()) {
          numbers.add(issue.number);
        }
      }
      cursor = connection.pageInfo.hasNextPage ? connection.pageInfo.endCursor : null;
    } while (cursor);
  });

  const assignees = pullRequest.assignees.map(user => user.login);
  for (const number of numbers) {
    const parameters = { owner, repo, issue_number: number };
    let issue;
    await attempt(`Read issue #${number}`, async () => {
      try {
        ({ data: issue } = await github.rest.issues.get(parameters));
      } catch (error) {
        if (error.status !== 404 && error.status !== 410) throw error;
        core.warning(`Skip missing issue #${number}.`);
      }
    });
    // The Issues API also returns pull requests; never mutate those references.
    if (!issue || issue.pull_request) continue;

    const existing = new Set(issue.assignees.map(user => user.login.toLowerCase()));
    const missing = assignees.filter(login => !existing.has(login.toLowerCase()));
    if (missing.length) {
      await attempt(`Assign PR assignees to issue #${number}`, async () => {
        const { data: updated } = await github.rest.issues.addAssignees({ ...parameters, assignees: missing });
        const assigned = new Set(updated.assignees.map(user => user.login.toLowerCase()));
        if (missing.some(login => !assigned.has(login.toLowerCase()))) {
          throw new Error('GitHub did not accept all PR assignees; check eligibility and the assignee limit.');
        }
        core.info(`Added PR assignees to issue #${number}.`);
      });
    }
    if (close && issue.state === 'open') {
      await attempt(`Close issue #${number}`, async () => {
        await github.rest.issues.update({ ...parameters, state: 'closed', state_reason: 'completed' });
        core.info(`Closed issue #${number} after merging PR #${pullRequest.number}.`);
      });
    }
  }
  if (failures.length) throw new Error(`PR issue synchronization failed: ${failures.join('; ')}`);
}

module.exports = { syncPullRequestIssues };
