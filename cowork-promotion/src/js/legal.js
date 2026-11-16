import {document, window} from './core/browser.js';
import {renderLegalDocument} from './data/legal-document.js';

const main = document.querySelector('[data-legal-type]');
const content = main.querySelector('[data-legal-content]');
const status = main.querySelector('[data-legal-status]');
const retry = main.querySelector('[data-legal-retry]');
let isLoading = false;

function scrollToFragment() {
	if (!window.location.hash) {
		return;
	}

	let id;
	try {
		id = decodeURIComponent(window.location.hash.slice(1));
	} catch {
		// A malformed URL fragment must not turn a loaded document into an error.
		return;
	}

	content.querySelector(`#${window.CSS.escape(id)}`)?.scrollIntoView();
}

async function loadLegalDocument(isRetry = false) {
	if (isLoading) {
		return;
	}

	isLoading = true;
	content.setAttribute('aria-busy', 'true');
	retry.setAttribute('aria-disabled', 'true');
	if (isRetry) {
		retry.focus();
	}

	status.textContent = '문서를 불러오고 있습니다.';
	try {
		const type = main.dataset.legalType;
		const response = await fetch(`/api/legal/${type}`, {
			headers: {accept: 'application/xml'},
			signal: AbortSignal.timeout(15_000),
		});
		if (!response.ok) {
			throw new Error('Legal document request failed.');
		}

		content.replaceChildren(renderLegalDocument(await response.text(), type, type === 'license' ? 2 : 1));
		content.lang = main.dataset.legalLanguage;
		status.textContent = '';
		if (isRetry && document.activeElement === retry) {
			main.focus({preventScroll: true});
		}

		retry.hidden = true;
		// The fragment target did not exist when the browser first opened this URL.
		scrollToFragment();
	} catch {
		status.textContent = '문서를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.';
		retry.hidden = false;
	} finally {
		isLoading = false;
		retry.removeAttribute('aria-disabled');
		content.setAttribute('aria-busy', 'false');
	}
}

retry.addEventListener('click', () => loadLegalDocument(true));
await loadLegalDocument();
