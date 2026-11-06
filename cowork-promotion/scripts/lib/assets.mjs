import {createHash} from 'node:crypto';
import {glob, readFile} from 'node:fs/promises';
import path from 'node:path';

const technologyLogoDirectory = new URL('../../src/images/technologies/', import.meta.url);

export async function addTechnologyLogos(assets) {
	const notices = await readFile(new URL('../technology-logo-notices.txt', technologyLogoDirectory));
	assets.files.set('technology-logo-notices.txt', notices);
	const filenames = await Array.fromAsync(glob('*.{svg,png}', {cwd: technologyLogoDirectory}));
	const entries = await Promise.all(filenames.map(async filename => {
		const {name, ext} = path.parse(filename);
		const content = await readFile(new URL(filename, technologyLogoDirectory));
		return [filename, assets.add(`tech-${name}`, ext.slice(1), content)];
	}));
	return new Map(entries);
}

export function createAssetCollection() {
	const files = new Map();

	function add(name, extension, content) {
		const hash = createHash('sha256').update(content).digest('hex').slice(0, 16);
		const assetPath = `assets/${name}.${hash}.${extension}`;
		files.set(assetPath, content);
		return `/${assetPath}`;
	}

	return {add, files};
}
