import {AxiosError} from 'axios';

export class GithubClientError extends Error {
	constructor(
		message: string,
		public readonly statusCode: number,
	) {
		super(message);
		this.name = 'GithubClientError';
	}
}

export function rethrowGithubError(error: unknown): never {
	if (
		error instanceof AxiosError
		&& error.response
		&& error.response.status < 500
		&& error.response.status !== 429
	) {
		throw new GithubClientError(
			(error.response.data as {message?: string})?.message ?? error.message,
			error.response.status,
		);
	}

	throw error;
}
