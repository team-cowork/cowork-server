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

export function toGithubError(error: unknown): unknown {
	if (error instanceof AxiosError && error.response && error.response.status < 500) {
		const {headers, status} = error.response;
		const message = (error.response.data as {message?: string})?.message ?? error.message;

		if (status === 403 || status === 429) {
			const responseHeaders = AxiosHeaders.from(headers as RawAxiosHeaders);
			const remaining = responseHeaders.get('x-ratelimit-remaining');
			const isExhausted = remaining === '0' || remaining === 0;
			const retryAfter = responseHeaders.get('retry-after');
			if (status === 429 || isExhausted || retryAfter !== undefined || /secondary rate limit/iv.test(message)) {
				const now = Date.now();
				const reset = Number(responseHeaders.get('x-ratelimit-reset'));
				const resetDelay = isExhausted && Number.isSafeInteger(reset * 1000) && reset > 0
					? Math.max(0, (reset * 1000) - now)
					: undefined;
				const retryAfterDelay = retryAfterMs(retryAfter, now);
				const retryDelay = resetDelay === undefined ? retryAfterDelay : Math.max(resetDelay, retryAfterDelay ?? 0);
				return new GithubRateLimitError(message, retryDelay);
			}
		}

		return new GithubClientError(message, status);
	}

	return error;
}

export function rethrowGithubError(error: unknown): never {
	throw toGithubError(error);
}
