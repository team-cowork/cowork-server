// GitHub setup 콜백의 OAuth `code`와 `state`는 로그에 남기지 않는다.
const SENSITIVE_QUERY_KEYS = ['code', 'state'];

export function stripSensitiveQuery(url: string): string {
	const queryStart = url.indexOf('?');
	if (queryStart === -1) {
		return url;
	}

	const parameters = new URLSearchParams(url.slice(queryStart + 1));
	for (const key of SENSITIVE_QUERY_KEYS) {
		parameters.delete(key);
	}

	const path = url.slice(0, queryStart);
	const query = parameters.toString();
	return query === '' ? path : `${path}?${query}`;
}
