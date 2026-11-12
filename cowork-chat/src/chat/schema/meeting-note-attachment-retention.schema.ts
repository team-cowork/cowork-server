import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';

// Projection 재구축과 업로드 TTL에 영향을 받지 않는 파일 정리 기록이다.
@schema({collection: 'meeting_note_attachment_retentions', versionKey: false})
export class MeetingNoteAttachmentRetention {
	@prop({required: true}) objectKey!: string;
	@prop({required: true, type: Date}) nextAttemptAt!: Date;
	@prop({type: String}) claimId?: string;
}

export const meetingNoteAttachmentRetentionSchema = SchemaFactory.createForClass(MeetingNoteAttachmentRetention)
	.index({objectKey: 1}, {unique: true})
	.index({nextAttemptAt: 1});
