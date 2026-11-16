import {document, window} from '../core/browser.js';

function blockChildren(source) {
	return [...source.childNodes].filter(node => {
		if ((node.nodeType === window.Node.COMMENT_NODE) || (node.nodeType === window.Node.TEXT_NODE && node.textContent.trim() === '')) {
			return false;
		}

		if (node.nodeType !== window.Node.ELEMENT_NODE || node.namespaceURI) {
			throw new Error('Invalid legal document block.');
		}

		return true;
	});
}

function inlineNodes(source) {
	return [...source.childNodes].map(node => {
		if ([window.Node.TEXT_NODE, window.Node.CDATA_SECTION_NODE].includes(node.nodeType)) {
			return document.createTextNode(node.textContent);
		}

		if (node.namespaceURI || !['strong', 'link'].includes(node.nodeName)) {
			throw new Error('Unsupported legal document inline element.');
		}

		const element = document.createElement(node.nodeName === 'link' ? 'a' : 'strong');
		if (node.nodeName === 'link') {
			const href = node.getAttribute('href') ?? '';
			const url = new URL(href, window.location.origin);
			const local = href.startsWith('/') && url.origin === window.location.origin;
			if ((!local && !/^(?:https:\/\/|mailto:)/v.test(href)) || url.username || url.password) {
				throw new Error('Unsupported legal document link.');
			}

			element.href = url.href;
		}

		element.append(...inlineNodes(node));
		return element;
	});
}

function headingElement(source, tag) {
	if (!source?.textContent.trim()) {
		throw new Error('Missing legal document heading.');
	}

	const element = document.createElement(tag);
	element.append(...inlineNodes(source));
	return element;
}

function renderBlock(source, level = 2) {
	switch (source.nodeName) {
		case 'paragraph': {
			const paragraph = document.createElement('p');
			paragraph.append(...inlineNodes(source));
			return paragraph;
		}

		case 'list': {
			const ordered = source.getAttribute('ordered');
			if (!['true', 'false'].includes(ordered)) {
				throw new Error('Invalid legal document list.');
			}

			const list = document.createElement(ordered === 'true' ? 'ol' : 'ul');
			for (const item of blockChildren(source)) {
				if (item.nodeName !== 'item') {
					throw new Error('Invalid legal document list item.');
				}

				const entry = document.createElement('li');
				entry.append(...inlineNodes(item));
				list.append(entry);
			}

			return list;
		}

		case 'section': {
			const [heading, ...blocks] = blockChildren(source);
			if (heading?.nodeName !== 'heading') {
				throw new Error('Missing legal document section heading.');
			}

			const section = document.createElement('section');
			section.append(headingElement(heading, `h${Math.min(level, 6)}`));
			section.append(...blocks.map(block => renderBlock(block, level + 1)));
			return section;
		}

		default: {
			throw new Error('Unsupported legal document block.');
		}
	}
}

export function renderLegalDocument(xml, expectedType) {
	// Gateway's schema has no DTD; reject it before the XML parser can expand entities.
	if (xml.includes('<!DOCTYPE')) {
		throw new Error('Unsupported legal document declaration.');
	}

	const parsed = new window.DOMParser().parseFromString(xml, 'application/xml');
	const root = parsed.documentElement;
	if (parsed.querySelector('parsererror') || root.nodeName !== 'document'
		|| root.namespaceURI || root.getAttribute('type') !== expectedType) {
		throw new Error('Invalid legal document XML.');
	}

	const [title, ...blocks] = blockChildren(root);
	if (title?.nodeName !== 'title') {
		throw new Error('Missing legal document title.');
	}

	const header = document.createElement('header');
	header.append(headingElement(title, 'h1'));
	const sections = [];
	for (const block of blocks) {
		const element = renderBlock(block);
		if (block.nodeName === 'section') {
			const id = `section-${sections.length + 1}`;
			element.id = id;
			element.className = 'legal-section';
			element.firstElementChild.id = `${id}-title`;
			element.setAttribute('aria-labelledby', `${id}-title`);
			sections.push(element);
		} else {
			if (sections.length > 0) {
				throw new Error('Legal document block outside a section.');
			}

			if (element.tagName === 'UL') {
				element.className = 'legal-details';
			}

			header.append(element);
		}
	}

	const fragment = document.createDocumentFragment();
	fragment.append(header);
	if (sections.length > 1) {
		const toc = document.createElement('nav');
		toc.className = 'legal-toc';
		toc.setAttribute('aria-label', `${title.textContent} 목차`);
		const heading = document.createElement('h2');
		heading.textContent = '목차';
		const list = document.createElement('ol');
		for (const section of sections) {
			const entry = document.createElement('li');
			const link = document.createElement('a');
			link.href = `#${section.id}`;
			link.textContent = section.firstElementChild.textContent.replace(/^제?\d+조?\.\s*/v, '');
			entry.append(link);
			list.append(entry);
		}

		toc.append(heading, list);
		fragment.append(toc);
	}

	fragment.append(...sections);
	return fragment;
}
