import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';
import {type ConfirmedAttachment} from '../../storage/attachment-upload.schema.js';

export const SCHEDULED_MESSAGE_STATUSES = ['PENDING', 'DISPATCHING', 'SENT', 'FAILED', 'CANCELED'] as const;
export type ScheduledMessageStatus = typeof SCHEDULED_MESSAGE_STATUSES[number];

export const SCHEDULED_MESSAGE_FAILURE_REASONS = [
	'MESSAGE_SEND_DENIED',
	'FILE_ATTACH_DENIED',
	'CHANNEL_NOT_FOUND',
	'CHANNEL_ARCHIVED',
	'DM_BLOCKED',
	'TOO_MANY_MENTIONS',
	'DISPATCH_TIMEOUT',
] as const;
export type ScheduledMessageFailureReason = typeof SCHEDULED_MESSAGE_FAILURE_REASONS[number];

@schema({_id: false})
class ScheduledAttachment implements ConfirmedAttachment {
	@prop({required: true}) objectKey!: string;
	@prop({required: true}) name!: string;
	@prop({required: true}) size!: number;
	@prop({required: true}) mimeType!: string;
	@prop({default: false}) spoiler!: boolean;
}

/**
 예약 메시지.

 첨부는 예약할 때 업로드 기록을 점유해 확정값을 저장한다. 업로드 기록의 재시도 창(7일)보다 늦게 발송해도 첨부를 쓸 수 있다.
 발송할 때는 고정된 `clientMessageId`로 발행하므로, 워커가 같은 예약을 다시 발행해도 메시지는 한 번만 저장된다.
 */
@schema({collection: 'scheduled_messages', timestamps: true, versionKey: false})
export class ScheduledMessage {
	createdAt!: Date;
	updatedAt!: Date;

	@prop({required: true}) authorId!: number;
	@prop({required: true}) channelId!: number;
	/**
    실패 알림에 쓰는 채널의 팀 ID. DM은 null이다.
    */
	@prop({type: Number, default: null}) teamId!: Nullable<number>;

	@prop({required: true, type: String}) content!: string;
	@prop({required: true, type: String}) type!: string;
	@prop({type: [ScheduledAttachment], default: []}) attachments!: ScheduledAttachment[];
	@prop({required: true}) clientMessageId!: string;
	@prop({required: true}) scheduledAt!: Date;

	@prop({required: true, type: String, enum: SCHEDULED_MESSAGE_STATUSES}) status!: ScheduledMessageStatus;

	/**
    다음 처리 가능 시각. `PENDING`이면 발송 시각이나 재시도 시각, `DISPATCHING`이면 점유 만료 시각,
    후처리 전의 `FAILED`와 `CANCELED`이면 후처리 재시도 시각이다.
    */
	@prop({required: true}) nextAttemptAt!: Date;

	/**
    점유 호출마다 새로 발급하는 식별자. 점유가 끝난 뒤 늦게 끝난 워커의 결과 기록을 막는다.
    */
	@prop({type: String, default: null}) claimId!: Nullable<string>;

	@prop({default: 0}) attempts!: number;
	@prop({type: String, default: null}) lastError!: Nullable<string>;
	@prop({type: String, enum: [...SCHEDULED_MESSAGE_FAILURE_REASONS, null], default: null}) failureReason!: Nullable<ScheduledMessageFailureReason>;
	@prop({type: Date, default: null}) failedAt!: Nullable<Date>;
	/**
    실패하거나 취소한 예약의 후처리를 끝낸 시각. 후처리는 첨부를 회수 대상으로 넘기고, 실패면 작성자에게 알리는 일이다.
    상태를 먼저 바꾸고 워커가 후처리하므로, 그 사이에 프로세스가 죽어도 후처리가 빠지지 않는다.
    */
	@prop({type: Date, default: null}) finalizedAt!: Nullable<Date>;

	/**
    `SENT`, `FAILED`, `CANCELED` 기록을 지울 시각. 대기 중인 예약은 null이다.
    */
	@prop({type: Date, default: null}) expiresAt!: Nullable<Date>;
}

export const scheduledMessageSchema = SchemaFactory.createForClass(ScheduledMessage)
	.index({clientMessageId: 1}, {unique: true})
	.index({status: 1, nextAttemptAt: 1})
	.index({authorId: 1, status: 1, scheduledAt: 1})
	.index({'attachments.objectKey': 1}, {sparse: true})
	.index({expiresAt: 1}, {expireAfterSeconds: 0});
