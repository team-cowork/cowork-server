import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../type/nullable.js';

export type ProjectionRunMode = 'BOOTSTRAP' | 'INCREMENTAL' | 'REBUILD';
export type ProjectionDatasetStatus =
	| 'INITIALIZING'
	| 'ACTIVE'
	| 'REBUILD_REQUESTED'
	| 'RESETTING'
	| 'REBUILDING'
	| 'REBUILD_REQUIRED';

export type ProjectionDatasetPartitionOffset = {
	partition: number;
	offset: string;
};

/**
Kafka checkpoint와 실제 Mongo projection collection을 묶는 durable dataset identity.
*/
@schema({timestamps: true, versionKey: false, collection: 'projection_datasets'})
export class ProjectionDataset {
	@prop({required: true}) stream!: string;
	@prop({required: true}) groupId!: string;
	@prop({required: true}) topic!: string;
	@prop({required: true}) sourceGeneration!: string;
	@prop({required: true}) datasetGeneration!: string;
	@prop({required: true}) mode!: ProjectionRunMode;
	@prop({required: true}) status!: ProjectionDatasetStatus;
	@prop({type: String, default: null}) reason!: Nullable<string>;
	@prop({type: Date, default: null}) rebuildRequestedAt!: Nullable<Date>;
	@prop({type: Date, default: null}) activatedAt!: Nullable<Date>;
	@prop({type: Number, default: 0}) activationDocumentCount!: number;
	/**
    주기적으로 갱신되는 마지막 관측 document count. 전체 소실(0)뿐 아니라 일부 유실도
    이 값 대비 급격한 감소로 탐지한다.
    */
	@prop({type: Number, default: null}) lastObservedDocumentCount!: Nullable<number>;
	@prop({type: [{partition: Number, offset: String}], default: []})
	baseOffsets!: ProjectionDatasetPartitionOffset[];

	@prop({type: [{partition: Number, offset: String}], default: []})
	targetOffsets!: ProjectionDatasetPartitionOffset[];
}

export const projectionDatasetSchema = SchemaFactory.createForClass(ProjectionDataset)
	.index({stream: 1}, {unique: true});
