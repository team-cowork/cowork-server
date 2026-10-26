import {aggregateAchievements, sixMonthPeriod} from './achievement-metrics.mjs';

const owner = 'team-cowork';
const metadataQuery = `query($owner: String!, $name: String!) {
  repository(owner: $owner, name: $name) {
    defaultBranchRef { name target { oid ... on Commit { history(first: 1) { totalCount } } } }
  }
}`;
const historyQuery = `query($owner: String!, $name: String!, $oid: GitObjectID!, $start: GitTimestamp!, $end: GitTimestamp!, $cursor: String) {
  repository(owner: $owner, name: $name) {
    object(oid: $oid) { ... on Commit {
      history(first: 100, after: $cursor, since: $start, until: $end) {
        pageInfo { hasNextPage endCursor }
        nodes { oid committedDate additions deletions parents { totalCount } author { email user { id login } } }
      }
    } }
  }
}`;
const pullRequestQuery = `query($owner: String!, $name: String!, $cursor: String) {
  repository(owner: $owner, name: $name) {
    pullRequests(first: 100, after: $cursor, states: MERGED, orderBy: {field: CREATED_AT, direction: DESC}) {
      pageInfo { hasNextPage endCursor }
      nodes { number mergedAt baseRefName headRefName }
    }
  }
}`;

export async function collectAchievements({token, period = sixMonthPeriod(), signal = AbortSignal.timeout(120_000)}) {
	if (!token) {
		throw new Error('GitHub authentication is unavailable.');
	}

	async function request(path, body) {
		const response = await fetch(`https://api.github.com/${path}`, {
			method: body ? 'POST' : 'GET',
			headers: {
				Authorization: `Bearer ${token}`,
				Accept: 'application/vnd.github+json',
				'Content-Type': 'application/json',
				'User-Agent': 'cowork-promotion',
			},
			...(body && {body: JSON.stringify(body)}),
			signal,
		});
		if (!response.ok) {
			throw new Error(`GitHub request failed: ${response.status}`);
		}

		const data = await response.json();
		if (data.errors?.length) {
			throw new Error('GitHub returned an incomplete result.');
		}

		return data;
	}

	async function graphql(query, variables) {
		const response = await request('graphql', {query, variables});
		return response.data.repository;
	}

	async function pages(query, variables, connection) {
		const nodes = [];
		let cursor;
		do {
			// GitHub's cursor determines the next request.
			// eslint-disable-next-line no-await-in-loop
			const result = await graphql(query, {...variables, cursor});
			const page = connection(result);
			if (!Array.isArray(page?.nodes) || (page.pageInfo.hasNextPage && !page.pageInfo.endCursor)) {
				throw new Error('GitHub returned incomplete pagination.');
			}

			nodes.push(...page.nodes);
			cursor = page.pageInfo.hasNextPage ? page.pageInfo.endCursor : undefined;
		} while (cursor);

		return nodes;
	}

	const repositories = [];
	let page = 1;
	let batch;
	do {
		// Read all public repositories rather than a fixed product list.
		// eslint-disable-next-line no-await-in-loop
		batch = await request(`orgs/${owner}/repos?type=public&per_page=100&page=${page}`);
		if (!Array.isArray(batch)) {
			throw new TypeError('GitHub returned an invalid repository list.');
		}

		repositories.push(...batch.filter(repository => !repository.fork && !repository.private));
		page++;
	} while (batch.length === 100);

	if (repositories.length === 0) {
		throw new Error('GitHub returned no public repositories.');
	}

	const until = new Date(Date.parse(period.end) - 1).toISOString();
	const collected = await Promise.all(repositories.map(async repository => {
		const variables = {owner, name: repository.name};
		const {defaultBranchRef: branch} = await graphql(metadataQuery, variables);
		const [commits, pullRequests] = await Promise.all([
			branch
				? pages(historyQuery, {
					...variables,
					oid: branch.target.oid,
					start: new Date(period.start).toISOString(),
					end: until,
				}, result => result.object.history)
				: [],
			pages(pullRequestQuery, variables, result => result.pullRequests),
		]);
		return {
			name: repository.name,
			url: repository.html_url,
			archived: repository.archived,
			defaultBranch: branch?.name ?? null,
			headOid: branch?.target.oid ?? null,
			totalCommits: branch?.target.history.totalCount ?? 0,
			commits,
			pullRequests,
		};
	}));
	return aggregateAchievements(collected, period);
}
