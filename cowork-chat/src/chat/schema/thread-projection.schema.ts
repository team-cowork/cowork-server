import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';

export const THREAD_STATUSES = ['PENDING', 'ACTIVE', 'REJECTED'] as const;
export type ThreadStatus = typeof THREAD_STATUSES[number];

/**
 `cowork-channel`의 `channel.thread.state.v1`을 따르는 스레드 읽기 모델.

 메시지 전송 시 스레드가 같은 채널에 속하는지, 보관되지 않았는지 확인하는 데 쓴다.
 삭제(채널 삭제, 시작 메시지 검증 거부)도 `deleted`로 남겨 늦게 도착한 이전 상태가 되살리지 못하게 한다.
 */
@schema({timestamps: true, versionKey: false})
export class ThreadProjection {
	@prop({required: true}) threadId!: number;
	@prop({required: true}) channelId!: number;
	@prop({required: true}) name!: string;
	@prop({required: true}) parentMessageId!: string;
	@prop({required: true}) createdBy!: number;
	@prop({required: true, type: String, enum: THREAD_STATUSES}) status!: ThreadStatus;
	@prop({required: true}) archived!: boolean;
	@prop({required: true}) deleted!: boolean;
	@prop({required: true}) sourceVersion!: bigint;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
}

export const threadProjectionSchema = SchemaFactory.createForClass(ThreadProjection)
	.index({threadId: 1}, {unique: true});
