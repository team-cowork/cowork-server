import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

const includePattern = /^[\t ]*<!--[\t ]*@include[\t ]+(?<path>\S+?)[\t ]*-->[\t ]*$/gmv;

function ensureInsideRoot(fileUrl, rootUrl) {
	const filePath = fileURLToPath(fileUrl);
	const rootPath = fileURLToPath(rootUrl).replace(/\/$/v, '');

	if (filePath !== rootPath && !filePath.startsWith(`${rootPath}/`)) {
		throw new Error(`Template include escapes its root: ${filePath}`);
	}
}

export async function composeTemplate(fileUrl, rootUrl, stack = []) {
	ensureInsideRoot(fileUrl, rootUrl);
	const filePath = fileURLToPath(fileUrl);
	if (stack.includes(filePath)) {
		throw new Error(`Circular template include: ${[...stack, filePath].join(' -> ')}`);
	}

	const template = await readFile(fileUrl, 'utf8');
	const matches = template.matchAll(includePattern).toArray();
	if (matches.length === 0) {
		return template;
	}

	const includes = await Promise.all(matches.map(async match => {
		const includeUrl = new URL(match.groups.path, fileUrl);
		return composeTemplate(includeUrl, rootUrl, [...stack, filePath]);
	}));

	let cursor = 0;
	let output = '';
	for (const [index, match] of matches.entries()) {
		output += template.slice(cursor, match.index) + includes[index];
		cursor = match.index + match[0].length;
	}

	return output + template.slice(cursor);
}

export function replaceGeneratedRegion(html, name, content) {
	const startMarker = `<!-- data:${name}:start -->`;
	const endMarker = `<!-- data:${name}:end -->`;
	const start = html.indexOf(startMarker);
	const end = html.indexOf(endMarker);

	if (start === -1 || end === -1 || end <= start) {
		throw new Error(`Missing or invalid ${name} build markers.`);
	}

	const contentStart = start + startMarker.length;
	return `${html.slice(0, contentStart)}\n${content}\n${html.slice(end)}`;
}
