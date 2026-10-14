import {document, window} from '../js/core/browser.js';

export function readDesignToken(name, element = document.documentElement) {
	return window.getComputedStyle(element).getPropertyValue(name).trim();
}

export function readMotionDuration(name, element) {
	const value = readDesignToken(name, element);
	const match = /^(?<amount>\d+(?:\.\d+)?|\.\d+)(?<unit>m?s)$/v.exec(value);
	if (!match) {
		throw new Error(`Invalid motion token: ${name}`);
	}

	const duration = Number(match.groups.amount);
	return match.groups.unit === 'ms' ? duration : duration * 1000;
}
