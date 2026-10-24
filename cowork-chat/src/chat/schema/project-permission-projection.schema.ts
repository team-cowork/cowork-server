import {Prop, Schema, SchemaFactory} from '@nestjs/mongoose';

/**
Effective permissions published by Preference; this is not a project membership.
*/
@Schema({timestamps: true, versionKey: false})
export class ProjectPermissionProjection {
    @Prop({required: true}) projectId!: number;
    @Prop({required: true}) userId!: number;
    @Prop({required: true, default: false}) read!: boolean;
    @Prop({required: true, default: false}) write!: boolean;
    @Prop({required: true, type: Date}) sourceOccurredAt!: Date;
    @Prop({required: true}) sourceVersion!: bigint;
    @Prop({required: true, default: 0n}) sourceMembershipVersion!: bigint;
}

export const ProjectPermissionProjectionSchema = SchemaFactory.createForClass(ProjectPermissionProjection);
ProjectPermissionProjectionSchema.index({projectId: 1, userId: 1}, {unique: true});
