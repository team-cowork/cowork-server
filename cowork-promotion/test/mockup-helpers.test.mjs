import assert from 'node:assert/strict';
import {describe, it} from 'node:test';
import {nextTime} from '../src/js/components/mockup-interactions.js';
import {imageSources} from '../src/js/core/preload.js';

describe('nextTime', () => {
	it('advances the meeting timer by one second and carries into minutes', () => {
		assert.deepEqual(['12:04', '12:59', '00:00', '09:59'].map(value => nextTime(value)),
			['12:05', '13:00', '00:01', '10:00']);
	});

	it('wraps back to 00:00 after 99:59', () => {
		assert.equal(nextTime('99:58'), '99:59');
		assert.equal(nextTime('99:59'), '00:00');
	});
});

describe('imageSources', () => {
	it('collects every img src in document order', () => {
		const html = '<img class="avatar" src="https://a.test/1.png"><p>text</p><img src="https://b.test/2.png" alt="">';
		assert.deepEqual(imageSources(html), ['https://a.test/1.png', 'https://b.test/2.png']);
	});

	it('decodes escaped ampersands so preload requests match the rendered URL', () => {
		assert.deepEqual(imageSources('<img src="https://c.test/3.png?a=1&amp;b=2">'), ['https://c.test/3.png?a=1&b=2']);
	});

	it('ignores markup without images', () => {
		assert.deepEqual(imageSources('<div><span>no images</span></div>'), []);
	});
});
