import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument } from 'mongoose';

export type TeamRoleProjectionDocument = HydratedDocument<TeamRoleProjection>;

/** `preference.team-role.changed`의 role 정의 projection. */
@Schema({ timestamps: true, versionKey: false })
export class TeamRoleProjection {
    @Prop({ required: true }) teamId!: number;
    @Prop({ required: true }) roleId!: number;
    @Prop({ required: true, default: '' }) name!: string;
    @Prop({ required: true, default: 0 }) priority!: number;
    @Prop({ required: true, type: [String], default: [] }) permissions!: string[];
    @Prop({ required: true, default: false }) deleted!: boolean;
    @Prop({ required: true, type: Date }) sourceOccurredAt!: Date;
    @Prop({ required: true }) sourceVersion!: bigint;
}

export const TeamRoleProjectionSchema = SchemaFactory.createForClass(TeamRoleProjection);
TeamRoleProjectionSchema.index({ teamId: 1, roleId: 1 }, { unique: true });
TeamRoleProjectionSchema.index({ roleId: 1, deleted: 1 });
