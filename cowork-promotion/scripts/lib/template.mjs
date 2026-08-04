import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const includePattern = /^\s*<!--\s*@include\s+([^\s]+)\s*-->\s*$/gm;

function ensureInsideRoot(fileUrl, rootUrl) {
    const filePath = fileURLToPath(fileUrl);
    const rootPath = fileURLToPath(rootUrl).replace(/\/$/, "");

    if (filePath !== rootPath && !filePath.startsWith(`${rootPath}/`)) {
        throw new Error(`Template include escapes its root: ${filePath}`);
    }
}

export async function composeTemplate(fileUrl, rootUrl, stack = []) {
    ensureInsideRoot(fileUrl, rootUrl);
    const filePath = fileURLToPath(fileUrl);
    if (stack.includes(filePath)) {
        throw new Error(`Circular template include: ${[...stack, filePath].join(" -> ")}`);
    }

    const template = await readFile(fileUrl, "utf8");
    const matches = [...template.matchAll(includePattern)];
    if (matches.length === 0) return template;

    let cursor = 0;
    let output = "";
    for (const match of matches) {
        const includeUrl = new URL(match[1], fileUrl);
        output += template.slice(cursor, match.index);
        output += await composeTemplate(includeUrl, rootUrl, [...stack, filePath]);
        cursor = match.index + match[0].length;
    }
    return output + template.slice(cursor);
}

export function replaceGeneratedRegion(html, name, content) {
    const startMarker = `<!-- data:${name}:start -->`;
    const endMarker = `<!-- data:${name}:end -->`;
    const start = html.indexOf(startMarker);
    const end = html.indexOf(endMarker);

    if (start < 0 || end < 0 || end <= start) {
        throw new Error(`Missing or invalid ${name} build markers.`);
    }

    const contentStart = start + startMarker.length;
    return `${html.slice(0, contentStart)}\n${content}\n${html.slice(end)}`;
}
