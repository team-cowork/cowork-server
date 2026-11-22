import {AxiosError, AxiosHeaders, type RawAxiosHeaders} from 'axios';

export class GithubClientError extends Error {
	constructor(
		message: string,
		public readonly statusCode: number,
	) {
		super(message);
		this.name = 'GithubClientError';
	}
}

export class GithubRateLimitError extends Error {
	constructor(
		message: string,
		public readonly retryDelayMs?: number,
	) {
		super(message);
		this.name = 'GithubRateLimitError';
	}
}

function retryAfterMs(value: unknown, now: number): number | undefined {
	if (typeof value === 'number' || (typeof value === 'string' && /^\d+$/v.test(value))) {
		const seconds = Number(value);
		return Number.isSafeInteger(seconds * 1000) && seconds >= 0 ? seconds * 1000 : undefined;
	}

	const date = typeof value === 'string' && /gmt$/iv.test(value) ? Date.parse(value) : NaN;
	return Number.isFinite(date) ? Math.max(0, date - now) : undefined;
}

export function toGithubRateLimitError(error: unknown): GithubRateLimitError | undefined {
	if (!(error instanceof AxiosError) || !error.response) {
		return undefined;
	}

	const {headers, status} = error.response;
	if (status !== 403 && status !== 429) {
		return undefined;
	}

	const message = (error.response.data as {message?: string})?.message ?? error.message;
	const responseHeaders = AxiosHeaders.from(headers as RawAxiosHeaders);
	const remaining = responseHeaders.get('x-ratelimit-remaining');
	const isExhausted = remaining === '0' || remaining === 0;
	const retryAfter = responseHeaders.get('retry-after');
	if (status === 403 && !isExhausted && retryAfter === undefined && !/secondary rate limit/iv.test(message)) {
		return undefined;
	}

	const now = Date.now();
	const reset = Number(responseHeaders.get('x-ratelimit-reset'));
	const resetDelay = isExhausted && Number.isSafeInteger(reset * 1000) && reset > 0
		? Math.max(0, (reset * 1000) - now)
		: undefined;
	const retryAfterDelay = retryAfterMs(retryAfter, now);
	const retryDelay = resetDelay === undefined ? retryAfterDelay : Math.max(resetDelay, retryAfterDelay ?? 0);
	return new GithubRateLimitError(message, retryDelay);
}

// 429와 5xx는 그대로 던져 기존 HTTP 호출부가 502로 응답하게 한다.
export function rethrowGithubError(error: unknown): never {
	if (error instanceof AxiosError && error.response && error.response.status < 500 && error.response.status !== 429) {
		throw new GithubClientError(
			(error.response.data as {message?: string})?.message ?? error.message,
			error.response.status,
		);
	}

	throw error;
}
