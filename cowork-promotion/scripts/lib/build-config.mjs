import {execFileSync} from 'node:child_process';
import path from 'node:path';
import process from 'node:process';
import {fileURLToPath, pathToFileURL} from 'node:url';

const defaultOutputDirectory = new URL('../../public/', import.meta.url);
const defaultTodoDirectory = fileURLToPath(
	new URL('../../../docs/todo/', import.meta.url),
);
const defaultRepositoryDirectory = fileURLToPath(
	new URL('../../../', import.meta.url),
);

function directoryUrl(value, fallback) {
	if (!value) {
		return fallback;
	}

	return value instanceof URL ? value : pathToFileURL(`${path.resolve(String(value))}/`);
}

function resolveRepositorySourceUrl(repositoryDirectory, environment) {
	let revision = environment.VERCEL_GIT_COMMIT_SHA || environment.GITHUB_SHA;

	if (!revision) {
		try {
			revision = execFileSync('git', ['rev-parse', 'HEAD'], {
				cwd: repositoryDirectory,
				encoding: 'utf8',
				stdio: ['ignore', 'pipe', 'ignore'],
			}).trim();
		} catch (error) {
			throw new Error(
				'Cannot determine the repository revision; provide repositorySourceUrl when building without Git metadata.',
				{cause: error},
			);
		}
	}

	return `https://github.com/team-cowork/cowork-server/blob/${encodeURIComponent(revision)}/`;
}

function resolveSiteUrl(options, environment) {
	const deploymentHost
		= environment.VERCEL_PROJECT_PRODUCTION_URL || environment.VERCEL_URL;
	const configuredSiteUrl
		= options.siteUrl
			|| environment.SITE_URL
			|| (deploymentHost ? `https://${deploymentHost}` : null);
	const siteUrl = configuredSiteUrl ? new URL(configuredSiteUrl) : null;

	if (
		siteUrl
		&& (!['http:', 'https:'].includes(siteUrl.protocol)
			|| siteUrl.username
			|| siteUrl.password
			|| siteUrl.pathname !== '/'
			|| siteUrl.search
			|| siteUrl.hash)
	) {
		throw new Error(
			'SITE_URL must be an HTTP(S) origin without a path, credentials, query or fragment.',
		);
	}

	return siteUrl;
}

export function resolveBuildConfig(options = {}, environment = process.env) {
	const repositoryDirectory = options.repositoryDirectory
		? path.resolve(String(options.repositoryDirectory))
		: defaultRepositoryDirectory;

	return {
		outputDirectory: directoryUrl(
			options.outputDirectory,
			defaultOutputDirectory,
		),
		todoDirectory: options.todoDirectory
			? path.resolve(String(options.todoDirectory))
			: defaultTodoDirectory,
		repositoryDirectory,
		repositorySourceUrl:
            options.repositorySourceUrl
            ?? resolveRepositorySourceUrl(repositoryDirectory, environment),
		siteUrl: resolveSiteUrl(options, environment),
	};
}
