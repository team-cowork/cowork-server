import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';

export type ProjectionRunMode = 'BOOTSTRAP' | 'INCREMENTAL' | 'REBUILD';
export type ProjectionDatasetStatus =
    | 'INITIALIZING'
    | 'ACTIVE'
    | 'REBUILD_REQUESTED'
    | 'RESETTING'
    | 'REBUILDING'
    | 'REBUILD_REQUIRED';

export interface ProjectionDatasetPartitionOffset {
    partition: number;
    offset: string;
}

/** Kafka checkpoint와 실제 Mongo projection collection을 묶는 durable dataset identity. */
@Schema({ timestamps: true, versionKey: false, collection: 'projection_datasets' })
export class ProjectionDataset {
    @Prop({ required: true, unique: true }) stream!: string;
    @Prop({ required: true }) groupId!: string;
    @Prop({ required: true }) topic!: string;
    @Prop({ required: true }) sourceGeneration!: string;
    @Prop({ required: true }) datasetGeneration!: string;
    @Prop({ required: true }) mode!: ProjectionRunMode;
    @Prop({ required: true }) status!: ProjectionDatasetStatus;
    @Prop({ type: String, default: null }) reason!: string | null;
    @Prop({ type: Date, default: null }) rebuildRequestedAt!: Date | null;
    @Prop({ type: Date, default: null }) activatedAt!: Date | null;
    @Prop({ type: Number, default: 0 }) activationDocumentCount!: number;
    @Prop({ type: [{ partition: Number, offset: String }], default: [] })
        baseOffsets!: ProjectionDatasetPartitionOffset[];
    @Prop({ type: [{ partition: Number, offset: String }], default: [] })
        targetOffsets!: ProjectionDatasetPartitionOffset[];
}

export const ProjectionDatasetSchema = SchemaFactory.createForClass(ProjectionDataset);
ProjectionDatasetSchema.index({ stream: 1 }, { unique: true });
