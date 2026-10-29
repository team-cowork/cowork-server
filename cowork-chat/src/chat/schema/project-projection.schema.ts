import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

/**
`project.event.v2`의 중복·역순 처리를 위한 프로젝트 lifecycle 읽기 모델.
*/
@schema({timestamps: true, versionKey: false})
export class ProjectProjection {
	@prop({required: true}) projectId!: number;
	@prop({required: true}) teamId!: number;
	@prop({type: Number, default: null}) createdBy!: Nullable<number>;
	@prop({required: true, default: ''}) name!: string;
	@prop({type: String, default: null}) description!: Nullable<string>;
	@prop({required: true, default: ''}) status!: string;
	@prop({required: true, default: 0}) position!: number;
	@prop({required: true, default: false}) deleted!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
}

export const projectProjectionSchema = SchemaFactory.createForClass(ProjectProjection)
	.index({projectId: 1}, {unique: true})
	.index({teamId: 1, position: 1, projectId: 1});
