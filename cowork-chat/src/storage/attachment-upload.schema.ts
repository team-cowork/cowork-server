import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';

// 발급 기록과 확정 첨부의 메시지 전송 재시도 창을 7일로 제한한다.
export const ATTACHMENT_UPLOAD_RETENTION_SECONDS = 7 * 24 * 60 * 60;

export type ConfirmedAttachment = {objectKey: string; name: string; size: number; mimeType: string};

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
}

export const attachmentUploadSchema = SchemaFactory.createForClass(AttachmentUpload)
	.index({objectKey: 1}, {unique: true, sparse: true})
	.index({createdAt: 1}, {expireAfterSeconds: ATTACHMENT_UPLOAD_RETENTION_SECONDS});
