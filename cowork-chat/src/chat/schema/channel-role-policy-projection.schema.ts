import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument } from 'mongoose';

export type ChannelRolePolicyProjectionDocument = HydratedDocument<ChannelRolePolicyProjection>;

/** `preference.channel-role-policy.changed`의 role×channel 정책 projection. */
@Schema({ timestamps: true, versionKey: false })
export class ChannelRolePolicyProjection {
    @Prop({ required: true }) teamId!: number;
    @Prop({ required: true }) channelId!: number;
    @Prop({ required: true }) roleId!: number;
    @Prop({ type: Boolean, default: null }) messageRead!: boolean | null;
    @Prop({ required: true, default: false }) deleted!: boolean;
    @Prop({ required: true, type: Date }) sourceOccurredAt!: Date;
    @Prop({ required: true }) sourceVersion!: bigint;
}

export const ChannelRolePolicyProjectionSchema = SchemaFactory.createForClass(ChannelRolePolicyProjection);
ChannelRolePolicyProjectionSchema.index({ teamId: 1, channelId: 1, roleId: 1 }, { unique: true });
ChannelRolePolicyProjectionSchema.index({ roleId: 1, channelId: 1, deleted: 1 });
