const seoulDate = new Intl.DateTimeFormat('en-CA', {
	timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
});
const aiAccounts = new Set(['claude', 'copilot', 'devin-ai-integration']);

function dateParts(formatter, date) {
	return Object.fromEntries(formatter.formatToParts(date).map(({type, value}) => [type, value]));
}

export function currentSeoulDate(now = new Date()) {
	const {year, month, day} = dateParts(seoulDate, now);
	return `${year}-${month}-${day}`;
}

export function sixMonthPeriod(end = currentSeoulDate()) {
	if (!/^\d{4}-\d{2}-\d{2}$/v.test(end)) {
		throw new Error('End date must use YYYY-MM-DD.');
	}

	const date = new Date(`${end}T00:00:00Z`);
	if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== end) {
		throw new Error('End date must be a valid calendar date.');
	}

	const startMonth = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() - 6, 1));
	const lastDay = new Date(Date.UTC(startMonth.getUTCFullYear(), startMonth.getUTCMonth() + 1, 0)).getUTCDate();
	startMonth.setUTCDate(Math.min(date.getUTCDate(), lastDay));
	return {start: `${startMonth.toISOString().slice(0, 10)}T00:00:00+09:00`, end: `${end}T00:00:00+09:00`, months: 6};
}

function isReleasePullRequest(pullRequest) {
	return ['main', 'master'].includes(pullRequest.baseRefName) && ['develop', 'dev', 'development'].includes(pullRequest.headRefName);
}

function weekBounds(time) {
	const monday = new Date(`${currentSeoulDate(new Date(time))}T00:00:00Z`);
	monday.setUTCDate(monday.getUTCDate() - ((monday.getUTCDay() + 6) % 7));
	const start = `${monday.toISOString().slice(0, 10)}T00:00:00+09:00`;
	monday.setUTCDate(monday.getUTCDate() + 7);
	return {start, end: `${monday.toISOString().slice(0, 10)}T00:00:00+09:00`};
}

function addRepositoryWeeks(repository, end, weeks, seen) {
	for (const pullRequest of repository.pullRequests) {
		const mergedAt = Date.parse(pullRequest.mergedAt);
		const id = `${repository.name.toLowerCase()}:${pullRequest.number}`;
		if (!Number.isFinite(mergedAt) || mergedAt >= end || seen.has(id) || isReleasePullRequest(pullRequest)) {
			continue;
		}

		seen.add(id);
		const bounds = weekBounds(mergedAt);
		const week = weeks.get(bounds.start) ?? {...bounds, pullRequests: 0};
		week.pullRequests++;
		weeks.set(bounds.start, week);
	}
}

export function aggregateWeeklyPullRequests(repositories, exclusiveEnd) {
	const end = Date.parse(exclusiveEnd);
	if (!Number.isFinite(end)) {
		throw new TypeError('A valid exclusive end date is required.');
	}

	const weeks = new Map();
	const seen = new Set();
	for (const repository of repositories) {
		addRepositoryWeeks(repository, end, weeks, seen);
	}

	const weekly = weeks.values().toArray().toSorted((a, b) => a.start.localeCompare(b.start));
	let peakWeek = {start: null, end: null, pullRequests: 0};
	for (const week of weekly) {
		if (week.pullRequests >= peakWeek.pullRequests) {
			peakWeek = week;
		}
	}

	return {weekly, peakWeek};
}

function isHuman(user) {
	return user && !user.id.startsWith('BOT_') && !user.login.endsWith('[bot]') && !aiAccounts.has(user.login.toLowerCase());
}

function expectCount(value, label) {
	if (!Number.isSafeInteger(value) || value < 0) {
		throw new Error(`GitHub ${label} counts must be non-negative safe integers.`);
	}

	return value;
}

function aggregateCommitWork(commits, inPeriod, contributors, unlinkedAuthors) {
	const people = new Set();
	const seen = new Set();
	let additions = 0;
	let deletions = 0;
	let periodCommits = 0;
	let excludedMergeCommits = 0;
	for (const commit of commits) {
		if (!inPeriod(commit.committedDate) || seen.has(commit.oid)) {
			continue;
		}

		seen.add(commit.oid);
		if (commit.parents.totalCount > 1) {
			excludedMergeCommits++;
			continue;
		}

		additions += expectCount(commit.additions, 'line');
		deletions += expectCount(commit.deletions, 'line');
		periodCommits++;
		const user = commit.author?.user;
		if (isHuman(user)) {
			contributors.set(user.id, {id: user.id, login: user.login});
			people.add(user.id);
		} else if (!user && commit.author?.email) {
			unlinkedAuthors.add(commit.author.email.trim().toLowerCase());
		}
	}

	return {
		additions, deletions, periodCommits, contributors: people.size, excludedMergeCommits,
	};
}

