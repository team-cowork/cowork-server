import {renderRepositoryCard} from '../../src/design-system/index.mjs';
import {
	homePageDescription,
	todoPageMetadata,
} from '../../src/js/core/page-metadata.js';
import {inlineJson, replaceBundleMarker} from './bundle.mjs';
import {renderTeamMembers, renderTechStacks} from './render.mjs';
import {renderShowcase} from './showcase-render.mjs';
import {replaceGeneratedRegion} from './template.mjs';
import {
	renderTodoDocument,
	renderTodoHistory,
	renderTodoItems,
	renderTodoToc,
} from './todo-render.mjs';
import {escapeHtml} from './validation.mjs';

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
	const withScript = replaceBundleMarker(withState, 'script', `<script type="module" src="${script}"></script>`);
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

function todoListTitle(document) {
	if (document.kind !== 'snapshot') {
		return undefined;
	}

	return document.description ? `${document.displayDate} — ${document.description}` : document.displayDate || document.title;
}

export function createTodoRegistry(content, assets) {
	return {
		documents: content.documents.map(document => {
			const {
				id,
				route,
				title,
				kind,
				priority,
				priorityLabel,
				sourceOrder,
				active,
				searchText,
				summary,
			} = document;
			const contentUrl = assets.add(
				'todo-document',
				'json',
				JSON.stringify({
					id,
					route,
					metadata: document.metadata,
					toc: document.toc,
					bodyHtml: document.bodyHtml,
				}),
			);

			return {
				id,
				route,
				title,
				kind,
				priority,
				priorityLabel,
				sourceOrder,
				active,
				searchText,
				summary,
				listTitle: todoListTitle(document),
				contentUrl,
			};
		}),
		activeItems: content.activeItems.map(({id}) => id),
		history: content.snapshots.map(({id}) => id),
	};
}

export function renderTodoPage({
	template,
	activeItems,
	snapshots,
	initialDocument,
	styles,
	stateData,
	script,
	logoUrl,
	socialImageUrl,
	siteUrl,
}) {
	let html = replaceGeneratedRegion(
		replaceGeneratedRegion(
			template,
			'todo-items',
			initialDocument ? '' : renderTodoItems(activeItems),
		),
		'todo-history',
		initialDocument ? '' : renderTodoHistory(snapshots),
	);

	html = replaceGeneratedRegion(
		replaceGeneratedRegion(
			html,
			'todo-document',
			initialDocument ? renderTodoDocument(initialDocument) : '',
		),
		'todo-toc',
		initialDocument ? renderTodoToc(initialDocument.toc) : '',
	);

	if (initialDocument) {
		html = html
			.replace('href="#main-content"', 'href="#todo-document-title"')
			.replace('class="todo-page"', 'class="todo-page todo-modal-open"')
			.replace(
				'data-initial-document-id=""',
				() => `data-initial-document-id="${escapeHtml(initialDocument.id)}"`,
			)
			.replace(
				'  data-todo-dashboard\n  tabindex',
				'  data-todo-dashboard\n  hidden\n  tabindex',
			)
			.replace(
				'  data-todo-dialog\n  aria',
				'  data-todo-dialog\n  open\n  aria',
			);

		if (initialDocument.toc.length > 0) {
			html = html.replace(
				'data-todo-toc-rail hidden',
				'data-todo-toc-rail',
			);
		}
	}

	return renderPageMetadata(
		bundlePage({
			html, styles, stateData, script, logoUrl,
		}),
		todoPageMetadata(initialDocument),
		{siteUrl, socialImageUrl},
	);
}
