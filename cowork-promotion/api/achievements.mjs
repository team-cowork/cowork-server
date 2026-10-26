import {readFile} from 'node:fs/promises';
import process from 'node:process';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {renderAchievementRegions} from '../scripts/lib/achievement-render.mjs';
import {collectAchievements} from '../scripts/lib/github-achievements.mjs';

const execute = promisify(execFile);
const fallback = JSON.parse(await readFile(new URL('../data/achievements.json', import.meta.url), 'utf8'));
let cached;
let pending;

async function refresh() {
	try {
		let token = process.env.GITHUB_TOKEN;
		if (!token && !process.env.VERCEL) {
			const {stdout} = await execute('gh', ['auth', 'token'], {timeout: 3000});
			token = stdout.trim();
		}

		const snapshot = await collectAchievements({token});
		return {regions: renderAchievementRegions(snapshot), live: true};
	} catch (error) {
		console.warn('GitHub achievement refresh failed.', error.name);
		return {regions: cached?.regions ?? renderAchievementRegions(fallback), live: false};
	}
}

export default async function achievements(request, response) {
	if (!['GET', 'HEAD'].includes(request.method)) {
		response.writeHead(405, {Allow: 'GET, HEAD'}).end();
		return;
	}

	if (!cached || Date.now() >= cached.expiresAt) {
		pending ??= refresh().then(result => {
			cached = {...result, expiresAt: Date.now() + (result.live ? 3_600_000 : 60_000)};
		}).finally(() => {
			pending = undefined;
		});
		await pending;
	}

	response.writeHead(200, {
		'Content-Type': 'application/json; charset=utf-8',
		'Cache-Control': cached.live
			? 'public, s-maxage=3600, stale-while-revalidate=86400'
			: 'public, s-maxage=60',
		'X-Achievement-Source': cached.live ? 'github' : 'snapshot',
	});
	response.end(request.method === 'HEAD' ? undefined : JSON.stringify({regions: cached.regions}));
}
