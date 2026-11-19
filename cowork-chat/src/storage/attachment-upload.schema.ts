import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';

// 발급 기록과 확정 첨부의 메시지 전송 재시도 창을 7일로 제한한다.
export const ATTACHMENT_UPLOAD_RETENTION_SECONDS = 7 * 24 * 60 * 60;
// 재시도 창 뒤 비동기 메시지 저장과 quarantine 재처리를 하루 더 기다린다.
export const UPLOAD_RECLAIM_AGE_MS = (ATTACHMENT_UPLOAD_RETENTION_SECONDS + (24 * 60 * 60)) * 1000;
// 회수 워커가 처리하지 못한 기록만 지우는 backstop이다. 회수 시점(재시도 창 + 유예)보다 길어야 한다.
export const ATTACHMENT_UPLOAD_TTL_SECONDS = 14 * 24 * 60 * 60;

// 스포일러 표시(spoiler)는 메시지 전송 요청에서 정한다. 이전 producer의 Kafka 이벤트에는 없을 수 있다.
export type ConfirmedAttachment = {objectKey: string; name: string; size: number; mimeType: string; spoiler?: boolean};

@schema({collection: 'attachment_uploads', timestamps: true, versionKey: false})
export class AttachmentUpload {
	@prop({required: true, unique: true}) uploadKey!: string;
	@prop({required: true}) channelId!: number;
	@prop({required: true}) userId!: number;
	@prop({required: true}) name!: string;
	@prop({required: true}) mimeType!: string;
	@prop({required: true}) declaredSize!: number;
	@prop({type: String}) objectKey?: string;
	@prop({type: Number}) size?: number;
	@prop({type: String}) clientMessageId?: string;
	// 회수 워커 점유 만료 시각. 이 시각 전까지는 다른 프로세스(배포 중 새 프로세스 등)도 같은 기록을 점유하지 않는다.
	@prop({type: Date}) reclaimAttemptAt?: Date;
	@prop({type: String}) reclaimClaimId?: string;
	createdAt?: Date;
}

export const attachmentUploadSchema = SchemaFactory.createForClass(AttachmentUpload)
	.index({objectKey: 1}, {unique: true, sparse: true})
	.index({createdAt: 1}, {expireAfterSeconds: ATTACHMENT_UPLOAD_TTL_SECONDS});
