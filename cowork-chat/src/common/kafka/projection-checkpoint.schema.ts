import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';

/** Kafka projection replay의 shared Mongo checkpoint. `nextOffset`부터 다시 소비한다. */
@Schema({ timestamps: true, versionKey: false, collection: 'projection_checkpoints' })
export class ProjectionCheckpoint {
    @Prop({ required: true }) groupId!: string;
    @Prop({ required: true }) topic!: string;
    @Prop({ required: true }) partition!: number;
    @Prop({ required: true }) nextOffset!: bigint;
    /** 이 partition에서 마지막으로 정상 검증한 full snapshot 완료 marker offset. */
    @Prop({ type: BigInt, default: null }) snapshotCompletedOffset!: bigint | null;
    @Prop({ type: String, default: null }) snapshotId!: string | null;
    @Prop({ type: String, default: null }) snapshotSource!: string | null;
    @Prop({ type: Date, default: null }) snapshotOccurredAt!: Date | null;
}

export const ProjectionCheckpointSchema = SchemaFactory.createForClass(ProjectionCheckpoint);
ProjectionCheckpointSchema.index({ groupId: 1, topic: 1, partition: 1 }, { unique: true });
