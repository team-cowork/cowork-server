import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';

export const CHAT_MESSAGE_QUARANTINE_STATUSES = [
    'QUARANTINED', 'REPROCESS_REQUESTED', 'PROCESSING', 'REPROCESSED', 'DISCARDED',
] as const;
export type ChatMessageQuarantineStatus = typeof CHAT_MESSAGE_QUARANTINE_STATUSES[number];
export const CHAT_MESSAGE_QUARANTINE_ERROR_TYPES = ['JSON_ERROR', 'CONTRACT_ERROR', 'SCOPE_ERROR'] as const;
export type ChatMessageQuarantineErrorType = typeof CHAT_MESSAGE_QUARANTINE_ERROR_TYPES[number];

/** chat.message consumer가 durable하게 보존한 재처리 불가 Kafka record. */
@Schema({ timestamps: true, versionKey: false, collection: 'chat_message_quarantine_records' })
export class ChatMessageQuarantineRecord {
    @Prop({ required: true }) groupId!: string;
    @Prop({ required: true }) topic!: string;
    @Prop({ required: true }) partition!: number;
    @Prop({ required: true }) messageOffset!: string;
    @Prop({ type: String, default: null }) eventKey!: string | null;
    @Prop({ type: String, default: null }) payload!: string | null;
    @Prop({ required: true, default: false }) payloadTruncated!: boolean;
    @Prop({ required: true }) payloadBytes!: number;
    @Prop({ required: true }) contractVersion!: number;
    @Prop({ required: true, enum: CHAT_MESSAGE_QUARANTINE_ERROR_TYPES }) errorType!: ChatMessageQuarantineErrorType;
    @Prop({ required: true }) reasonCode!: string;
    @Prop({ required: true }) reason!: string;
    @Prop({ required: true, enum: CHAT_MESSAGE_QUARANTINE_STATUSES, default: 'QUARANTINED' }) status!: ChatMessageQuarantineStatus;
    @Prop({ required: true }) firstObservedAt!: Date;
    @Prop({ required: true }) lastObservedAt!: Date;
    @Prop({ type: Date, default: null }) reprocessRequestedAt!: Date | null;
    @Prop({ type: Date, default: null }) processingStartedAt!: Date | null;
    @Prop({ type: Date, default: null }) processedAt!: Date | null;
    @Prop({ type: Date, default: null }) discardedAt!: Date | null;
    @Prop({ required: true }) expiresAt!: Date;
    @Prop({ type: String, default: null }) lastReprocessError!: string | null;
}

export const ChatMessageQuarantineRecordSchema = SchemaFactory.createForClass(ChatMessageQuarantineRecord);
ChatMessageQuarantineRecordSchema.index(
    { groupId: 1, topic: 1, partition: 1, messageOffset: 1 },
    { unique: true },
);
ChatMessageQuarantineRecordSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 0 });
ChatMessageQuarantineRecordSchema.index({ status: 1, reprocessRequestedAt: 1 });
