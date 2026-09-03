import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';

/** Kafka projection replay의 shared Mongo checkpoint. `nextOffset`부터 다시 소비한다. */
@Schema({ timestamps: true, versionKey: false, collection: 'projection_checkpoints' })
export class ProjectionCheckpoint {
    @Prop({ required: true }) groupId!: string;
    @Prop({ required: true }) topic!: string;
    @Prop({ required: true }) partition!: number;
    /** checkpoint가 가리키는 Mongo projection dataset identity. */
    @Prop({ required: true }) datasetGeneration!: string;
    /** topic 재생성/producer 세대 교체 시 운영자가 변경하는 source identity. */
    @Prop({ required: true }) sourceGeneration!: string;
    /** 현재 partition assignment owner만 checkpoint를 전진시킬 수 있는 fencing token. */
    @Prop({ type: String, default: null }) assignmentEpoch!: string | null;
    /** Kafka가 발급한 현재 owner identity. generation만으로 active owner를 선점할 수 없다. */
    @Prop({ type: String, default: null }) assignmentMemberId!: string | null;
    @Prop({ type: Number, default: null }) assignmentGenerationId!: number | null;
    /** Mongo DB time으로 갱신되는 lease heartbeat. 애플리케이션 clock은 fencing에 사용하지 않는다. */
    @Prop({ type: Date, default: null }) assignmentLeaseRenewedAt!: Date | null;
    /** 명시적 rebuild 중 현재 owner가 consumption pause를 확인한 generation. */
    @Prop({ type: String, default: null }) rebuildPausedGeneration!: string | null;
    @Prop({ required: true }) nextOffset!: bigint;
    /** 이 partition에서 마지막으로 정상 검증한 full snapshot 완료 marker offset. */
    @Prop({ type: BigInt, default: null }) snapshotCompletedOffset!: bigint | null;
    @Prop({ type: String, default: null }) snapshotId!: string | null;
    @Prop({ type: String, default: null }) snapshotSource!: string | null;
    @Prop({ type: Date, default: null }) snapshotOccurredAt!: Date | null;
    /** 격리하고 건너뛴 마지막 snapshot-backed state record offset. */
    @Prop({ type: BigInt, default: null }) invalidRecordOffset!: bigint | null;
    /** assignment reset과 무관하게 마지막으로 관측한 marker를 기억해 반복 marker를 배제한다. */
    @Prop({ type: BigInt, default: null }) lastSnapshotCompletedOffset!: bigint | null;
    @Prop({ type: String, default: null }) lastSnapshotId!: string | null;
    /** invalid record 뒤 처음 관측한 새로운 full snapshot ID. */
    @Prop({ type: String, default: null }) recoverySnapshotId!: string | null;
}

export const ProjectionCheckpointSchema = SchemaFactory.createForClass(ProjectionCheckpoint);
ProjectionCheckpointSchema.index({ groupId: 1, topic: 1, partition: 1 }, { unique: true });
