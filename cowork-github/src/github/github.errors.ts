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

// 429와 5xx는 GitHub 쪽 일시 장애라 요청자 오류로 바꾸지 않고 그대로 던져 호출부가 502로 응답하게 한다.
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
