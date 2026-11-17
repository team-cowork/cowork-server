import {toGraphqlClientError} from './github-graphql.client.js';

describe('toGraphqlClientError', () => {
	it('대상이 없거나 권한이 없는 오류는 클라이언트 오류로 바꾼다', () => {
		expect(toGraphqlClientError([{type: 'NOT_FOUND', message: 'Could not resolve'}])?.statusCode).toBe(404);
		expect(toGraphqlClientError([{type: 'FORBIDDEN', message: 'no'}])?.statusCode).toBe(403);
		expect(toGraphqlClientError([{message: 'Resource not accessible by integration'}])?.statusCode).toBe(403);
	});

	it('rate limit 같은 일시적인 오류는 클라이언트 오류로 보지 않는다', () => {
		expect(toGraphqlClientError([{type: 'RATE_LIMITED', message: 'slow down'}])).toBeUndefined();
	});
});
