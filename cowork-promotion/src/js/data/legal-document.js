import {document, window} from '../core/browser.js';

// Preserve published fragment URLs independently of section ordering.
const legacyAnchors = new Map([
	['privacy-policy', new Map([
		['처리 목적과 개인정보 항목', 'collection'],
		['보유기간과 파기', 'retention'],
		['정보 공유와 외부 서비스', 'sharing'],
		['이용자의 권리', 'rights'],
		['개인정보 보호 조치', 'security'],
		['브라우저 저장소와 외부 리소스', 'storage'],
		['문의 및 권리 구제', 'contact'],
		['방침의 변경과 시행', 'changes'],
	])],
	['terms-of-service', new Map([
		['목적과 용어', 'purpose'],
		['가입과 계정 관리', 'account'],
		['서비스의 제공과 변경', 'service'],
		['이용자의 준수사항', 'conduct'],
		['콘텐츠와 권리', 'content'],
		['개인정보와 외부 서비스', 'privacy'],
		['이용 제한과 이의 제기', 'restriction'],
		['이용 종료와 데이터 처리', 'termination'],
		['책임과 분쟁 해결', 'liability'],
		['약관의 변경과 시행', 'changes'],
	])],
]);

function sectionLabel(heading) {
	return heading.textContent.replace(/^제?\d+조?\.\s*/v, '').trim();
}

function sectionId(type, heading, usedIds) {
	const label = sectionLabel(heading);
	const base = legacyAnchors.get(type)?.get(label) ?? `section-${label.replaceAll(/\s+/gv, '-') || 'untitled'}`;
	let id = base;
	let suffix = 1;
	while (usedIds.has(id) || usedIds.has(`${id}-title`)) {
		suffix += 1;
		id = `${base}-${suffix}`;
	}

	usedIds.add(id);
	usedIds.add(`${id}-title`);
	return id;
}

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
	const usedIds = new Set();
	for (const block of blocks) {
		const element = renderBlock(block);
		if (block.nodeName === 'section') {
			const id = sectionId(expectedType, element.firstElementChild, usedIds);
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
			link.href = `#${encodeURIComponent(section.id)}`;
			link.textContent = sectionLabel(section.firstElementChild);
			entry.append(link);
			list.append(entry);
		}

		toc.append(heading, list);
		fragment.append(toc);
	}

	fragment.append(...sections);
	return fragment;
}
