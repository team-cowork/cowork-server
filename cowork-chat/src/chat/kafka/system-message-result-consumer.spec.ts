import {ServiceUnavailableException} from '@nestjs/common';
import {isMissingChannelAfterReady} from './system-message-result-consumer.js';

describe('isMissingChannelAfterReady', () => {
	const readiness = (isChannelReady: boolean) => ({areReady: jest.fn().mockReturnValue(isChannelReady)});

	it('channel 준비 후 채널 부재(ServiceUnavailable)면 건너뛸 대상이다', () => {
		const error = new ServiceUnavailableException('채널 정보가 아직 동기화되지 않았습니다');
		const projection = readiness(true);

		expect(isMissingChannelAfterReady(error, projection)).toBe(true);
		expect(projection.areReady).toHaveBeenCalledWith(['channel']);
	});

	it('channel이 아직 준비되지 않았으면 재시도하도록 건너뛰지 않는다', () => {
		const error = new ServiceUnavailableException('채널 정보가 아직 동기화되지 않았습니다');

		expect(isMissingChannelAfterReady(error, readiness(false))).toBe(false);
	});

	it('ServiceUnavailable이 아닌 오류는 준비 상태와 무관하게 건너뛰지 않는다', () => {
		expect(isMissingChannelAfterReady(new Error('mongo down'), readiness(true))).toBe(false);
	});
});
