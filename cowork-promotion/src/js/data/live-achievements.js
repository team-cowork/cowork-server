import {document} from '../core/browser.js';

export async function refreshAchievements() {
	try {
		const response = await fetch('/api/achievements', {signal: AbortSignal.timeout(135_000)});
		if (!response.ok) {
			return;
		}

		const {regions} = await response.json();
		const selectors = {
			'achievement-summary': '.achievement-code-summary',
			'achievement-pr-summary': '.achievement-pr-summary',
			'achievement-people-summary': '.achievement-people-summary',
			'achievements-contributors': '.achievement-people',
			'achievement-pr-activity': '.achievement-pr-activity',
			'achievement-commit-summary': '.achievement-commit-summary',
		};
		const updates = Object.entries(selectors).map(([name, selector]) => {
			if (typeof regions?.[name] !== 'string') {
				throw new TypeError('Incomplete achievement response.');
			}

			const element = document.querySelector(selector);
			if (!element) {
				throw new Error('Missing achievement region.');
			}

			return {element, html: regions[name]};
		});
		for (const {element, html} of updates) {
			element.innerHTML = html;
		}
	} catch {
		// Keep the server-rendered snapshot when GitHub or the network is unavailable.
	}
}
