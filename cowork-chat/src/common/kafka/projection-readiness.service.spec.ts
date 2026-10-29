import {isSuspiciousDocumentCountDrop} from './projection-readiness.service.js';

describe('isSuspiciousDocumentCountDrop', () => {
	it('정상 범위 안의 감소나 증가는 의심하지 않는다', () => {
		expect(isSuspiciousDocumentCountDrop(1000, 995)).toBe(false);
		expect(isSuspiciousDocumentCountDrop(1000, 1200)).toBe(false);
		expect(isSuspiciousDocumentCountDrop(0, 0)).toBe(false);
	});

	it('절대값과 비율을 모두 넘는 감소만 의심한다', () => {
		expect(isSuspiciousDocumentCountDrop(1000, 750)).toBe(true);
		// 절대값은 넘지만 비율이 작은 대용량 컬렉션은 의심하지 않는다.
		expect(isSuspiciousDocumentCountDrop(1_000_000, 999_950)).toBe(false);
		// 비율은 크지만 절대값이 작은 소규모 컬렉션은 의심하지 않는다.
		expect(isSuspiciousDocumentCountDrop(10, 5)).toBe(false);
	});

	it('커스텀 임계값을 그대로 따른다', () => {
		expect(isSuspiciousDocumentCountDrop(10, 5, {minAbsoluteDrop: 3, minRatio: 0.3})).toBe(true);
		expect(isSuspiciousDocumentCountDrop(10, 9, {minAbsoluteDrop: 3, minRatio: 0.3})).toBe(false);
	});
});
