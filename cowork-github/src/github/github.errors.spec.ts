import {AxiosError, AxiosHeaders, type AxiosResponse} from 'axios';
import {GithubClientError, GithubRateLimitError, toGithubError} from './github.errors.js';

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
		const primary = toGithubError(responseError(403, headers));
		expect(primary).toBeInstanceOf(GithubRateLimitError);
		expect((primary as GithubRateLimitError).retryDelayMs).toBeGreaterThan(118_000);
		const combined = toGithubError(responseError(403, {...headers, 'Retry-After': '7'}));
		expect((combined as GithubRateLimitError).retryDelayMs).toBeGreaterThan(118_000);
		expect(toGithubError(responseError(403, {'Retry-After': '7'}))).toHaveProperty('retryDelayMs', 7000);
		expect(toGithubError(responseError(429, {'Retry-After': '8'}))).toHaveProperty('retryDelayMs', 8000);
	});

	it('detects limits without a valid delay and preserves other errors', () => {
		expect(toGithubError(responseError(403, {}, 'You have exceeded a secondary rate limit.'))).toBeInstanceOf(GithubRateLimitError);
		expect(toGithubError(responseError(429))).toBeInstanceOf(GithubRateLimitError);
		expect(toGithubError(responseError(429, {'Retry-After': 'invalid'}))).toHaveProperty('retryDelayMs', undefined);
		expect(toGithubError(responseError(403, {'X-RateLimit-Remaining': '10'}))).toBeInstanceOf(GithubClientError);
		const unavailable = responseError(503, {'Retry-After': '8'});
		expect(toGithubError(unavailable)).toBe(unavailable);
	});
});
