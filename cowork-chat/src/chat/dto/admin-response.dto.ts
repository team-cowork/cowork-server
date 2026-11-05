import {ApiProperty as apiProperty} from '@nestjs/swagger';
import {type Nullable} from '../../common/type/nullable.js';
import {CHAT_MESSAGE_QUARANTINE_ERROR_TYPES, CHAT_MESSAGE_QUARANTINE_STATUSES} from '../schema/chat-message-quarantine.schema.js';

export class MessageSearchStatusDto {
	@apiProperty({type: 'object', additionalProperties: {type: 'integer'}, description: 'PENDING, PROCESSING, SYNCED, FAILED, DELETING, SKIPPED 상태별 건수'}) messages!: Record<string, number>;
	@apiProperty({type: 'object', additionalProperties: {type: 'integer'}, description: 'PENDING, PROCESSING, DELETED, FAILED 상태별 삭제 작업 건수'}) tombstones!: Record<string, number>;
	@apiProperty({type: [String]}) aliasTargets!: string[];
	@apiProperty() indexScopeMessages!: number;
	@apiProperty({type: String, format: 'date-time', nullable: true}) oldestPendingAt!: Nullable<string>;
	@apiProperty() rebuildRunning!: boolean;
	@apiProperty({type: String, format: 'date-time', nullable: true}) lastRebuildStartedAt!: Nullable<string>;
	@apiProperty({type: String, format: 'date-time', nullable: true}) lastRebuildSucceededAt!: Nullable<string>;
	@apiProperty({type: String, format: 'date-time', nullable: true}) lastRebuildFailedAt!: Nullable<string>;
	@apiProperty({type: String, nullable: true}) lastRebuildIndex!: Nullable<string>;
	@apiProperty({type: String, nullable: true}) lastRebuildError!: Nullable<string>;
}

export class QuarantineRecordDto {
	@apiProperty() _id!: string;
	@apiProperty() groupId!: string;
	@apiProperty() topic!: string;
	@apiProperty() partition!: number;
	@apiProperty() messageOffset!: string;
	@apiProperty() payloadTruncated!: boolean;
	@apiProperty() payloadBytes!: number;
	@apiProperty() contractVersion!: number;
	@apiProperty({enum: CHAT_MESSAGE_QUARANTINE_ERROR_TYPES}) errorType!: string;
	@apiProperty() reasonCode!: string;
	@apiProperty() reason!: string;
	@apiProperty({enum: CHAT_MESSAGE_QUARANTINE_STATUSES}) status!: string;
	@apiProperty({format: 'date-time'}) firstObservedAt!: string;
	@apiProperty({format: 'date-time'}) lastObservedAt!: string;
	@apiProperty({type: String, format: 'date-time', nullable: true}) reprocessRequestedAt!: Nullable<string>;
	@apiProperty({type: String, format: 'date-time', nullable: true}) processingStartedAt!: Nullable<string>;
	@apiProperty({type: String, format: 'date-time', nullable: true}) processedAt!: Nullable<string>;
	@apiProperty({type: String, format: 'date-time', nullable: true}) discardedAt!: Nullable<string>;
	@apiProperty({format: 'date-time'}) expiresAt!: string;
	@apiProperty({type: String, nullable: true}) lastReprocessError!: Nullable<string>;
	@apiProperty({format: 'date-time'}) createdAt!: string;
	@apiProperty({format: 'date-time'}) updatedAt!: string;
}
