import {renderRepositoryCard} from '../../src/design-system/index.mjs';
import {renderAchievements} from './achievement-render.mjs';
import {inlineJson, replaceBundleMarker} from './bundle.mjs';
import {renderTeamMembers, renderTechStacks} from './render.mjs';
import {renderShowcase} from './showcase-render.mjs';
import {replaceGeneratedRegion} from './template.mjs';
import {escapeHtml} from './validation.mjs';

const homePageDescription = '광주소프트웨어마이스터고등학교 학생들이 만드는 협업 관리 플랫폼';

export function concatenateStyles(paths, sources) {
	return sources
		.map((source, index) => `/* ${paths[index]} */\n${source}`)
		.join('\n');
}

function bundlePage({html, styles, stateData, script, logoUrl}) {
	const styleLinks = styles
		.map(url => `<link rel="stylesheet" href="${url}" />`)
		.join('\n');
	const withStyles = replaceBundleMarker(html, 'styles', styleLinks);
	const withState = replaceBundleMarker(withStyles, 'state-data', stateData);
	const withScript = replaceBundleMarker(withState, 'script', script ? `<script type="module" src="${script}"></script>` : '');
	return withScript.replace('href="/logo.svg"', () => `href="${logoUrl}"`);
}

function renderPageMetadata(html, metadata, {siteUrl, socialImageUrl}) {
	const absoluteUrl = route => (siteUrl ? new URL(route, siteUrl).href : route);
	const values = {
		title: metadata.title,
		description: metadata.description,
		type: metadata.type,
		url: absoluteUrl(metadata.route),
		image: siteUrl ? absoluteUrl(socialImageUrl) : '',
	};

	// Og:image must be an absolute URL, so the tag is omitted when no site origin is known.
	let result = siteUrl ? html : html.replaceAll(/^[\t ]*<meta[^>]*\{\{metadata:image\}\}[^>]*>\n?/gmv, '');
	for (const [name, value] of Object.entries(values)) {
		result = result.replaceAll(`{{metadata:${name}}}`, () => escapeHtml(value));
	}

	return result;
}

export function renderAchievementsPage({template, achievements, styles, script, logoUrl, socialImageUrl, siteUrl}) {
	return renderPageMetadata(
		bundlePage({
			html: renderAchievements(template, achievements),
			styles,
			script,
			stateData: '',
			logoUrl,
		}),
		{
			title: '개발 성과 | cowork',
			description: '함께 만든 cowork. 코드의 변화부터 주간 최다 Pull Request, 기여자와 AI 활용까지 개발의 성과를 소개합니다.',
			route: '/achievements/',
			type: 'website',
		},
		{siteUrl, socialImageUrl},
	);
}

export function renderLegalPage({template, metadata, styles, logoUrl, socialImageUrl, siteUrl}) {
	return renderPageMetadata(
		bundlePage({
			html: template, styles, stateData: '', logoUrl,
		}),
		{...metadata, type: 'website'},
		{siteUrl, socialImageUrl},
	);
}

export function renderHomePage({
	template,
	repositories,
	techStacks,
	team,
	featureStates,
	positionStates,
	styles,
	script,
	logoUrl,
	socialImageUrl,
	siteUrl,
}) {
	const repositoryCards = repositories.map(repository => renderRepositoryCard(repository)).join('\n');
	const withRepositories = replaceGeneratedRegion(template, 'repositories', repositoryCards);
	const withTechStacks = replaceGeneratedRegion(withRepositories, 'tech-stacks', renderTechStacks(techStacks.categories));
	let html = replaceGeneratedRegion(withTechStacks, 'team-members', renderTeamMembers(team));

	html = renderShowcase(html, 'features', featureStates, {
		label: '기능',
		backgroundClass: 'feature-index',
		dotClass: 'showcase-dot',
		activeDotWidth: 'var(--indicator-feature-active)',
		inactiveDotColor: 'var(--color-indicator-inverse)',
	});
	html = renderShowcase(html, 'positions', positionStates, {
		label: '포지션',
		backgroundClass: 'position-index',
		dotClass: 'showcase-dot',
		activeDotWidth: 'var(--indicator-active)',
		inactiveDotColor: 'var(--color-indicator)',
	});

	const stateData = [
		['/data/feature-states.json', featureStates],
		['/data/position-states.json', positionStates],
	]
		.map(
			([url, states]) =>
				`<script type="application/json" data-state-url="${url}">${inlineJson(states)}</script>`,
		)
		.join('\n    ');

	return renderPageMetadata(
		bundlePage({
			html, styles, stateData, script, logoUrl,
		}),
		{
			title: 'cowork',
			description: homePageDescription,
			route: '/',
			type: 'website',
		},
		{siteUrl, socialImageUrl},
	);
}
