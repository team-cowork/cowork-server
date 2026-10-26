import {rename, rm, writeFile} from 'node:fs/promises';
import process from 'node:process';
import {sixMonthPeriod} from './lib/achievement-metrics.mjs';
import {collectAchievements} from './lib/github-achievements.mjs';
import {resolveGitHubToken} from './lib/github-auth.mjs';

const arguments_ = process.argv.slice(2);
if (arguments_.length > 0 && (arguments_.length !== 2 || arguments_[0] !== '--end')) {
	throw new Error('Usage: node scripts/collect-achievements.mjs [--end YYYY-MM-DD]');
}

const period = sixMonthPeriod(arguments_[1]);
const token = await resolveGitHubToken();
const result = await collectAchievements({token, period, signal: AbortSignal.timeout(300_000)});
const output = new URL('../data/achievements.json', import.meta.url);
const temporary = new URL(`../data/.achievements-${process.pid}.json`, import.meta.url);
try {
	await writeFile(temporary, `${JSON.stringify(result, null, 2)}\n`, {flag: 'wx'});
	await rename(temporary, output);
} finally {
	await rm(temporary, {force: true});
}

console.log(`Saved ${result.totals.changedLines.toLocaleString('en-US')} changed lines, ${result.totals.commits} commits, ${result.totals.contributors} contributors.`);
console.log(`Peak week: ${result.peakWeek.pullRequests} merged PRs, ${result.peakWeek.start} to ${result.peakWeek.end}.`);
