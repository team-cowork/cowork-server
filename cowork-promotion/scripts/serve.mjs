import { watch } from "node:fs";
import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, join, normalize, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "./build.mjs";

const projectDirectory = resolve(fileURLToPath(new URL("../", import.meta.url)));
const rootDirectory = resolve(process.argv[2] ?? ".");
const watchMode = process.argv.includes("--watch");
const port = Number(process.env.PORT ?? 3000);
const liveReloadClients = new Set();
const contentTypes = {
    ".css": "text/css; charset=utf-8",
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
};
const liveReloadClient = `const events = new EventSource("/__dev/events");
events.addEventListener("reload", () => window.location.reload());
events.addEventListener("build-error", (event) => {
    const detail = JSON.parse(event.data);
    console.error("라이브 리로드 빌드에 실패했습니다.", detail.message);
});`;

function resolveRequestPath(pathname) {
    const relativePath = normalize(pathname).replace(/^[/\\]+/, "");
    const filePath = resolve(join(rootDirectory, relativePath));

    if (filePath !== rootDirectory && !filePath.startsWith(`${rootDirectory}${sep}`)) {
        return null;
    }
    return filePath;
}

function openEventStream(request, response) {
    response.writeHead(200, {
        "Cache-Control": "no-cache, no-transform",
        Connection: "keep-alive",
        "Content-Type": "text/event-stream; charset=utf-8",
        "X-Accel-Buffering": "no",
    });
    response.write("retry: 1000\nevent: connected\ndata: {}\n\n");
    liveReloadClients.add(response);

    request.on("close", () => liveReloadClients.delete(response));
}

function broadcast(event, data) {
    const message = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    liveReloadClients.forEach((client) => {
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
        .replace("<html", `<html data-dev-render-id="${Date.now()}"`)
        .replace("</body>", `  ${script}\n  </body>`);
}

async function serveFile(requestUrl, response) {
    const url = new URL(requestUrl, "http://localhost");

    if (watchMode && url.pathname === "/__dev/events") {
        return { eventStream: true, url };
    }
    if (watchMode && url.pathname === "/__dev/reload.js") {
        response.writeHead(200, {
            "Cache-Control": "no-store",
            "Content-Type": contentTypes[".js"],
        });
        response.end(liveReloadClient);
        return { eventStream: false, url };
    }

    let filePath = resolveRequestPath(decodeURIComponent(url.pathname));
    if (!filePath) {
        response.writeHead(403).end("Forbidden");
        return { eventStream: false, url };
    }

    const fileStats = await stat(filePath);
    if (fileStats.isDirectory()) filePath = join(filePath, "index.html");

    const extension = extname(filePath);
    const file = await readFile(filePath);
    const content =
        watchMode && extension === ".html"
            ? injectLiveReload(file.toString("utf8"))
            : file;
    response.writeHead(200, {
        "Cache-Control": "no-cache",
        "Content-Type": contentTypes[extension] ?? "application/octet-stream",
    });
    response.end(content);
    return { eventStream: false, url };
}

const server = createServer(async (request, response) => {
    try {
        const result = await serveFile(request.url ?? "/", response);
        if (result.eventStream) openEventStream(request, response);
    } catch (error) {
        const status = error?.code === "ENOENT" ? 404 : 500;
        response.writeHead(status).end(status === 404 ? "Not Found" : "Internal Server Error");
    }
});

let sourceWatcher;
let rebuildTimer;
let building = false;
let rebuildQueued = false;
let latestChangedPath = "";

function shouldRebuild(filename) {
    if (!filename) return false;
    const path = normalize(String(filename));
    return path === "logo.svg" || path.startsWith(`src${sep}`) || path.startsWith(`data${sep}`);
}

async function rebuild() {
    if (building) {
        rebuildQueued = true;
        return;
    }

    building = true;
    try {
        const summary = await build();
        console.log(`Reload: ${latestChangedPath}`);
        broadcast("reload", { changed: latestChangedPath, summary });
    } catch (error) {
        console.error("Live reload build failed.", error);
        broadcast("build-error", { message: error.message });
    } finally {
        building = false;
        if (rebuildQueued) {
            rebuildQueued = false;
            await rebuild();
        }
    }
}

function scheduleRebuild(filename) {
    if (!shouldRebuild(filename)) return;
    latestChangedPath = relative(projectDirectory, resolve(projectDirectory, filename));
    clearTimeout(rebuildTimer);
    rebuildTimer = setTimeout(rebuild, 80);
}

if (watchMode) {
    await build();
    sourceWatcher = watch(
        projectDirectory,
        { recursive: true },
        (_eventType, filename) => scheduleRebuild(filename),
    );
}

const heartbeat = setInterval(() => broadcast("heartbeat", { at: Date.now() }), 15_000);
heartbeat.unref();

server.listen(port, "127.0.0.1", () => {
    console.log(`Static server: http://127.0.0.1:${port}${watchMode ? " (SSE reload)" : ""}`);
});

function shutdown() {
    clearInterval(heartbeat);
    clearTimeout(rebuildTimer);
    sourceWatcher?.close();
    liveReloadClients.forEach((client) => client.end());
    server.close();
}

process.once("SIGINT", shutdown);
process.once("SIGTERM", shutdown);
