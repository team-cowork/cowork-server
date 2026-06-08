import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument, Types } from 'mongoose';

export type DmMessageDocument = HydratedDocument<DmMessage>;

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

@Schema({ _id: false })
class Reaction {
    @Prop({ required: true }) emoji!: string;
    @Prop({ type: [Number], default: [] }) userIds!: number[];
}

const AttachmentSchema = SchemaFactory.createForClass(Attachment);
const EditHistorySchema = SchemaFactory.createForClass(EditHistory);
const ReactionSchema = SchemaFactory.createForClass(Reaction);

@Schema({ timestamps: true, versionKey: false })
export class DmMessage {
    createdAt!: Date;
    updatedAt!: Date;

    @Prop({ type: Types.ObjectId, required: true }) conversationId!: Types.ObjectId;

    @Prop({ required: true }) authorId!: number;

    @Prop({ required: true, maxlength: 25000 }) content!: string;

    @Prop({ enum: ['TEXT', 'FILE', 'SYSTEM'], default: 'TEXT' }) type!: string;

    @Prop({ type: [AttachmentSchema], default: [] }) attachments!: Attachment[];

    @Prop({ default: false }) isEdited!: boolean;

    @Prop({ type: [EditHistorySchema], default: [] }) editHistory!: EditHistory[];

    @Prop({ type: [ReactionSchema], default: [] }) reactions!: Array<{ emoji: string; userIds: number[] }>;

    @Prop({ type: String }) clientMessageId?: string | null;

    @Prop({ type: [Number], default: [] }) mentions!: number[];

    @Prop({ enum: ['PENDING', 'PROCESSING', 'SENT', 'FAILED'], default: 'PENDING' })
    notificationStatus!: string;

    @Prop({ default: 0 }) notificationRetryCount!: number;
}

export const DmMessageSchema = SchemaFactory.createForClass(DmMessage);

DmMessageSchema.index({ conversationId: 1, _id: -1 });
DmMessageSchema.index({ authorId: 1 });
DmMessageSchema.index({ clientMessageId: 1 }, { unique: true, sparse: true });
DmMessageSchema.index({ notificationStatus: 1, createdAt: 1 });
