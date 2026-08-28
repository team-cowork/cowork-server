import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument } from 'mongoose';

export type ChannelProjectionDocument = HydratedDocument<ChannelProjection>;

/** `channel.event`로 동기화되는 채널 읽기 모델. */
@Schema({ timestamps: true, versionKey: false })
export class ChannelProjection {
    @Prop({ required: true }) channelId!: number;
    @Prop({ type: Number, default: null }) teamId!: number | null;
    @Prop({ type: Number, default: null }) projectId!: number | null;
    @Prop({ required: true, default: '' }) name!: string;
    @Prop({ required: true, default: '' }) type!: string;
    @Prop({ required: true, default: '' }) viewType!: string;
    @Prop({ type: String, default: null }) description!: string | null;
    @Prop({ required: true, default: false }) isPrivate!: boolean;
    @Prop({ required: true, default: 0 }) position!: number;
    @Prop({ required: true, default: false }) deleted!: boolean;
    @Prop({ required: true, type: Date }) sourceOccurredAt!: Date;
    @Prop({ required: true }) sourceVersion!: bigint;
}

export const ChannelProjectionSchema = SchemaFactory.createForClass(ChannelProjection);
ChannelProjectionSchema.index({ channelId: 1 }, { unique: true });
ChannelProjectionSchema.index({ teamId: 1, position: 1, channelId: 1 });
