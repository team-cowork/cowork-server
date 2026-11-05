import process from 'node:process';
import {pathToFileURL} from 'node:url';
import {addTechnologyLogos, createAssetCollection} from './lib/assets.mjs';
import {resolveBuildConfig} from './lib/build-config.mjs';
import {loadBuildInput, stylesheetPaths} from './lib/build-input.mjs';
import {
	parseContent,
	parseFeatureStates,
	parseRepositories,
} from './lib/content.mjs';
import {generateFeatureStates} from './lib/feature-render.mjs';
import {replaceOutputDirectory} from './lib/output.mjs';
import {
	concatenateStyles,
	renderAchievementsPage,
	renderHomePage,
	renderLegalPage,
} from './lib/page-render.mjs';
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
		technologyLogos: await addTechnologyLogos(assets),
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

	const achievementsHtml = renderAchievementsPage({
		template: input.achievementsTemplate,
		achievements: JSON.parse(input.achievementSource),
		script: assets.add('achievements', 'js', input.achievementScriptBundle),
		styles: [
			sharedStyleUrl,
			addStylesheet(
				assets,
				'achievements',
				stylesheetPaths.achievements,
				input.stylesheetSources.achievements,
			),
		],
		logoUrl,
		socialImageUrl,
		siteUrl: config.siteUrl,
	});
	const legalPageOptions = {
		styles: [
			sharedStyleUrl,
			addStylesheet(assets, 'legal', stylesheetPaths.legal, input.stylesheetSources.legal),
		],
		logoUrl,
		socialImageUrl,
		siteUrl: config.siteUrl,
	};
	const privacyHtml = renderLegalPage({
		...legalPageOptions,
		template: input.privacyTemplate,
		metadata: {
			title: '개인정보처리방침 | cowork',
			description: 'team.cowork의 개인정보 처리 목적과 항목, 이용자의 권리 및 문의 방법을 안내합니다.',
			route: '/privacy/',
		},
	});
	const termsHtml = renderLegalPage({
		...legalPageOptions,
		template: input.termsTemplate,
		metadata: {
			title: '서비스 이용약관 | cowork',
			description: 'cowork 서비스 이용에 관한 이용자와 team.cowork의 권리, 의무 및 이용 조건을 안내합니다.',
			route: '/terms/',
		},
	});
	const licenseHtml = renderLegalPage({
		...legalPageOptions,
		template: input.licenseTemplate,
		metadata: {
			title: '라이선스 | cowork',
			description: 'cowork-server의 MIT 라이선스 원문과 저작권 고지를 안내합니다.',
			route: '/license/',
		},
	});
	const outputFiles = new Map([
		...assets.files,
		['index.html', homeHtml],
		['achievements/index.html', achievementsHtml],
		['privacy/index.html', privacyHtml],
		['terms/index.html', termsHtml],
		['license/index.html', licenseHtml],
	]);

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
