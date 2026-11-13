import {window} from './browser.js';

const imageSourcePattern = /<img[^>]+src="(?<source>[^"]+)"/gv;
const preloadedImages = [];

export function imageSources(html) {
	return Array.from(html.matchAll(imageSourcePattern), match => match.groups.source.replaceAll('&amp;', '&'));
}

export function preloadImages(sources) {
	for (const source of new Set(sources)) {
		const image = new window.Image();
		image.src = source;
		preloadedImages.push(image);
	}
}

export function preloadSceneImages(states) {
	preloadImages(states.flatMap(state =>
		Object.values(state).filter(value => typeof value === 'string').flatMap(value => imageSources(value))));
}
