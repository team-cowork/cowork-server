import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument } from 'mongoose';

export type TeamMemberProjectionDocument = HydratedDocument<TeamMemberProjection>;

/** `team.member.event`로 동기화되는 팀 멤버십 읽기 모델. */
@Schema({ timestamps: true, versionKey: false })
export class TeamMemberProjection {
    @Prop({ required: true }) teamId!: number;
    @Prop({ required: true }) userId!: number;
    @Prop({ required: true, default: '' }) role!: string;
    @Prop({ required: true, default: '' }) teamName!: string;
    @Prop({ required: true, default: false }) deleted!: boolean;
    @Prop({ required: true, type: Date }) sourceOccurredAt!: Date;
    @Prop({ required: true }) sourceVersion!: bigint;
}

export const TeamMemberProjectionSchema = SchemaFactory.createForClass(TeamMemberProjection);
TeamMemberProjectionSchema.index({ teamId: 1, userId: 1 }, { unique: true });
TeamMemberProjectionSchema.index({ userId: 1, teamId: 1 });
