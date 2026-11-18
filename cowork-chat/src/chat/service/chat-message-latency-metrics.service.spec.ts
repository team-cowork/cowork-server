import {elapsedSeconds} from './chat-message-latency-metrics.service.js';

describe('elapsedSeconds', () => {
	it('두 시각 사이의 밀리초를 초로 바꾼다', () => {
		expect(elapsedSeconds(1000, 3500)).toBe(2.5);
	});

	it('시계 차이로 끝 시각이 앞서면 0으로 본다', () => {
		expect(elapsedSeconds(2000, 1000)).toBe(0);
	});

	it('잘못된 occurredAt처럼 숫자가 아니면 0으로 본다', () => {
		expect(elapsedSeconds(NaN, 1000)).toBe(0);
	});
});
