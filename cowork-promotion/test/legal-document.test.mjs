import assert from 'node:assert/strict';
import {
	after,
	before,
	describe,
	it,
} from 'node:test';
import {JSDOM} from 'jsdom';

const originalGlobals = Object.fromEntries(['window', 'document'].map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
let dom;
let renderLegalDocument;

before(async () => {
	dom = new JSDOM('', {url: 'https://promotion.test/'});
	Object.defineProperties(globalThis, {
		window: {value: dom.window, configurable: true},
		document: {value: dom.window.document, configurable: true},
	});
	({renderLegalDocument} = await import('../src/js/data/legal-document.js'));
});

after(() => {
	dom.window.close();
	for (const [name, descriptor] of Object.entries(originalGlobals)) {
		if (descriptor) {
			Object.defineProperty(globalThis, name, descriptor);
		} else {
			Reflect.deleteProperty(globalThis, name);
		}
	}
});

function xml(type, body = '', title = '문서') {
	return `<document type="${type}"><title>${title}</title>${body}</document>`;
}

function section(heading, body = '<paragraph>본문</paragraph>') {
	return `<section><heading>${heading}</heading>${body}</section>`;
}

function render(source, type, headingLevel = 1) {
	const content = dom.window.document.createElement('main');
	content.append(renderLegalDocument(source, type, headingLevel));
	return content;
}

function elementWithId(content, id) {
	return [...content.querySelectorAll('[id]')].find(element => element.id === id);
}

describe('renderLegalDocument', () => {
	describe('when the XML contains a supported document', () => {
		for (const [type, title] of [['privacy-policy', '개인정보처리방침'], ['terms-of-service', '서비스 이용약관'], ['license', 'MIT License']]) {
			it(`renders the ${type} title and body without an empty table of contents`, () => {
				const source = xml(type, '<paragraph>Document body.</paragraph>', title);

				const result = render(source, type);

				assert.equal(result.querySelector(':scope > header > h1').textContent, title);
				assert.equal(result.querySelector('p').textContent, 'Document body.');
				assert.equal(result.querySelector('nav'), null);
			});
		}

		it('preserves inline order, lists and section hierarchy with a navigable table of contents', () => {
			const source = xml('terms-of-service', `
				<paragraph>앞 <strong>중요</strong> 뒤</paragraph>
				<list ordered="false"><item>소개</item></list>
				${section('제1조. 목적과 용어', `<list ordered="true"><item>첫째</item><item>둘째</item></list>${section('세부 조건')}`)}
				${section('제2조. 가입과 계정 관리')}
			`);

			const result = render(source, 'terms-of-service');

			assert.equal(result.querySelector(':scope > header > p').textContent, '앞 중요 뒤');
			assert.equal(result.querySelector(':scope > header > p > strong').textContent, '중요');
			assert.equal(result.querySelector(':scope > header > ul.legal-details > li').textContent, '소개');
			assert.deepEqual([...result.querySelectorAll(':scope > #purpose > ol > li')].map(item => item.textContent), ['첫째', '둘째']);
			assert.equal(result.querySelector(':scope > #purpose > h2').textContent, '제1조. 목적과 용어');
			assert.equal(result.querySelector(':scope > #purpose > section > h3').textContent, '세부 조건');
			assert.equal(result.querySelector('nav').getAttribute('aria-label'), '문서 목차');
			const links = [...result.querySelectorAll(':scope > nav a')];
			assert.deepEqual(links.map(link => link.textContent), ['목적과 용어', '가입과 계정 관리']);
			const targets = links.map(link => elementWithId(result, decodeURIComponent(link.hash.slice(1))));
			assert.deepEqual(targets.map(target => target.firstElementChild.textContent), ['제1조. 목적과 용어', '제2조. 가입과 계정 관리']);
			assert.deepEqual(targets.map(target => elementWithId(result, target.getAttribute('aria-labelledby'))), targets.map(target => target.firstElementChild));
		});

		it('starts English license content below the page heading when heading level is two', () => {
			const source = xml('license', section('Permissions', section('Conditions')) + section('Warranty'), 'MIT License');

			const result = render(source, 'license', 2);

			assert.equal(result.querySelector('h1'), null);
			assert.equal(result.querySelector(':scope > header > h2').textContent, 'MIT License');
			assert.equal(result.querySelector(':scope > nav > h3').textContent, '목차');
			assert.equal(result.querySelector(':scope > .legal-section > h3').textContent, 'Permissions');
			assert.equal(result.querySelector(':scope > .legal-section > section > h4').textContent, 'Conditions');
		});

		it('keeps markup-like text inert and copies only supported links and formatting', () => {
			const source = xml('privacy-policy', `<paragraph onclick="alert(1)">
				<![CDATA[<img src="x" onerror="alert(1)">]]>&lt;script&gt; &amp;
				<strong style="color:red" onclick="alert(1)">강조</strong>
				<link href="https://example.test/policy?q=one&amp;x=two" onclick="alert(1)">외부</link>
				<link href="mailto:help@example.test">메일</link><link href="/terms/">약관</link>
			</paragraph>`);

			const result = render(source, 'privacy-policy');

			assert.match(result.querySelector('p').textContent, /<img src="x" onerror="alert\(1\)"><script> &/v);
			assert.equal(result.querySelector('img, script, [onclick], [style]'), null);
			assert.equal(result.querySelector('strong').textContent, '강조');
			assert.deepEqual([...result.querySelectorAll('a')].map(link => link.href), [
				'https://example.test/policy?q=one&x=two', 'mailto:help@example.test', 'https://promotion.test/terms/',
			]);
		});
	});

	describe('when the XML or its content violates the document contract', () => {
		const invalidDocuments = [
			['a different document type', xml('license')],
			['malformed XML', '<document type="privacy-policy"><title>문서</document>'],
			['a DTD with an entity', '<!DOCTYPE document [<!ENTITY title "문서">]><document type="privacy-policy"><title>&title;</title></document>'],
			['a namespaced root', '<document xmlns="urn:other" type="privacy-policy"><title>문서</title></document>'],
			['a missing title', '<document type="privacy-policy"><paragraph>본문</paragraph></document>'],
			['an empty title', xml('privacy-policy', '', ' ')],
			['text outside a block', xml('privacy-policy', '본문')],
			['an unsupported block', xml('privacy-policy', '<script>alert(1)</script>')],
			['an unsupported inline element', xml('privacy-policy', '<paragraph><img src="x"/></paragraph>')],
			['a namespaced inline element', xml('privacy-policy', '<paragraph><strong xmlns="urn:other">본문</strong></paragraph>')],
			['a section without its heading', xml('privacy-policy', '<section><paragraph>본문</paragraph></section>')],
			['an invalid list kind', xml('privacy-policy', '<list ordered="yes"><item>항목</item></list>')],
			['a non-item list child', xml('privacy-policy', '<list ordered="false"><paragraph>항목</paragraph></list>')],
			['a block after the sections', xml('privacy-policy', `${section('항목')}<paragraph>본문</paragraph>`)],
		];
		for (const [reason, source] of invalidDocuments) {
			it(`rejects ${reason}`, () => {
				assert.throws(() => renderLegalDocument(source, 'privacy-policy'), /legal document/iv);
			});
		}

		for (const href of ['', ['javascript', 'alert(1)'].join(':'), 'data:text/html,test', new URL('http:external.test/').href, '//external.test/', String.raw`/\external.test/`, 'https://user:password@example.test/']) {
			it(`rejects the unsafe link ${JSON.stringify(href)}`, () => {
				const source = xml('privacy-policy', `<paragraph><link href="${href}">링크</link></paragraph>`);

				assert.throws(() => renderLegalDocument(source, 'privacy-policy'), /Unsupported legal document link/v);
			});
		}
	});

	describe('when headings determine published fragment links', () => {
		for (const [type, heading, id] of [['privacy-policy', '처리 목적과 개인정보 항목', 'collection'], ['terms-of-service', '목적과 용어', 'purpose']]) {
			it(`preserves ${type} legacy links after sections are inserted and renumbered`, () => {
				const original = render(xml(type, section(`1. ${heading}`)), type);
				const reordered = render(xml(type, section('새 항목') + section(`제2조. ${heading}`)), type);

				assert.equal(elementWithId(original, id).firstElementChild.textContent, `1. ${heading}`);
				assert.equal(elementWithId(reordered, id).firstElementChild.textContent, `제2조. ${heading}`);
				assert.equal(reordered.querySelectorAll(':scope > nav a')[1].hash, `#${id}`);
			});
		}

		it('keeps new heading anchors stable and URL-encodes their table of contents links', () => {
			const heading = '새 항목 # %';
			const first = render(xml('privacy-policy', section(heading)), 'privacy-policy');
			const reordered = render(xml('privacy-policy', section('먼저 삽입') + section(heading)), 'privacy-policy');
			const {id} = first.querySelector('.legal-section');

			assert.equal(elementWithId(reordered, id).firstElementChild.textContent, heading);
			assert.equal(reordered.querySelectorAll(':scope > nav a')[1].getAttribute('href'), `#${encodeURIComponent(id)}`);
		});

		it('assigns unique section and heading IDs when headings repeat or collide with title IDs', () => {
			const source = xml('privacy-policy', ['항목', '항목', '항목-title'].map(heading => section(heading)).join(''));

			const result = render(source, 'privacy-policy');

			const ids = [...result.querySelectorAll('[id]')].map(element => element.id);
			assert.equal(ids.length, 6);
			assert.equal(new Set(ids).size, ids.length);
			const links = [...result.querySelectorAll(':scope > nav a')];
			const targets = links.map(link => elementWithId(result, decodeURIComponent(link.hash.slice(1))));
			assert.deepEqual(targets.map(target => target.firstElementChild.textContent), ['항목', '항목', '항목-title']);
			assert.deepEqual(targets.map(target => elementWithId(result, target.getAttribute('aria-labelledby'))), targets.map(target => target.firstElementChild));
		});
	});
});
