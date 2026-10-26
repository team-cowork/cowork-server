import {replaceGeneratedRegion} from './template.mjs';
import {escapeHtml} from './validation.mjs';

const numberFormat = new Intl.NumberFormat('ko-KR');

function formatNumber(value) {
	if (!Number.isSafeInteger(value) || value < 0) {
		throw new Error('Achievement metrics must be non-negative integers.');
	}

	return numberFormat.format(value);
}

function renderPeakActivity(count) {
	formatNumber(count);
	return Array.from({length: count}, (_, index) =>
		`<span class="achievement-pr-token" style="--pr-index: ${index}"></span>`).join('\n');
}

function renderContributors(contributors) {
	return contributors.map(({login}) => {
		if (typeof login !== 'string' || !/^[\w\-]+$/v.test(login)) {
			throw new Error('Invalid achievement contributor login.');
		}

		return `<a class="achievement-person" href="https://github.com/${login}" target="_blank" rel="noopener noreferrer">
			<img src="https://github.com/${login}.png?size=96" alt="" width="48" height="48" loading="lazy" decoding="async">
			<span>${escapeHtml(login)}</span>
		</a>`;
	}).join('\n');
}

export function renderAchievementRegions(data) {
	if (data.schemaVersion !== 1 || !Array.isArray(data.contributors)) {
		throw new Error('Unsupported achievement snapshot.');
	}

	const {totals} = data;
	return {
		'achievement-summary': `<p class="achievement-code-count">${formatNumber(totals.changedLines)}<span>라인</span></p>
		<div class="achievement-diff-stats">
			<span class="achievement-diff-add">+${formatNumber(totals.additions)}<span class="sr-only"> 추가</span></span>
			<span class="achievement-diff-remove">−${formatNumber(totals.deletions)}<span class="sr-only"> 삭제</span></span>
		</div>`,
		'achievement-pr-summary': `<p class="achievement-number">${formatNumber(data.peakWeek.pullRequests)}<span>개</span></p>`,
		'achievement-people-summary': `<p class="achievement-number">${formatNumber(totals.contributors)}<span>명</span></p>`,
		'achievements-contributors': renderContributors(data.contributors),
		'achievement-pr-activity': renderPeakActivity(data.peakWeek.pullRequests),
		'achievement-commit-summary': `<p class="achievement-number">${formatNumber(totals.commits)}<span>커밋</span></p>`,
	};
}

export function renderAchievements(template, data) {
	let html = template;
	for (const [name, content] of Object.entries(renderAchievementRegions(data))) {
		html = replaceGeneratedRegion(html, name, content);
	}

	const symbols = ['{}', '=>', '()', '[]', '+', '&&', ';', '</>'];
	const cells = Array.from({length: 100}, (_, index) =>
		`<span class="achievement-ai-cell${index >= 98 ? ' achievement-ai-cell--human' : ''}" style="--cell-index: ${index}">${escapeHtml(symbols[index % symbols.length])}</span>`);
	return replaceGeneratedRegion(html, 'achievement-ai-grid', cells.join('\n'));
}
