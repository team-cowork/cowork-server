import assert from 'node:assert/strict';
import process from 'node:process';
import {
	afterEach,
	beforeEach,
	describe,
	it,
} from 'node:test';
import middleware from '../middleware.js';

const xml = '<?xml version="1.0" encoding="UTF-8"?><document type="license"><title>MIT License</title></document>';
const originalBaseUrl = process.env.PUBLIC_API_BASE_URL;

function call(path, method = 'GET') {
	return middleware(new Request(`https://promotion.test${path}`, {method}));
}

async function status(path, method) {
	const response = await call(path, method);
	return response.status;
}

function setBaseUrl(value) {
	if (value === undefined) {
		delete process.env.PUBLIC_API_BASE_URL;
	} else {
		process.env.PUBLIC_API_BASE_URL = value;
	}
}

describe('legal document middleware', () => {
	let fetchMock;

	beforeEach(t => {
		setBaseUrl('https://gateway.test');
		fetchMock = t.mock.method(
			globalThis,
			'fetch',
			async () => new Response(xml, {headers: {'content-type': 'application/xml;charset=UTF-8'}}),
		);
	});

	afterEach(() => {
		setBaseUrl(originalBaseUrl);
	});

	it('proxies a supported document as XML from Gateway', async () => {
		const response = await call('/api/legal/license');

		assert.equal(response.status, 200);
		assert.equal(response.headers.get('content-type'), 'application/xml; charset=utf-8');
		assert.equal(await response.text(), xml);
		const [url, options] = fetchMock.mock.calls[0].arguments;
		assert.equal(String(url), 'https://gateway.test/api/legal/license');
		assert.equal(options.headers.accept, 'application/xml');
	});

	it('returns no body for HEAD', async () => {
		const response = await call('/api/legal/license', 'HEAD');

		assert.equal(response.status, 200);
		assert.equal(await response.text(), '');
		assert.equal(fetchMock.mock.calls[0].arguments[1].method, 'HEAD');
	});

	it('rejects unknown document types and other methods without calling Gateway', async () => {
		assert.equal(await status('/api/legal/open-source'), 404);
		assert.equal(await status('/api/legal/license/extra'), 404);

		const post = await call('/api/legal/license', 'POST');
		assert.equal(post.status, 405);
		assert.equal(post.headers.get('allow'), 'GET, HEAD');
		assert.equal(fetchMock.mock.callCount(), 0);
	});

	for (const value of [undefined, 'not a url', 'ftp://gateway.test', 'https://gateway.test/base']) {
		describe(`when PUBLIC_API_BASE_URL is ${value}`, () => {
			beforeEach(() => {
				setBaseUrl(value);
			});

			it('returns 503 without calling Gateway', async () => {
				assert.equal(await status('/api/legal/license'), 503);
				assert.equal(fetchMock.mock.callCount(), 0);
			});
		});
	}

	it('returns 502 when Gateway returns an error status', async () => {
		fetchMock.mock.mockImplementation(async () => new Response('error', {status: 500, headers: {'content-type': 'application/xml'}}));
		assert.equal(await status('/api/legal/license'), 502);
	});

	it('returns 502 when Gateway does not return XML', async () => {
		fetchMock.mock.mockImplementation(async () => new Response('# MIT License', {headers: {'content-type': 'text/markdown'}}));
		assert.equal(await status('/api/legal/license'), 502);
	});

	it('returns 502 when Gateway is unreachable', async () => {
		fetchMock.mock.mockImplementation(async () => {
			throw new TypeError('fetch failed');
		});
		assert.equal(await status('/api/legal/license'), 502);
	});
});
