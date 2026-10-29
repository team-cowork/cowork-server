import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

export const CHAT_MESSAGE_QUARANTINE_STATUSES = [
	'QUARANTINED', 'REPROCESS_REQUESTED', 'PROCESSING', 'REPROCESSED', 'DISCARDED',
] as const;
export type ChatMessageQuarantineStatus = typeof CHAT_MESSAGE_QUARANTINE_STATUSES[number];
export const CHAT_MESSAGE_QUARANTINE_ERROR_TYPES = ['JSON_ERROR', 'CONTRACT_ERROR', 'SCOPE_ERROR'] as const;
export type ChatMessageQuarantineErrorType = typeof CHAT_MESSAGE_QUARANTINE_ERROR_TYPES[number];

/**
Chat.message consumer가 durable하게 보존한 재처리 불가 Kafka record.
*/
@schema({timestamps: true, versionKey: false, collection: 'chat_message_quarantine_records'})
export class ChatMessageQuarantineRecord {
	@prop({required: true}) groupId!: string;
	@prop({required: true}) topic!: string;
	@prop({required: true}) partition!: number;
	@prop({required: true}) messageOffset!: string;
	@prop({type: String, default: null}) eventKey!: Nullable<string>;
	@prop({type: String, default: null}) payload!: Nullable<string>;
	@prop({required: true, default: false}) payloadTruncated!: boolean;
	@prop({required: true}) payloadBytes!: number;
	@prop({required: true}) contractVersion!: number;
	@prop({required: true, enum: CHAT_MESSAGE_QUARANTINE_ERROR_TYPES}) errorType!: ChatMessageQuarantineErrorType;
	@prop({required: true}) reasonCode!: string;
	@prop({required: true}) reason!: string;
	@prop({required: true, enum: CHAT_MESSAGE_QUARANTINE_STATUSES, default: 'QUARANTINED'}) status!: ChatMessageQuarantineStatus;
	@prop({required: true}) firstObservedAt!: Date;
	@prop({required: true}) lastObservedAt!: Date;
	@prop({type: Date, default: null}) reprocessRequestedAt!: Nullable<Date>;
	@prop({type: Date, default: null}) processingStartedAt!: Nullable<Date>;
	@prop({type: Date, default: null}) processedAt!: Nullable<Date>;
	@prop({type: Date, default: null}) discardedAt!: Nullable<Date>;
	@prop({required: true}) expiresAt!: Date;
	@prop({type: String, default: null}) lastReprocessError!: Nullable<string>;
}

export const chatMessageQuarantineRecordSchema = SchemaFactory.createForClass(ChatMessageQuarantineRecord)
	.index(
		{
			groupId: 1, topic: 1, partition: 1, messageOffset: 1,
		},
		{unique: true},
	)
	.index({expiresAt: 1}, {expireAfterSeconds: 0})
	.index({status: 1, reprocessRequestedAt: 1});
