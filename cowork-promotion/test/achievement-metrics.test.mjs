import assert from 'node:assert/strict';
import test from 'node:test';
import {
	aggregateAchievements,
	aggregateWeeklyPullRequests,
	currentSeoulDate,
	sixMonthPeriod,
} from '../scripts/lib/achievement-metrics.mjs';

const period = sixMonthPeriod('2026-10-26');
const human = {id: 'U_1', login: 'developer'};
const commit = (overrides = {}) => ({
	oid: 'abc', committedDate: period.start, additions: 10, deletions: 3, parents: {totalCount: 1}, author: {user: human}, ...overrides,
});
const repository = (overrides = {}) => ({
	name: 'example', url: 'https://github.com/team-cowork/example', archived: false, defaultBranch: 'main', headOid: 'head', totalCommits: 0, commits: [], pullRequests: [], ...overrides,
});

test('six-month periods clamp dates and follow Seoul rather than UTC dates', () => {
	assert.deepEqual(period, {start: '2026-04-26T00:00:00+09:00', end: '2026-10-26T00:00:00+09:00', months: 6});
	assert.equal(sixMonthPeriod('2026-08-31').start, '2026-02-28T00:00:00+09:00');
	assert.equal(sixMonthPeriod('2024-08-31').start, '2024-02-29T00:00:00+09:00');
	assert.equal(currentSeoulDate(new Date('2026-10-25T15:00:00Z')), '2026-10-26');
	assert.throws(() => sixMonthPeriod('2026-02-30'), /valid calendar date/v);
	assert.throws(() => sixMonthPeriod('2026-2-3'), /YYYY-MM-DD/v);
});

test('line counts include the start, exclude the end and exclude duplicate and merge commits', () => {
	const valid = commit();
	const snapshot = aggregateAchievements([repository({
		commits: [
			valid,
			valid,
			commit({oid: 'end', committedDate: period.end}),
			commit({oid: 'early', committedDate: '2026-04-25T14:59:59Z'}),
			commit({oid: 'merge', additions: 900, parents: {totalCount: 2}}),
			commit({oid: 'last', committedDate: '2026-10-25T14:59:59Z'}),
		],
	})], period, period.end);
	assert.equal(snapshot.totals.changedLines, 26);
	assert.equal(snapshot.totals.periodCommits, 2);
	assert.equal(snapshot.excludedMergeCommits, 1);
	assert.equal(snapshot.totals.contributors, 1);
});

test('contributors deduplicate GitHub IDs across repositories and exclude bot and AI accounts', () => {
	const snapshot = aggregateAchievements([
		repository({
			commits: [
				commit(),
				commit({oid: 'bot', author: {user: {id: 'BOT_1', login: 'automation'}}}),
				commit({oid: 'bot2', author: {user: {id: 'U_bot', login: 'other[bot]'}}}),
				commit({oid: 'ai', author: {user: {id: 'U_ai', login: 'claude'}}}),
				commit({oid: 'unknown', author: {email: 'UNKNOWN@example.com', user: null}}),
			],
		}),
		repository({name: 'another', archived: true, commits: [commit({author: {user: {...human, login: 'renamed'}}}), commit({oid: 'unknown2', author: {email: 'unknown@example.com', user: null}})]}),
	], period);
	assert.equal(snapshot.totals.contributors, 1);
	assert.equal(snapshot.totals.repositories, 2);
	assert.equal(snapshot.unlinkedContributors, 1);
	assert.deepEqual(snapshot.contributors, [{id: 'U_1', login: 'renamed'}]);
	assert.equal(snapshot.repositories[1].archived, true);
});

test('PR metrics exclude release promotion duplicates and select the latest tied peak week', () => {
	const pullRequest = {
		number: 1, mergedAt: period.start, baseRefName: 'develop', headRefName: 'feature/work',
	};
	const snapshot = aggregateAchievements([repository({
		pullRequests: [
			pullRequest,
			pullRequest,
			{
				...pullRequest, number: 2, baseRefName: 'main', headRefName: 'develop',
			},
			{
				...pullRequest, number: 6, baseRefName: 'main', headRefName: 'dev',
			},
			{
				...pullRequest, number: 7, baseRefName: 'master', headRefName: 'development',
			},
			{...pullRequest, number: 3, mergedAt: period.end},
			{...pullRequest, number: 4, mergedAt: '2026-04-30T15:00:00Z'},
			{...pullRequest, number: 5, mergedAt: '2026-10-25T14:59:59Z'},
		],
	})], period, period.end);
	assert.equal(snapshot.totals.mergedPullRequests, 3);
	assert.equal(snapshot.totals.weeklyPeakPullRequests, 1);
	assert.equal(snapshot.excludedReleasePullRequests, 3);
	assert.deepEqual(snapshot.weekly, [
		{start: '2026-04-20T00:00:00+09:00', end: '2026-04-27T00:00:00+09:00', pullRequests: 1},
		{start: '2026-04-27T00:00:00+09:00', end: '2026-05-04T00:00:00+09:00', pullRequests: 1},
		{start: '2026-10-19T00:00:00+09:00', end: '2026-10-26T00:00:00+09:00', pullRequests: 1},
	]);
	assert.deepEqual(snapshot.peakWeek, snapshot.weekly.at(-1));
});

