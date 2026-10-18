import {window} from './browser.js';

const imageSourcePattern = /<img[^>]+src="(?<source>[^"]+)"/gv;
const preloadedImages = [];

function imageSources(html) {
	return Array.from(html.matchAll(imageSourcePattern), match => match.groups.source.replaceAll('&amp;', '&'));
}

export function preloadSceneImages(states) {
	const sources = new Set(states.flatMap(state =>
		Object.values(state).filter(value => typeof value === 'string').flatMap(value => imageSources(value))));

	for (const source of sources) {
		const image = new window.Image();
		image.src = source;
		preloadedImages.push(image);
	}
}
