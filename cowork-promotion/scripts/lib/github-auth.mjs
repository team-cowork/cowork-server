import {execFile} from 'node:child_process';
import process from 'node:process';
import {promisify} from 'node:util';

const execute = promisify(execFile);

export async function resolveGitHubToken({allowCli = true, timeout} = {}) {
	const token = process.env.GITHUB_TOKEN?.trim();
	if (token) {
		return token;
	}

	if (allowCli) {
		try {
			const {stdout} = await execute('gh', ['auth', 'token'], {maxBuffer: 64 * 1024, timeout});
			if (stdout.trim()) {
				return stdout.trim();
			}
		} catch {
			// Callers decide whether authentication failure should use a snapshot or stop collection.
		}
	}

	throw new Error('GitHub authentication is unavailable. Run gh auth login or set GITHUB_TOKEN.');
}
