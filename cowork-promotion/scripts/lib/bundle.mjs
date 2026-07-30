import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const importPattern = /^import\s+[\s\S]*?\s+from\s+["']([^"']+)["'];\s*$/gm;
const exportPattern = /^export\s+(?=(?:async\s+)?(?:function|class|const|let|var)\b)/gm;

function ensureInsideRoot(fileUrl, rootUrl) {
    const filePath = fileURLToPath(fileUrl);
    const rootPath = fileURLToPath(rootUrl).replace(/\/$/, "");

    if (filePath !== rootPath && !filePath.startsWith(`${rootPath}/`)) {
        throw new Error(`JavaScript import escapes its root: ${filePath}`);
    }
}

function moduleLabel(fileUrl, rootUrl) {
    return fileURLToPath(fileUrl).slice(fileURLToPath(rootUrl).length);
}

export async function bundleJavaScript(entryUrl, rootUrl) {
    const modules = [];
    const visited = new Set();
    const visiting = [];

    async function visit(fileUrl) {
        ensureInsideRoot(fileUrl, rootUrl);
        const filePath = fileURLToPath(fileUrl);
        if (visited.has(filePath)) return;
        if (visiting.includes(filePath)) {
            throw new Error(
                `Circular JavaScript import: ${[...visiting, filePath].join(" -> ")}`,
            );
        }

        visiting.push(filePath);
        const source = await readFile(fileUrl, "utf8");
        const imports = [...source.matchAll(importPattern)];

        for (const match of imports) {
            const specifier = match[1];
            if (!specifier.startsWith(".")) {
                throw new Error(`Only relative browser imports can be bundled: ${specifier}`);
            }
            await visit(new URL(specifier, fileUrl));
        }

        const body = source
            .replace(importPattern, "")
            .replace(exportPattern, "")
            .trim();
        if (/^\s*(?:import|export)\b/m.test(body)) {
            throw new Error(`Unsupported ESM syntax in ${filePath}`);
        }

        modules.push({ body, label: moduleLabel(fileUrl, rootUrl) });
        visiting.pop();
        visited.add(filePath);
    }

    await visit(entryUrl);
    const moduleSource = modules
        .map(({ body, label }) => `    // ${label}\n${body}`)
        .join("\n\n");
    const bundle = `(() => {\n    "use strict";\n\n${moduleSource}\n})();\n`;

    new Function(bundle);
    return bundle.replace(/<\/script/gi, "<\\/script");
}

export function inlineJson(value) {
    return JSON.stringify(value)
        .replace(/</g, "\\u003c")
        .replace(/\u2028/g, "\\u2028")
        .replace(/\u2029/g, "\\u2029");
}

export function replaceBundleMarker(html, name, content) {
    const marker = `<!-- bundle:${name} -->`;
    if (!html.includes(marker)) {
        throw new Error(`Missing ${name} bundle marker.`);
    }
    return html.replace(marker, content);
}
