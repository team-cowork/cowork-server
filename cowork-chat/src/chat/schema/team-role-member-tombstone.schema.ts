import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument } from 'mongoose';

export type TeamRoleMemberTombstoneDocument = HydratedDocument<TeamRoleMemberTombstone>;

/** 이전 assignment 이벤트의 부활을 막는 member 단위 tombstone. */
@Schema({ timestamps: true, versionKey: false })
export class TeamRoleMemberTombstone {
    @Prop({ required: true }) teamId!: number;
    @Prop({ required: true }) accountId!: number;
    @Prop({ required: true, type: Date }) sourceOccurredAt!: Date;
    @Prop({ required: true }) sourceVersion!: bigint;
}

export const TeamRoleMemberTombstoneSchema = SchemaFactory.createForClass(TeamRoleMemberTombstone);
TeamRoleMemberTombstoneSchema.index({ teamId: 1, accountId: 1 }, { unique: true });
