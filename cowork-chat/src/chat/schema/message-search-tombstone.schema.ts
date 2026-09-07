import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';

export const MESSAGE_SEARCH_TOMBSTONE_STATUSES = ['PENDING', 'PROCESSING', 'DELETED', 'FAILED'] as const;
export type MessageSearchTombstoneStatus = typeof MESSAGE_SEARCH_TOMBSTONE_STATUSES[number];

/**
 * 메시지 삭제 의도를 durable하게 남기는 tombstone.
 *
 * 메시지 도큐먼트는 hard delete되어 사라지므로 삭제 색인 명령은 별도 collection에 남아야 한다.
 * `version`은 삭제 직전에 메시지에서 예약한 단조 증가 버전으로, Elasticsearch 외부 버전 삭제에
 * 그대로 사용해 지연된 upsert가 삭제된 문서를 되살리지 못하게 한다.
 *
 * tombstone은 재구축 catch-up이 참조할 수 있도록 `expiresAt` TTL까지 보존한다.
 */
@Schema({ timestamps: true, versionKey: false, collection: 'message_search_tombstones' })
export class MessageSearchTombstone {
    createdAt!: Date;
    updatedAt!: Date;

    /** 삭제된 메시지의 ObjectId 문자열. 색인 문서 ID와 같다. */
    @Prop({ required: true }) messageId!: string;

    @Prop({ required: true }) teamId!: number;
    @Prop({ required: true }) projectId!: number;
    @Prop({ required: true }) channelId!: number;

    /** 삭제 시점에 메시지에서 예약한 색인 버전 */
    @Prop({ required: true }) version!: number;

    @Prop({ required: true, enum: MESSAGE_SEARCH_TOMBSTONE_STATUSES, default: 'PENDING' })
    status!: MessageSearchTombstoneStatus;

    @Prop({ default: 0 }) retryCount!: number;
    @Prop({ type: Date, default: null }) nextAttemptAt!: Date | null;
    @Prop({ type: Date, default: null }) processingStartedAt!: Date | null;
    @Prop({ type: String, default: null }) lastError!: string | null;
    @Prop({ type: Date, default: null }) deletedAt!: Date | null;

    /** TTL 만료 시각. 전체 재구축이 삭제를 재생할 수 있을 만큼 길게 잡는다. */
    @Prop({ required: true }) expiresAt!: Date;
}

export const MessageSearchTombstoneSchema = SchemaFactory.createForClass(MessageSearchTombstone);

/** 같은 메시지에 대한 tombstone 중복 생성을 막는다. 삭제 재시도는 멱등하게 동작한다. */
MessageSearchTombstoneSchema.index({ messageId: 1 }, { unique: true });

/** 워커가 처리 대기 tombstone을 시각 순으로 꺼내기 위한 인덱스 */
MessageSearchTombstoneSchema.index({ status: 1, nextAttemptAt: 1 });

/** 재구축 catch-up이 특정 시점 이후의 삭제만 재생하기 위한 인덱스 */
MessageSearchTombstoneSchema.index({ updatedAt: 1, _id: 1 });

/** 보존 기간이 지난 tombstone을 MongoDB가 직접 정리한다. */
MessageSearchTombstoneSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 0 });
