import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';

export type ConfirmedAttachment = { objectKey: string; name: string; size: number; mimeType: string };

@Schema({ collection: 'attachment_uploads', timestamps: true, versionKey: false })
export class AttachmentUpload {
    @Prop({ required: true, unique: true }) uploadKey!: string;
    @Prop({ required: true }) channelId!: number;
    @Prop({ required: true }) userId!: number;
    @Prop({ required: true }) name!: string;
    @Prop({ required: true }) mimeType!: string;
    @Prop({ required: true }) declaredSize!: number;
    @Prop({ type: String }) objectKey?: string;
    @Prop({ type: Number }) size?: number;
    @Prop({ type: String }) clientMessageId?: string;
}

export const AttachmentUploadSchema = SchemaFactory.createForClass(AttachmentUpload);
AttachmentUploadSchema.index({ objectKey: 1 }, { unique: true, sparse: true });
