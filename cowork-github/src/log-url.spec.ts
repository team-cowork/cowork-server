import {stripSensitiveQuery} from './log-url.js';

describe('stripSensitiveQuery', () => {
	it('removes code and state and keeps other query parameters', () => {
		expect(stripSensitiveQuery('/github/setup?installation_id=1&setup_action=install&state=s1&code=c1'))
			.toBe('/github/setup?installation_id=1&setup_action=install');
	});

	it('drops the question mark when no parameter is left', () => {
		expect(stripSensitiveQuery('/github/setup?code=c1&state=s1')).toBe('/github/setup');
	});

	it('returns a url without query unchanged', () => {
		expect(stripSensitiveQuery('/api/repos/o/r/pulls/1/merge')).toBe('/api/repos/o/r/pulls/1/merge');
	});
});
