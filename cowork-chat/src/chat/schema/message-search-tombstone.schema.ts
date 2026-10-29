import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

export const MESSAGE_SEARCH_TOMBSTONE_STATUSES = ['PENDING', 'PROCESSING', 'DELETED', 'FAILED'] as const;
export type MessageSearchTombstoneStatus = typeof MESSAGE_SEARCH_TOMBSTONE_STATUSES[number];

/**
 메시지 삭제 의도를 durable하게 남기는 tombstone.

 메시지 도큐먼트는 hard delete되어 사라지므로 삭제 색인 명령은 별도 collection에 남아야 한다.
 `version`은 삭제 직전에 메시지에서 예약한 단조 증가 버전으로, Elasticsearch 외부 버전 삭제에
 그대로 사용해 지연된 upsert가 삭제된 문서를 되살리지 못하게 한다.

 tombstone은 재구축 catch-up이 참조할 수 있도록 `expiresAt` TTL까지 보존한다.
 */
@schema({timestamps: true, versionKey: false, collection: 'message_search_tombstones'})
export class MessageSearchTombstone {
	createdAt!: Date;
	updatedAt!: Date;

	/**
    삭제된 메시지의 ObjectId 문자열. 색인 문서 ID와 같다.
    */
	@prop({required: true}) messageId!: string;

	@prop({required: true}) teamId!: number;
	@prop({required: true}) projectId!: number;
	@prop({required: true}) channelId!: number;

	/**
    삭제 시점에 메시지에서 예약한 색인 버전
    */
	@prop({required: true}) version!: number;

	@prop({
		type: String, required: true, enum: MESSAGE_SEARCH_TOMBSTONE_STATUSES, default: 'PENDING',
	})
	status!: MessageSearchTombstoneStatus;

	@prop({default: 0}) retryCount!: number;
	@prop({type: Date, default: null}) nextAttemptAt!: Nullable<Date>;
	@prop({type: Date, default: null}) processingStartedAt!: Nullable<Date>;

	/**
    점유 호출마다 새로 발급하는 식별자. 같은 밀리초에 점유한 두 워커를 구분한다.
    */
	@prop({type: String, default: null}) claimId!: Nullable<string>;
	@prop({type: String, default: null}) lastError!: Nullable<string>;
	@prop({type: Date, default: null}) deletedAt!: Nullable<Date>;

	/**
    TTL 만료 시각. 전체 재구축이 삭제를 재생할 수 있을 만큼 길게 잡는다.
    */
	@prop({required: true}) expiresAt!: Date;
}

export const messageSearchTombstoneSchema = SchemaFactory.createForClass(MessageSearchTombstone)
	/**
	같은 메시지에 대한 tombstone 중복 생성을 막는다. 삭제 재시도는 멱등하게 동작한다.
	*/
	.index({messageId: 1}, {unique: true})
	/**
	워커가 처리 대기 tombstone을 시각 순으로 꺼내기 위한 인덱스
	*/
	.index({status: 1, nextAttemptAt: 1})
	/**
	재구축 catch-up이 특정 시점 이후의 삭제만 재생하기 위한 인덱스
	*/
	.index({updatedAt: 1, _id: 1})
	/**
	보존 기간이 지난 tombstone을 MongoDB가 직접 정리한다.
	*/
	.index({expiresAt: 1}, {expireAfterSeconds: 0});
