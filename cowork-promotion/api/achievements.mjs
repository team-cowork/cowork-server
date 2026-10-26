import process from 'node:process';
import {waitUntil} from '@vercel/functions';
import {renderAchievementRegions} from '../scripts/lib/achievement-render.mjs';
import {collectAchievements} from '../scripts/lib/github-achievements.mjs';
import {resolveGitHubToken} from '../scripts/lib/github-auth.mjs';

let cached;
let pending;
let nextRefreshAt = 0;

async function refresh() {
	try {
		const token = await resolveGitHubToken({allowCli: !process.env.VERCEL, timeout: 3000});
		const snapshot = await collectAchievements({token});
		cached = {regions: renderAchievementRegions(snapshot), expiresAt: Date.now() + 3_600_000};
		nextRefreshAt = cached.expiresAt;
	} catch (error) {
		console.warn('GitHub achievement refresh failed.', error.name);
		nextRefreshAt = Date.now() + 600_000;
	}
}

export default function achievements(request, response) {
	if (!['GET', 'HEAD'].includes(request.method)) {
		response.writeHead(405, {Allow: 'GET, HEAD'}).end();
		return;
	}

	const now = Date.now();
	const hasQuery = (request.url ?? '').includes('?');
	if (!hasQuery && !pending && now >= nextRefreshAt) {
		pending = refresh().finally(() => {
			pending = undefined;
		});
		waitUntil(pending);
	}

	if (!cached) {
		// Keep the page's snapshot without replacing a newer CDN response.
		response.writeHead(503, {'Cache-Control': 'no-store'}).end();
		return;
	}

	const maxAge = now < cached.expiresAt ? Math.ceil((cached.expiresAt - now) / 1000) : 60;
	response.writeHead(200, {
		'Content-Type': 'application/json; charset=utf-8',
		'Cache-Control': hasQuery
			? 'no-store'
			: `public, s-maxage=${maxAge}, stale-while-revalidate=86400, stale-if-error=86400`,
		'X-Achievement-Source': 'github',
	});
	response.end(request.method === 'HEAD' ? undefined : JSON.stringify({regions: cached.regions}));
}
