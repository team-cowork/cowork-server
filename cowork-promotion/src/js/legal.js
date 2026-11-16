import {document, window} from './core/browser.js';
import {renderLegalDocument} from './data/legal-document.js';

const main = document.querySelector('[data-legal-type]');
const content = main.querySelector('[data-legal-content]');
const status = main.querySelector('[data-legal-status]');
const retry = main.querySelector('[data-legal-retry]');

async function loadLegalDocument() {
	const wasRetry = document.activeElement === retry;
	content.setAttribute('aria-busy', 'true');
	retry.hidden = true;
	status.textContent = '문서를 불러오고 있습니다.';
	try {
		const type = main.dataset.legalType;
		const response = await fetch(`/api/legal/${type}`, {
			headers: {accept: 'application/xml'},
			cache: 'no-store',
			signal: AbortSignal.timeout(15_000),
		});
		if (!response.ok) {
			throw new Error('Legal document request failed.');
		}

		content.replaceChildren(renderLegalDocument(await response.text(), type));
		content.lang = main.dataset.legalLanguage;
		status.textContent = '';
		if (wasRetry) {
			main.focus({preventScroll: true});
		}

		// The fragment target did not exist when the browser first opened this URL.
		if (window.location.hash) {
			content.querySelector(`#${window.CSS.escape(window.location.hash.slice(1))}`)?.scrollIntoView();
		}
	} catch {
		status.textContent = '문서를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.';
		retry.hidden = false;
		if (wasRetry) {
			retry.focus();
		}
	} finally {
		content.setAttribute('aria-busy', 'false');
	}
}

retry.addEventListener('click', loadLegalDocument);
await loadLegalDocument();
