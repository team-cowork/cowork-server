import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';

/** 계약을 위반했지만 원문을 보존한 뒤에만 건너뛴 Kafka projection record. */
@Schema({ timestamps: true, versionKey: false, collection: 'projection_quarantine_records' })
export class ProjectionQuarantineRecord {
    @Prop({ required: true }) groupId!: string;
    @Prop({ required: true }) topic!: string;
    @Prop({ required: true }) partition!: number;
    @Prop({ required: true }) messageOffset!: bigint;
    @Prop({ type: String, default: null }) eventKey!: string | null;
    @Prop({ type: String, default: null }) payload!: string | null;
    @Prop({ required: true }) reason!: string;
}

export const ProjectionQuarantineRecordSchema = SchemaFactory.createForClass(ProjectionQuarantineRecord);
ProjectionQuarantineRecordSchema.index(
    { groupId: 1, topic: 1, partition: 1, messageOffset: 1 },
    { unique: true },
);
