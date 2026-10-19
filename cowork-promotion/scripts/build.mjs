import process from 'node:process';
import {pathToFileURL} from 'node:url';
import {createAssetCollection} from './lib/assets.mjs';
import {resolveBuildConfig} from './lib/build-config.mjs';
import {loadBuildInput, stylesheetPaths} from './lib/build-input.mjs';
import {
	parseContent,
	parseFeatureStates,
	parseRepositories,
} from './lib/content.mjs';
import {generateFeatureStates} from './lib/feature-render.mjs';
import {replaceOutputDirectory} from './lib/output.mjs';
import {concatenateStyles, renderHomePage} from './lib/page-render.mjs';
import {generatePositionStates} from './lib/render.mjs';

function addStylesheet(assets, name, paths, sources) {
	return assets.add(name, 'css', concatenateStyles(paths, sources));
}

export async function build(options = {}) {
	const config = resolveBuildConfig(options);
	const input = await loadBuildInput();
	const assets = createAssetCollection();
	const {team, techStacks} = parseContent({
		teamXml: input.teamSource,
		techStackYaml: input.techStackSource,
	});
	const repositories = parseRepositories(input.repositorySource);
	const positionStates = generatePositionStates(techStacks.positions, team);
	const featureStates = await generateFeatureStates(
		parseFeatureStates(input.featureStateSource),
	);

	const sharedStyleUrl = addStylesheet(
		assets,
		'shared',
		stylesheetPaths.shared,
		input.stylesheetSources.shared,
	);
	const logoUrl = assets.add('logo', 'svg', input.logoSource);
	const socialImageUrl = assets.add('social-card', 'png', input.socialImageSource);
	const homeHtml = renderHomePage({
		template: input.homeTemplate,
		repositories,
		techStacks,
		team,
		featureStates,
		positionStates,
		styles: [
			sharedStyleUrl,
			addStylesheet(
				assets,
				'home',
				stylesheetPaths.home,
				input.stylesheetSources.home,
			),
		],
		script: assets.add('home', 'js', input.homeScriptBundle),
		logoUrl,
		socialImageUrl,
		siteUrl: config.siteUrl,
	});

	const outputFiles = new Map([...assets.files, ['index.html', homeHtml]]);

	await replaceOutputDirectory(config.outputDirectory, outputFiles);

	const summary = {
		teamMembers: team.length,
		techGroups: techStacks.categories.length,
	};
	console.log(
		`Promotion site built in public/ (${summary.techGroups} tech groups, ${summary.teamMembers} team members).`,
	);
	return summary;
}

const isDirectRun
	= process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isDirectRun) {
	await build();
}
