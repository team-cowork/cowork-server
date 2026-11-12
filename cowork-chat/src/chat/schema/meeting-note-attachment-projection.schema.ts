import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';

export type MeetingNoteAttachment = {objectKey: string; name: string};

@schema({timestamps: true, versionKey: false})
export class MeetingNoteAttachmentProjection {
	@prop({required: true}) noteId!: number;
	@prop({required: true}) channelId!: number;
	@prop({required: true}) authorId!: number;
	@prop({type: [{_id: false, objectKey: String, name: String}], default: []}) attachments!: MeetingNoteAttachment[];
	@prop({required: true}) deleted!: boolean;
	@prop({required: true}) sourceVersion!: bigint;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
}

export const meetingNoteAttachmentProjectionSchema = SchemaFactory.createForClass(MeetingNoteAttachmentProjection)
	.index({noteId: 1}, {unique: true})
	.index({'attachments.objectKey': 1, deleted: 1});
