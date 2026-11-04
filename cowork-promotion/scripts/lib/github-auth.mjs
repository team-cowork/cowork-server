import process from 'node:process';

export function resolveGitHubToken() {
	const token = process.env.GITHUB_TOKEN?.trim();
	if (token) {
		return token;
	}

	throw new Error('GITHUB_TOKEN is required.');
}
