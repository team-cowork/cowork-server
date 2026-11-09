import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

/**
Effective permissions published by Preference; this is not a project membership.
*/
@schema({timestamps: true, versionKey: false})
export class ProjectPermissionProjection {
	@prop({required: true}) projectId!: number;
	@prop({required: true}) userId!: number;
	@prop({required: true, default: false}) read!: boolean;
	@prop({required: true, default: false}) write!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
	@prop({required: true}) sourceMembershipVersion!: bigint;
	@prop({type: BigInt}) sourceAuthorizationVersion!: Nullable<bigint>;
}

export const projectPermissionProjectionSchema = SchemaFactory.createForClass(ProjectPermissionProjection)
	.index({projectId: 1, userId: 1}, {unique: true});
