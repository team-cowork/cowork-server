import {createServer} from 'node:http';
import path from 'node:path';
import process from 'node:process';
import {readFile, stat, watch} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {build} from './build.mjs';

const projectDirectory = path.resolve(fileURLToPath(new URL('../', import.meta.url)));
const todoDirectory = path.resolve(projectDirectory, '../docs/todo');
const rootDirectory = path.resolve(process.argv[2] ?? '.');
const watchMode = process.argv.includes('--watch');
const port = Number(process.env.PORT ?? 3000);
const liveReloadClients = new Set();
const contentTypes = {
	'.css': 'text/css; charset=utf-8',
	'.html': 'text/html; charset=utf-8',
	'.js': 'text/javascript; charset=utf-8',
	'.json': 'application/json; charset=utf-8',
	'.svg': 'image/svg+xml',
};
const liveReloadClient = `const events = new EventSource("/__dev/events");
events.addEventListener("reload", () => window.location.reload());
events.addEventListener("build-error", (event) => {
    const detail = JSON.parse(event.data);
    console.error("라이브 리로드 빌드에 실패했습니다.", detail.message);
});`;

function resolveRequestPath(pathname) {
	const relativePath = path.normalize(pathname).replace(/^[\/\\]+/v, '');
	const filePath = path.resolve(path.join(rootDirectory, relativePath));

	return filePath !== rootDirectory && !filePath.startsWith(`${rootDirectory}${path.sep}`) ? null : filePath;
}

function openEventStream(request, response) {
	response.writeHead(200, {
		'Cache-Control': 'no-cache, no-transform',
		Connection: 'keep-alive',
		'Content-Type': 'text/event-stream; charset=utf-8',
		'X-Accel-Buffering': 'no',
	});
	response.write('retry: 1000\nevent: connected\ndata: {}\n\n');
	liveReloadClients.add(response);

	request.on('close', () => liveReloadClients.delete(response));
}

function broadcast(event, data) {
	const message = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
	liveReloadClients.forEach(client => {
		try {
			client.write(message);
		} catch {
			liveReloadClients.delete(client);
		}
	});
}

function injectLiveReload(html) {
	const script = '<script type="module" src="/__dev/reload.js"></script>';
	return html
		.replace('<html', () => `<html data-dev-render-id="${Date.now()}"`)
		.replace('</body>', () => `  ${script}\n  </body>`);
}

async function serveFile(requestUrl, response) {
	const url = new URL(requestUrl, 'http://localhost');

	if (watchMode && url.pathname === '/__dev/events') {
		return {eventStream: true, url};
	}

	if (watchMode && url.pathname === '/__dev/reload.js') {
		response.writeHead(200, {
			'Cache-Control': 'no-store',
			'Content-Type': contentTypes['.js'],
		});
		response.end(liveReloadClient);
		return {eventStream: false, url};
	}

	let filePath = resolveRequestPath(decodeURIComponent(url.pathname));
	if (!filePath) {
		response.writeHead(403).end('Forbidden');
		return {eventStream: false, url};
	}

	const fileStats = await stat(filePath);
	if (fileStats.isDirectory()) {
		filePath = path.join(filePath, 'index.html');
	}

	const extension = path.extname(filePath);
	const file = await readFile(filePath);
	const content
		= watchMode && extension === '.html'
			? injectLiveReload(file.toString('utf8'))
			: file;
	response.writeHead(200, {
		'Cache-Control': 'no-cache',
		'Content-Type': contentTypes[extension] ?? 'application/octet-stream',
	});
	response.end(content);
	return {eventStream: false, url};
}

const server = createServer(async (request, response) => {
	try {
		const result = await serveFile(request.url ?? '/', response);
		if (result.eventStream) {
			openEventStream(request, response);
		}
	} catch (error) {
		const status = error?.code === 'ENOENT' ? 404 : 500;
		response.writeHead(status).end(status === 404 ? 'Not Found' : 'Internal Server Error');
	}
});

const watchController = new AbortController();
let rebuildTimer;
let isBuilding = false;
let isRebuildQueued = false;
let latestChangedPath = '';

function shouldRebuild(filename) {
	if (!filename) {
		return false;
	}

	const normalized = path.normalize(String(filename));
	return normalized === 'logo.svg' || normalized.startsWith(`src${path.sep}`) || normalized.startsWith(`data${path.sep}`);
}

async function rebuild() {
	if (isBuilding) {
		isRebuildQueued = true;
		return;
	}

	isBuilding = true;
	try {
		const summary = await build();
		console.log(`Reload: ${latestChangedPath}`);
		broadcast('reload', {changed: latestChangedPath, summary});
	} catch (error) {
		console.error('Live reload build failed.', error);
		broadcast('build-error', {message: error.message});
	} finally {
		isBuilding = false;
		if (isRebuildQueued) {
			isRebuildQueued = false;
			await rebuild();
		}
	}
}

function scheduleRebuild(changedPath) {
	latestChangedPath = path.relative(projectDirectory, changedPath);
	clearTimeout(rebuildTimer);
	rebuildTimer = setTimeout(rebuild, 80);
}

async function watchDirectory(directory, selectFilename) {
	try {
		for await (const {filename} of watch(directory, {recursive: true, signal: watchController.signal})) {
			const selected = selectFilename(filename);
			if (selected) {
				scheduleRebuild(path.resolve(directory, selected));
			}
		}
	} catch (error) {
		if (error.name !== 'AbortError') {
			throw error;
		}
	}
}

if (watchMode) {
	await build();
}

const heartbeat = setInterval(() => broadcast('heartbeat', {at: Date.now()}), 15_000);
heartbeat.unref();

server.listen(port, '127.0.0.1', () => {
	console.log(`Static server: http://127.0.0.1:${port}${watchMode ? ' (SSE reload)' : ''}`);
});

function shutdown() {
	clearInterval(heartbeat);
	clearTimeout(rebuildTimer);
	watchController.abort();

	liveReloadClients.forEach(client => client.end());
	server.close();
}

process.once('SIGINT', shutdown);
process.once('SIGTERM', shutdown);

if (watchMode) {
	try {
		await Promise.all([
			watchDirectory(projectDirectory, filename => (shouldRebuild(filename) ? filename : undefined)),
			watchDirectory(todoDirectory, filename => (filename?.endsWith('.md') ? filename : undefined)),
		]);
	} catch (error) {
		console.error('Source watcher failed.', error);
		process.exitCode = 1;
		shutdown();
	}
}
