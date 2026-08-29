import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument } from 'mongoose';

export type TeamRoleAssignmentProjectionDocument = HydratedDocument<TeamRoleAssignmentProjection>;

/** `preference.team-role.changed`의 account↔role assignment projection. */
@Schema({ timestamps: true, versionKey: false })
export class TeamRoleAssignmentProjection {
    @Prop({ required: true }) teamId!: number;
    @Prop({ required: true }) accountId!: number;
    @Prop({ required: true }) roleId!: number;
    @Prop({ required: true, default: false }) deleted!: boolean;
    @Prop({ required: true, type: Date }) sourceOccurredAt!: Date;
    @Prop({ required: true }) sourceVersion!: bigint;
}

export const TeamRoleAssignmentProjectionSchema = SchemaFactory.createForClass(TeamRoleAssignmentProjection);
TeamRoleAssignmentProjectionSchema.index({ teamId: 1, accountId: 1, roleId: 1 }, { unique: true });
TeamRoleAssignmentProjectionSchema.index({ roleId: 1, deleted: 1 });
