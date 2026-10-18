import {window} from './browser.js';

const imageSourcePattern = /<img[^>]+src="(?<source>[^"]+)"/gv;
const preloadedImages = [];

function imageSources(html) {
	return html.matchAll(imageSourcePattern).map(match => match.groups.source.replaceAll('&amp;', '&')).toArray();
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
