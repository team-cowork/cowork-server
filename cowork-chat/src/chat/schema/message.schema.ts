import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument, Types } from 'mongoose';

export type MessageDocument = HydratedDocument<Message>;

@Schema({ _id: false })
class Attachment {
    @Prop({ required: true }) name!: string;
    @Prop({ required: true }) url!: string;
    @Prop({ required: true }) size!: number;
    @Prop({ required: true }) mimeType!: string;
}

@Schema({ _id: false })
class EditHistory {
    @Prop({ required: true }) content!: string;
    @Prop({ required: true }) editedAt!: Date;
}

@Schema({ timestamps: true, versionKey: false })
export class Message {
    createdAt!: Date;
    updatedAt!: Date;

    @Prop({ required: true }) teamId!: number;
    @Prop({ type: Number, default: null }) projectId!: number | null;
    @Prop({ required: true }) channelId!: number;
    @Prop({ required: true }) authorId!: number;

    @Prop({ required: true, maxlength: 25000 }) content!: string;

    @Prop({ enum: ['TEXT', 'FILE', 'SYSTEM'], default: 'TEXT' }) type!: string;

    @Prop({ type: [Attachment], default: [] }) attachments!: Attachment[];

    @Prop({ type: Types.ObjectId, default: null }) parentMessageId!: Types.ObjectId | null;

    @Prop({ default: false }) isEdited!: boolean;

    @Prop({ type: [EditHistory], default: [] }) editHistory!: EditHistory[];

    @Prop({ default: false }) isPinned!: boolean;

    @Prop({ type: String, default: null }) clientMessageId!: string | null;
}

export const MessageSchema = SchemaFactory.createForClass(Message);

MessageSchema.index({ channelId: 1, _id: -1 });
MessageSchema.index({ authorId: 1 });
MessageSchema.index({ parentMessageId: 1 });
MessageSchema.index({ isPinned: 1, channelId: 1 });
MessageSchema.index({ clientMessageId: 1 }, { unique: true, sparse: true });