function aggregateMergedPullRequests(pullRequests, inPeriod) {
	const seen = new Set();
	let mergedPullRequests = 0;
	let excludedReleasePullRequests = 0;
	for (const pullRequest of pullRequests) {
		if (!inPeriod(pullRequest.mergedAt) || seen.has(pullRequest.number)) {
			continue;
		}

		seen.add(pullRequest.number);
		if (isReleasePullRequest(pullRequest)) {
			excludedReleasePullRequests++;
			continue;
		}

		mergedPullRequests++;
	}

	return {mergedPullRequests, excludedReleasePullRequests};
}

export function aggregateAchievements(repositories, period, collectedAt = new Date().toISOString()) {
	const start = Date.parse(period.start);
	const end = Date.parse(period.end);
	if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end || period.months !== 6) {
		throw new Error('A valid six-month period is required.');
	}

	const inPeriod = value => {
		const time = Date.parse(value);
		return time >= start && time < end;
	};

	const {weekly, peakWeek} = aggregateWeeklyPullRequests(repositories, collectedAt);
	const contributors = new Map();
	const unlinkedAuthors = new Set();
	let excludedReleasePullRequests = 0;
	let excludedMergeCommits = 0;
	const results = repositories.map(repository => {
		const work = aggregateCommitWork(repository.commits, inPeriod, contributors, unlinkedAuthors);
		const pullRequests = aggregateMergedPullRequests(repository.pullRequests, inPeriod);
		excludedMergeCommits += work.excludedMergeCommits;
		excludedReleasePullRequests += pullRequests.excludedReleasePullRequests;

		return {
			name: repository.name,
			url: repository.url,
			archived: repository.archived,
			defaultBranch: repository.defaultBranch,
			headOid: repository.headOid,
			additions: work.additions,
			deletions: work.deletions,
			changedLines: work.additions + work.deletions,
			commits: expectCount(repository.totalCommits, 'total commit'),
			periodCommits: work.periodCommits,
			mergedPullRequests: pullRequests.mergedPullRequests,
			contributors: work.contributors,
		};
	});
	const sum = key => results.reduce((total, repository) => total + repository[key], 0);
	return {
		schemaVersion: 1,
		collectedAt,
		period,
		totals: {
			additions: sum('additions'),
			deletions: sum('deletions'),
			changedLines: sum('changedLines'),
			commits: sum('commits'),
			periodCommits: sum('periodCommits'),
			mergedPullRequests: sum('mergedPullRequests'),
			weeklyPeakPullRequests: peakWeek.pullRequests,
			contributors: contributors.size,
			repositories: results.length,
		},
		peakWeek,
		weekly,
		repositories: results,
		contributors: contributors.values().toArray().toSorted((a, b) => a.login.localeCompare(b.login)),
		unlinkedContributors: unlinkedAuthors.size,
		excludedReleasePullRequests,
		excludedMergeCommits,
		methodology: {
			repositories: 'All public, non-fork team-cowork repositories, including archived repositories.',
			changedLines: 'Additions + deletions in non-merge commits reachable from each default-branch head, filtered by committedDate. Includes documentation, generated files and lockfiles.',
			commits: 'All-time history.totalCount reachable from each default-branch head, including merge and bot commits. Counts are summed per repository.',
			pullRequests: 'Merged in the period, excluding develop/dev/development-to-main/master release PRs. Bot-authored PRs are included.',
			weeklyPeakPullRequests: 'All-time merged PRs before collectedAt, excluding releases. Monday 00:00 to next Monday 00:00 in Asia/Seoul; ties select the latest week.',
			contributors: 'Unique GitHub account IDs of non-merge commit authors in the period, excluding BOT_ IDs, [bot] logins and known AI accounts. Unlinked email authors are not counted.',
			excludedAiAccounts: [...aiAccounts],
			timeZone: 'Asia/Seoul',
			periodBoundaries: 'Inclusive start; exclusive end.',
		},
	};
}