test('empty periods have zero totals and no peak-week dates', () => {
	const snapshot = aggregateAchievements([repository()], sixMonthPeriod('2026-10-01'));
	assert.equal(snapshot.totals.weeklyPeakPullRequests, 0);
	assert.equal(snapshot.totals.commits, 0);
	assert.equal(snapshot.totals.changedLines, 0);
	assert.equal(snapshot.totals.contributors, 0);
	assert.deepEqual(snapshot.weekly, []);
	assert.deepEqual(snapshot.peakWeek, {start: null, end: null, pullRequests: 0});
});

test('weekly peak spans years and pre-period PRs, deduplicates per repository and includes bot-authored work', () => {
	const pullRequest = (number, mergedAt, overrides = {}) => ({
		number, mergedAt, baseRefName: 'main', headRefName: 'feature/work', ...overrides,
	});
	const botPullRequest = pullRequest(1, '2025-12-28T14:59:59Z', {author: {id: 'BOT_proxy', login: 'proxy[bot]'}});
	const repositories = [
		repository({
			pullRequests: [
				botPullRequest,
				botPullRequest,
				pullRequest(2, '2025-12-28T15:00:00Z'),
				pullRequest(3, '2026-01-04T14:59:59Z'),
				pullRequest(4, '2026-01-04T15:00:00Z'),
				pullRequest(5, '2026-01-05T00:00:00Z'),
				pullRequest(6, period.end),
				pullRequest(7, '2026-01-05T00:00:00Z', {headRefName: 'develop'}),
			],
		}),
		repository({pullRequests: [botPullRequest]}),
		repository({
			name: 'another', pullRequests: [
				pullRequest(1, '2025-12-30T00:00:00Z'),
				pullRequest(2, '2026-01-05T00:00:00Z'),
			],
		}),
	];
	const {weekly, peakWeek} = aggregateWeeklyPullRequests(repositories, period.end);
	assert.deepEqual(weekly, [
		{start: '2025-12-22T00:00:00+09:00', end: '2025-12-29T00:00:00+09:00', pullRequests: 1},
		{start: '2025-12-29T00:00:00+09:00', end: '2026-01-05T00:00:00+09:00', pullRequests: 3},
		{start: '2026-01-05T00:00:00+09:00', end: '2026-01-12T00:00:00+09:00', pullRequests: 3},
	]);
	assert.deepEqual(peakWeek, weekly.at(-1));
	assert.equal(aggregateAchievements(repositories, period, period.end).totals.weeklyPeakPullRequests, 3);
});

test('weekly peaks include current-day merges before collection while six-month work retains its period', () => {
	const collectedAt = '2026-10-26T12:00:00+09:00';
	const pullRequest = (number, mergedAt) => ({
		number, mergedAt, baseRefName: 'main', headRefName: 'feature/work',
	});
	const snapshot = aggregateAchievements([repository({
		commits: [commit(), commit({oid: 'today', committedDate: period.end, additions: 100})],
		pullRequests: [
			pullRequest(1, '2026-10-25T14:59:59Z'),
			pullRequest(2, period.end),
			pullRequest(3, '2026-10-26T11:59:59+09:00'),
			pullRequest(4, collectedAt),
			pullRequest(5, '2026-10-26T12:00:01+09:00'),
		],
	})], period, collectedAt);
	assert.equal(snapshot.totals.changedLines, 13);
	assert.equal(snapshot.totals.periodCommits, 1);
	assert.equal(snapshot.totals.contributors, 1);
	assert.equal(snapshot.totals.mergedPullRequests, 1);
	assert.deepEqual(snapshot.peakWeek, {start: '2026-10-26T00:00:00+09:00', end: '2026-11-02T00:00:00+09:00', pullRequests: 2});
});

test('invalid period and line counts fail rather than publishing misleading totals', () => {
	assert.throws(() => aggregateAchievements([], {...period, months: 0}), /valid six-month period/v);
	assert.throws(() => aggregateAchievements([], {...period, start: period.end}), /valid six-month period/v);
	assert.throws(() => aggregateAchievements([repository({commits: [commit({additions: -1})]})], period), /non-negative safe integers/v);
	assert.throws(() => aggregateWeeklyPullRequests([], 'invalid'), /valid exclusive end date/v);
	assert.throws(() => aggregateAchievements([repository({totalCommits: -1})], period), /total commit counts/v);
	assert.throws(() => aggregateAchievements([repository({totalCommits: undefined})], period), /total commit counts/v);
	assert.throws(() => aggregateAchievements([repository({totalCommits: 1.5})], period), /total commit counts/v);
});

test('cumulative commits use all-time repository totals including merges, bots and work outside the six-month period', () => {
	const snapshot = aggregateAchievements([
		repository({
			totalCommits: 500, commits: [
				commit(),
				commit({oid: 'merge', parents: {totalCount: 2}}),
				commit({oid: 'bot', author: {user: {id: 'BOT_1', login: 'automation[bot]'}}}),
				commit({oid: 'past', committedDate: '2025-01-01T00:00:00Z'}),
			],
		}),
		repository({name: 'another', totalCommits: 70, commits: [commit()]}),
	], period);
	assert.equal(snapshot.totals.commits, 570);
	assert.equal(snapshot.repositories[0].commits, 500);
	assert.equal(snapshot.repositories[1].commits, 70);
	assert.equal(snapshot.totals.periodCommits, 3);
	assert.equal(snapshot.totals.changedLines, 39);
	assert.equal(snapshot.totals.contributors, 1);
});
