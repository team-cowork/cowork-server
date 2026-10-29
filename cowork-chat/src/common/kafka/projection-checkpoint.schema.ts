import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../type/nullable.js';

/**
Kafka projection replay의 shared Mongo checkpoint. `nextOffset`부터 다시 소비한다.
*/
@schema({timestamps: true, versionKey: false, collection: 'projection_checkpoints'})
export class ProjectionCheckpoint {
	@prop({required: true}) groupId!: string;
	@prop({required: true}) topic!: string;
	@prop({required: true}) partition!: number;
	/**
    Checkpoint가 가리키는 Mongo projection dataset identity.
    */
	@prop({required: true}) datasetGeneration!: string;
	/**
    Topic 재생성/producer 세대 교체 시 운영자가 변경하는 source identity.
    */
	@prop({required: true}) sourceGeneration!: string;
	/**
    현재 partition assignment owner만 checkpoint를 전진시킬 수 있는 fencing token.
    */
	@prop({type: String, default: null}) assignmentEpoch!: Nullable<string>;
	/**
    Kafka가 발급한 현재 owner identity. generation만으로 active owner를 선점할 수 없다.
    */
	@prop({type: String, default: null}) assignmentMemberId!: Nullable<string>;
	@prop({type: Number, default: null}) assignmentGenerationId!: Nullable<number>;
	/**
    Mongo DB time으로 갱신되는 lease heartbeat. 애플리케이션 clock은 fencing에 사용하지 않는다.
    */
	@prop({type: Date, default: null}) assignmentLeaseRenewedAt!: Nullable<Date>;
	/**
    명시적 rebuild 중 현재 owner가 consumption pause를 확인한 generation.
    */
	@prop({type: String, default: null}) rebuildPausedGeneration!: Nullable<string>;
	@prop({required: true}) nextOffset!: bigint;
	/**
    이 partition에서 마지막으로 정상 검증한 full snapshot 완료 marker offset.
    */
	@prop({type: BigInt, default: null}) snapshotCompletedOffset!: Nullable<bigint>;
	@prop({type: String, default: null}) snapshotId!: Nullable<string>;
	@prop({type: String, default: null}) snapshotSource!: Nullable<string>;
	@prop({type: Date, default: null}) snapshotOccurredAt!: Nullable<Date>;
	/**
    격리하고 건너뛴 마지막 snapshot-backed state record offset.
    */
	@prop({type: BigInt, default: null}) invalidRecordOffset!: Nullable<bigint>;
	/**
    Assignment reset과 무관하게 마지막으로 관측한 marker를 기억해 반복 marker를 배제한다.
    */
	@prop({type: BigInt, default: null}) lastSnapshotCompletedOffset!: Nullable<bigint>;
	@prop({type: String, default: null}) lastSnapshotId!: Nullable<string>;
	/**
    Invalid record 뒤 처음 관측한 새로운 full snapshot ID.
    */
	@prop({type: String, default: null}) recoverySnapshotId!: Nullable<string>;
}

export const projectionCheckpointSchema = SchemaFactory.createForClass(ProjectionCheckpoint)
	.index({groupId: 1, topic: 1, partition: 1}, {unique: true});
