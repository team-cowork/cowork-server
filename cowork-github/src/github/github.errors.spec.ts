import {AxiosError, AxiosHeaders, type AxiosResponse} from 'axios';
import {GithubRateLimitError, rethrowGithubError, toGithubRateLimitError} from './github.errors.js';

function responseError(status: number, headers: Record<string, string> = {}, message = 'Forbidden'): AxiosError {
	const response: AxiosResponse = {
		status,
		statusText: message,
		headers: new AxiosHeaders(headers),
		data: {message},
		config: {headers: new AxiosHeaders()},
	};
	return new AxiosError('GitHub request failed', undefined, undefined, undefined, response);
}

describe('GitHub API rate limit', () => {
	it('uses the provider delay for primary and secondary limits', () => {
		const reset = String(Math.floor(Date.now() / 1000) + 120);
		const headers = {'X-RateLimit-Remaining': '0', 'X-RateLimit-Reset': reset};
		const primary = toGithubRateLimitError(responseError(403, headers));
		expect(primary).toBeInstanceOf(GithubRateLimitError);
		expect(primary?.retryDelayMs).toBeGreaterThan(118_000);
		const combined = toGithubRateLimitError(responseError(403, {...headers, 'Retry-After': '7'}));
		expect(combined?.retryDelayMs).toBeGreaterThan(118_000);
		expect(toGithubRateLimitError(responseError(403, {'Retry-After': '7'}))).toHaveProperty('retryDelayMs', 7000);
		expect(toGithubRateLimitError(responseError(429, {'Retry-After': '8'}))).toHaveProperty('retryDelayMs', 8000);
	});

	it('detects limits without a valid delay and preserves other errors', () => {
		expect(toGithubRateLimitError(responseError(403, {}, 'You have exceeded a secondary rate limit.'))).toBeInstanceOf(GithubRateLimitError);
		expect(toGithubRateLimitError(responseError(429))).toBeInstanceOf(GithubRateLimitError);
		expect(toGithubRateLimitError(responseError(429, {'Retry-After': 'invalid'}))).toHaveProperty('retryDelayMs', undefined);
		expect(toGithubRateLimitError(responseError(403, {'X-RateLimit-Remaining': '10'}))).toBeUndefined();
		expect(toGithubRateLimitError(responseError(503, {'Retry-After': '8'}))).toBeUndefined();
	});
});

describe('rethrowGithubError', () => {
	it('keeps the existing HTTP mapping of a rate-limited 403', async () => {
		const error = responseError(403, {'Retry-After': '7'});
		await expect(Promise.try(() => rethrowGithubError(error))).rejects.toMatchObject({
			name: 'GithubClientError',
			message: 'Forbidden',
			statusCode: 403,
		});
	});

	it.each([429, 503])('keeps the original HTTP error for %d', async status => {
		const error = responseError(status, {'Retry-After': '7'});
		await expect(Promise.try(() => rethrowGithubError(error))).rejects.toBe(error);
	});
});
