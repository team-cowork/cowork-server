import {readFile} from 'node:fs/promises';
import {
	componentStylesheets,
	foundationStylesheets,
} from '../../src/design-system/index.mjs';
import {bundleJavaScript} from './bundle.mjs';
import {composeTemplate} from './template.mjs';

const projectDirectory = new URL('../../', import.meta.url);
const sourceDirectory = new URL('../../src/', import.meta.url);
const htmlDirectory = new URL('html/', sourceDirectory);

export const stylesheetPaths = Object.freeze({
	shared: [
		...foundationStylesheets,
		'css/base.css',
		'css/utilities.css',
		'css/responsive.css',
		...componentStylesheets,
		'css/site.css',
	],
	home: ['css/home.css', 'css/showcase.css'],
	achievements: ['css/achievements.css', 'css/achievement-showcase.css', 'css/achievement-responsive.css'],
});

function projectUrl(path) {
	return new URL(path, projectDirectory);
}

function sourceUrl(path) {
	return new URL(path, sourceDirectory);
}

function loadStylesheets(paths) {
	return Promise.all(paths.map(path => readFile(sourceUrl(path), 'utf8')));
}

export async function loadBuildInput() {
	const [
		homeTemplate,
		techStackSource,
		repositorySource,
		teamSource,
		featureStateSource,
		logoSource,
		socialImageSource,
		homeScriptBundle,
		sharedStylesheetSources,
		homeStylesheetSources,
		achievementsTemplate,
		achievementSource,
		achievementStylesheetSources,
		achievementScriptBundle,
	] = await Promise.all([
		composeTemplate(new URL('index.html', htmlDirectory), htmlDirectory),
		readFile(projectUrl('data/tech-stacks.yaml'), 'utf8'),
		readFile(projectUrl('data/repositories.json'), 'utf8'),
		readFile(projectUrl('data/team-members.xml'), 'utf8'),
		readFile(projectUrl('data/feature-states.json'), 'utf8'),
		readFile(projectUrl('logo.svg'), 'utf8'),
		readFile(projectUrl('social-card.png')),
		bundleJavaScript(sourceUrl('js/main.js'), sourceDirectory),
		loadStylesheets(stylesheetPaths.shared),
		loadStylesheets(stylesheetPaths.home),
		composeTemplate(new URL('achievements.html', htmlDirectory), htmlDirectory),
		readFile(projectUrl('data/achievements.json'), 'utf8'),
		loadStylesheets(stylesheetPaths.achievements),
		bundleJavaScript(sourceUrl('js/achievements.js'), sourceDirectory),
	]);

	return {
		homeTemplate,
		techStackSource,
		repositorySource,
		teamSource,
		featureStateSource,
		logoSource,
		socialImageSource,
		homeScriptBundle,
		achievementsTemplate,
		achievementSource,
		achievementScriptBundle,
		stylesheetSources: {
			shared: sharedStylesheetSources,
			home: homeStylesheetSources,
			achievements: achievementStylesheetSources,
		},
	};
}
