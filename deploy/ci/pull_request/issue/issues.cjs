'use strict';

const { referencedIssueNumbers } = require('./issue_references.cjs');

// Issue statuses move only when a PR opens or leaves draft, so later manual changes stay.
const STATUS_ACTIONS = new Set(['opened', 'ready_for_review']);
const DRAFT_STATUS = 'In progress';
const REVIEW_STATUS = 'In review';

/**
 * Returns the GitHub issue status (preview) that the PR moves the issue to, or null.
 * A status never moves backward, and a closed issue keeps its status because a started status reopens it.
 */
function nextIssueStatus(pullRequest, issue) {
  if (issue.state !== 'open') return null;
  const target = pullRequest.draft ? DRAFT_STATUS : REVIEW_STATUS;
  const current = issue.status;
  if (!current) return target;
  if (current.name === target) return null;
  const movable = pullRequest.draft ? ['backlog', 'planned'] : ['backlog', 'planned', 'started'];
  return movable.includes(current.category) ? target : null;
}

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

  const updatesStatus = !close && STATUS_ACTIONS.has(context.payload.action);
  let statusIds;
  async function statusId(name) {
    if (!statusIds) {
      const result = await github.graphql(`
        query($owner: String!, $repo: String!) {
          repository(owner: $owner, name: $repo) {
            planningStatuses(first: 50) { nodes { id name } }
          }
        }
      `, { owner, repo });
      statusIds = new Map(result.repository.planningStatuses.nodes.map(status => [status.name, status.id]));
    }
    const id = statusIds.get(name);
    if (!id) throw new Error(`Issue status "${name}" is not available in ${repository}.`);
    return id;
  }

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
    const status = updatesStatus ? nextIssueStatus(pullRequest, issue) : null;
    if (status) {
      await attempt(`Set issue #${number} status to ${status}`, async () => {
        await github.graphql(`
          mutation($issueId: ID!, $statusId: ID!) {
            setIssueStatus(input: { issueId: $issueId, statusId: $statusId }) { issue { number } }
          }
        `, { issueId: issue.node_id, statusId: await statusId(status) });
        core.info(`Set issue #${number} status to ${status}.`);
      });
    }
    if (close && issue.state === 'open') {
      await attempt(`Close issue #${number}`, async () => {
        await github.rest.issues.update({ ...parameters, state: 'closed', state_reason: 'completed' });
        core.info(`Closed issue #${number} after merging PR #${pullRequest.number}.`);
      });
    }
  }
  if (failures.length && close) throw new Error(`PR issue synchronization failed: ${failures.join('; ')}`);
}

module.exports = { nextIssueStatus, syncPullRequestIssues };
