import process from 'node:process';

const documentTypes = new Set(['privacy-policy', 'terms-of-service', 'license']);
const gateway = resolveGateway();

function resolveGateway() {
	try {
		const origin = new URL(process.env.PUBLIC_API_BASE_URL);
		if (!['http:', 'https:'].includes(origin.protocol)
			|| origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) {
			return undefined;
		}

		return origin;
	} catch {
		return undefined;
	}
}

export const config = {matcher: '/api/legal/:type', runtime: 'nodejs'};

export default async function middleware(request) {
	const headers = {'Cache-Control': 'no-store'};
	if (!['GET', 'HEAD'].includes(request.method)) {
		return new Response(null, {status: 405, headers: {...headers, Allow: 'GET, HEAD'}});
	}

	const {pathname} = new URL(request.url);
	const type = pathname.match(/^\/api\/legal\/(?<type>[^\/]+)\/?$/v)?.groups.type;
	if (!documentTypes.has(type)) {
		return new Response(null, {status: 404, headers});
	}

	if (!gateway) {
		return new Response(null, {status: 503, headers});
	}

	try {
		const upstream = await fetch(new URL(`/api/legal/${type}`, gateway), {
			method: request.method,
			headers: {accept: 'application/xml'},
			signal: AbortSignal.timeout(10_000),
		});
		const contentType = upstream.headers.get('content-type')?.split(';', 1)[0].trim().toLowerCase();
		if (!upstream.ok || !['application/xml', 'text/xml'].includes(contentType)) {
			await upstream.body?.cancel();
			return new Response(null, {status: 502, headers});
		}

		const xml = await upstream.text();
		const responseHeaders = {
			'Content-Type': 'application/xml; charset=utf-8',
			'Cache-Control': upstream.headers.get('cache-control') ?? 'no-store',
		};
		for (const name of ['age', 'date']) {
			const value = upstream.headers.get(name);
			if (value !== null) {
				responseHeaders[name] = value;
			}
		}

		return new Response(request.method === 'HEAD' ? null : xml, {
			headers: responseHeaders,
		});
	} catch {
		return new Response(null, {status: 502, headers});
	}
}
