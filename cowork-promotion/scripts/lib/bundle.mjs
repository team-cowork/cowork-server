import {compileFunction} from 'node:vm';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {runInSequence} from './sequence.mjs';

const importPattern = /^import\s[\s\S]*?\sfrom\s+["'](?<specifier>[^"']+)["'];\s*$/gmv;
const exportPattern = /^export\s+(?=(?:async\s+)?(?:function|class|const|let|var)\b)/gmv;

function ensureInsideRoot(fileUrl, rootUrl) {
	const filePath = fileURLToPath(fileUrl);
	const rootPath = fileURLToPath(rootUrl).replace(/\/$/v, '');

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
		if (visited.has(filePath)) {
			return;
		}

		if (visiting.includes(filePath)) {
			throw new Error(
				`Circular JavaScript import: ${[...visiting, filePath].join(' -> ')}`,
			);
		}

		visiting.push(filePath);
		const source = await readFile(fileUrl, 'utf8');
		const imports = source.matchAll(importPattern).toArray();

		const specifiers = imports.map(match => match.groups.specifier);
		const unsupported = specifiers.find(specifier => !specifier.startsWith('.'));
		if (unsupported !== undefined) {
			throw new Error(`Only relative browser imports can be bundled: ${unsupported}`);
		}

		await runInSequence(specifiers, async specifier => {
			await visit(new URL(specifier, fileUrl));
		});

		const body = source
			.replaceAll(importPattern, '')
			.replaceAll(exportPattern, '')
			.trim();
		if (/^[\t ]*(?:import|export)\b/mv.test(body)) {
			throw new Error(`Unsupported ESM syntax in ${filePath}`);
		}

		modules.push({body, label: moduleLabel(fileUrl, rootUrl)});
		visiting.pop();
		visited.add(filePath);
	}

	await visit(entryUrl);
	const moduleSource = modules
		.map(({body, label}) => `    // ${label}\n${body}`)
		.join('\n\n');
	const bundle = `(async () => {\n    "use strict";\n\n${moduleSource}\n})();\n`;

	// Compile the generated bundle to validate syntax without executing it.
	compileFunction(bundle);

	return bundle.replaceAll(/<\/script/giv, String.raw`<\/script`);
}

export function inlineJson(value) {
	return JSON.stringify(value)
		.replaceAll('<', String.raw`\u003c`)
		.replaceAll('\u{2028}', String.raw`\u2028`)
		.replaceAll('\u{2029}', String.raw`\u2029`);
}

export function replaceBundleMarker(html, name, content) {
	const marker = `<!-- bundle:${name} -->`;
	if (!html.includes(marker)) {
		throw new Error(`Missing ${name} bundle marker.`);
	}

	return html.replace(marker, () => content);
}
