import {
	BadRequestException,
	ConflictException,
	ForbiddenException,
	HttpException,
	NotFoundException,
} from '@nestjs/common';
import {type ScheduledMessageFailureReason} from './scheduled-message.schema.js';

export const SCHEDULE_MIN_LEAD_MS = 60 * 1000;
export const SCHEDULE_MAX_LEAD_MS = 120 * 24 * 60 * 60 * 1000;
export const MAX_WAITING_SCHEDULED_MESSAGES = 100;
/**
발송 시각에서 이 시간이 지나도록 일시 오류로 보내지 못하면 실패로 바꾼다. 장애가 끝난 뒤 오래된 예약이 갑자기 나가지 않게 한다.
*/
export const DISPATCH_TIMEOUT_MS = 24 * 60 * 60 * 1000;
export const SCHEDULED_CLIENT_MESSAGE_ID_PREFIX = 'scheduled:';

const RETRY_BASE_DELAY_MS = 5000;
const RETRY_MAX_DELAY_MS = 5 * 60 * 1000;

/**
 예약 시각이 허용 범위 안인지 검사한다.

 @throws BadRequestException 예약 시각이 지금부터 1분 뒤에서 120일 뒤 사이가 아닌 경우
 */
export function requireScheduleTime(scheduledAt: Date, now: Date): void {
	const lead = scheduledAt.getTime() - now.getTime();
	if (Number.isNaN(lead) || lead < SCHEDULE_MIN_LEAD_MS || lead > SCHEDULE_MAX_LEAD_MS) {
		throw new BadRequestException('예약 시각은 지금부터 1분 뒤에서 120일 뒤 사이여야 합니다');
	}
}

/**
 사용자당 대기 중인 예약 한도를 검사한다.

 @throws ConflictException 대기 중인 예약이 한도에 이른 경우
 */
export function requireScheduleCapacity(waitingCount: number): void {
	if (waitingCount >= MAX_WAITING_SCHEDULED_MESSAGES) {
		throw new ConflictException(`대기 중인 예약 메시지는 ${MAX_WAITING_SCHEDULED_MESSAGES}건까지 만들 수 있습니다`);
	}
}

/**
 첨부만 보내는 메시지는 본문이 비어도 되지만, 그 밖의 메시지는 공백만으로 보낼 수 없다.

 @throws BadRequestException 첨부가 없고 본문이 공백뿐인 경우
 */
export function requireScheduledContent(content: string, attachmentCount: number): void {
	if (attachmentCount === 0 && content.trim() === '') {
		throw new BadRequestException('메시지 내용을 입력하세요');
	}
}

/**
 발송 검사 단계. 같은 예외라도 단계에 따라 실패 사유가 다르다.
 - `MESSAGE_SEND`: `message_send` 권한 검사
 - `FILE_ATTACH`: `file_attach` 권한 검사
 - `TARGET`: 채널 존재, 보관 여부, DM 차단 검사. 앞 단계를 통과했으므로 여기의 403은 DM 차단이다.
 - `PUBLISH`: 전송 간격과 멘션 한도 예약, Kafka 발행
 */
export type DispatchStage = 'MESSAGE_SEND' | 'FILE_ATTACH' | 'TARGET' | 'PUBLISH';

export type DispatchOutcome = {kind: 'FAIL'; reason: ScheduledMessageFailureReason} | {kind: 'RETRY'; nextAttemptAt: Date};

function permanentFailure(stage: DispatchStage, error: unknown): ScheduledMessageFailureReason | undefined {
	if (error instanceof ForbiddenException) {
		return stage === 'MESSAGE_SEND' ? 'MESSAGE_SEND_DENIED' : (stage === 'FILE_ATTACH' ? 'FILE_ATTACH_DENIED' : 'DM_BLOCKED');
	}

	if (stage === 'TARGET' && error instanceof NotFoundException) {
		return 'CHANNEL_NOT_FOUND';
	}

	if (stage === 'TARGET' && error instanceof ConflictException) {
		return 'CHANNEL_ARCHIVED';
	}

	// 메시지 하나의 멘션 수 초과는 다시 보내도 같다. 시간창 한도(429)는 기다리면 풀린다.
	return stage === 'PUBLISH' && error instanceof BadRequestException ? 'TOO_MANY_MENTIONS' : undefined;
}

/**
 발송 시각에서 {@link DISPATCH_TIMEOUT_MS}가 지났는지 판정한다. 워커가 오래 멈췄다가 다시 돌 때도 오래된 예약을 보내지 않는다.
 */
export function isDispatchExpired(scheduledAt: Date, now: Date): boolean {
	return now.getTime() - scheduledAt.getTime() >= DISPATCH_TIMEOUT_MS;
}

/**
 지수 백오프 재시도 시각 (5초, 10초, 20초 … 상한 5분)
 */
export function retryAt(attempts: number, now: Date): Date {
	const delay = Math.min(RETRY_BASE_DELAY_MS * (2 ** Math.min(attempts, 10)), RETRY_MAX_DELAY_MS);
	return new Date(now.getTime() + delay);
}

/**
 발송 검사나 발행에서 난 오류로 예약을 실패시킬지, 다시 시도할지 정한다.
 권한 상실, 채널 삭제와 보관, DM 차단, 멘션 수 초과는 실패다. 그 밖의 오류는 일시 오류로 보고 다시 시도하지만,
 발송 시각에서 {@link DISPATCH_TIMEOUT_MS}가 지났으면 실패다.
 */
export function decideDispatchOutcome(
	stage: DispatchStage,
	error: unknown,
	message: {scheduledAt: Date; attempts: number},
	now: Date,
): DispatchOutcome {
	const reason = permanentFailure(stage, error);
	if (reason !== undefined) {
		return {kind: 'FAIL', reason};
	}

	return isDispatchExpired(message.scheduledAt, now) ? {kind: 'FAIL', reason: 'DISPATCH_TIMEOUT'} : {kind: 'RETRY', nextAttemptAt: retryAt(message.attempts, now)};
}

export function describeDispatchError(error: unknown): string {
	if (error instanceof HttpException) {
		return `${error.getStatus()} ${error.message}`;
	}

	return error instanceof Error ? error.message : String(error);
}
