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

export default async function legalDocument(request, response) {
	response.setHeader('Cache-Control', 'no-store');
	if (!['GET', 'HEAD'].includes(request.method)) {
		response.writeHead(405, {Allow: 'GET, HEAD'}).end();
		return;
	}

	const {pathname} = new URL(request.url ?? '/', 'http://localhost');
	const type = pathname.match(/^\/api\/legal\/(?<type>[^\/]+)\/?$/v)?.groups.type;
	if (!documentTypes.has(type)) {
		response.writeHead(404).end();
		return;
	}

	if (!gateway) {
		response.writeHead(503).end();
		return;
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
			response.writeHead(502).end();
			return;
		}

		const xml = await upstream.text();
		const headers = {
			'Content-Type': 'application/xml; charset=utf-8',
			'Cache-Control': upstream.headers.get('cache-control') ?? 'no-store',
		};
		for (const name of ['age', 'date']) {
			const value = upstream.headers.get(name);
			if (value !== null) {
				headers[name] = value;
			}
		}

		response.writeHead(200, headers);
		response.end(request.method === 'HEAD' ? undefined : xml);
	} catch {
		response.writeHead(502).end();
	}
}
