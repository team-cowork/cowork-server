import {
	BadRequestException,
	ConflictException,
	ForbiddenException,
	HttpException,
	HttpStatus,
	NotFoundException,
	ServiceUnavailableException,
} from '@nestjs/common';
import {
	DISPATCH_TIMEOUT_MS,
	SCHEDULE_MAX_LEAD_MS,
	SCHEDULE_MIN_LEAD_MS,
	decideDispatchOutcome,
	isDispatchExpired,
	requireScheduleCapacity,
	requireScheduleTime,
	requireScheduledContent,
	type DispatchStage,
} from './scheduled-message.policy.js';

const NOW = new Date('2026-11-23T00:00:00.000Z');
const after = (ms: number) => new Date(NOW.getTime() + ms);

describe('scheduled message policy', () => {
	describe('requireScheduleTime', () => {
		it.each([
			['1분 뒤', SCHEDULE_MIN_LEAD_MS],
			['120일 뒤', SCHEDULE_MAX_LEAD_MS],
		])('%s는 허용한다', (_label, lead) => {
			expect(() => {
				requireScheduleTime(after(lead), NOW);
			}).not.toThrow();
		});

		it.each([
			['1분보다 이른 시각', SCHEDULE_MIN_LEAD_MS - 1],
			['지난 시각', -1000],
			['120일보다 늦은 시각', SCHEDULE_MAX_LEAD_MS + 1],
		])('%s는 거부한다', (_label, lead) => {
			expect(() => {
				requireScheduleTime(after(lead), NOW);
			}).toThrow(BadRequestException);
		});

		it('날짜가 아닌 값은 거부한다', () => {
			expect(() => {
				requireScheduleTime(new Date('invalid'), NOW);
			}).toThrow(BadRequestException);
		});
	});

	describe('requireScheduleCapacity', () => {
		it('대기 중인 예약이 99건이면 허용하고 100건이면 거부한다', () => {
			expect(() => {
				requireScheduleCapacity(99);
			}).not.toThrow();
			expect(() => {
				requireScheduleCapacity(100);
			}).toThrow(ConflictException);
		});
	});

	describe('requireScheduledContent', () => {
		it('첨부가 없으면 공백 본문을 거부하고, 첨부가 있으면 허용한다', () => {
			expect(() => {
				requireScheduledContent('  ', 0);
			}).toThrow(BadRequestException);
			expect(() => {
				requireScheduledContent('', 1);
			}).not.toThrow();
		});
	});

	describe('isDispatchExpired', () => {
		it('발송 시각에서 24시간이 지나면 만료로 본다', () => {
			expect(isDispatchExpired(NOW, after(DISPATCH_TIMEOUT_MS - 1))).toBe(false);
			expect(isDispatchExpired(NOW, after(DISPATCH_TIMEOUT_MS))).toBe(true);
		});
	});

	describe('decideDispatchOutcome', () => {
		const message = {scheduledAt: NOW, attempts: 0};

		it.each<[DispatchStage, Error, string]>([
			['MESSAGE_SEND', new ForbiddenException(), 'MESSAGE_SEND_DENIED'],
			['FILE_ATTACH', new ForbiddenException(), 'FILE_ATTACH_DENIED'],
			['TARGET', new ForbiddenException(), 'DM_BLOCKED'],
			['TARGET', new NotFoundException(), 'CHANNEL_NOT_FOUND'],
			['TARGET', new ConflictException(), 'CHANNEL_ARCHIVED'],
			['PUBLISH', new BadRequestException(), 'TOO_MANY_MENTIONS'],
		])('%s 단계의 %p는 실패 사유 %s로 바꾼다', (stage, error, reason) => {
			expect(decideDispatchOutcome(stage, error, message, after(1000))).toEqual({kind: 'FAIL', reason});
		});

		it.each<[DispatchStage, Error]>([
			['MESSAGE_SEND', new ServiceUnavailableException()],
			['TARGET', new ServiceUnavailableException()],
			['PUBLISH', new HttpException('slowmode', HttpStatus.TOO_MANY_REQUESTS)],
			['PUBLISH', new Error('kafka unavailable')],
		])('%s 단계의 일시 오류 %p는 다시 시도한다', (stage, error) => {
			expect(decideDispatchOutcome(stage, error, message, after(1000))).toEqual({kind: 'RETRY', nextAttemptAt: after(1000 + 5000)});
		});

		it('재시도 간격은 늘어나되 5분을 넘지 않는다', () => {
			const outcome = decideDispatchOutcome('PUBLISH', new Error('down'), {scheduledAt: NOW, attempts: 20}, NOW);

			expect(outcome).toEqual({kind: 'RETRY', nextAttemptAt: after(5 * 60 * 1000)});
		});

		it('발송 시각에서 24시간이 지나면 일시 오류도 실패로 바꾼다', () => {
			expect(decideDispatchOutcome('PUBLISH', new Error('down'), message, after(DISPATCH_TIMEOUT_MS - 1)).kind).toBe('RETRY');
			expect(decideDispatchOutcome('PUBLISH', new Error('down'), message, after(DISPATCH_TIMEOUT_MS)))
				.toEqual({kind: 'FAIL', reason: 'DISPATCH_TIMEOUT'});
		});
	});
});
