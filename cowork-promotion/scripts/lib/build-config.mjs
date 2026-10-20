import path from 'node:path';
import process from 'node:process';
import {pathToFileURL} from 'node:url';

const defaultOutputDirectory = new URL('../../public/', import.meta.url);

function directoryUrl(value, fallback) {
	if (!value) {
		return fallback;
	}

	return value instanceof URL ? value : pathToFileURL(`${path.resolve(String(value))}/`);
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
	return {
		outputDirectory: directoryUrl(
			options.outputDirectory,
			defaultOutputDirectory,
		),
		siteUrl: resolveSiteUrl(options, environment),
	};
}
