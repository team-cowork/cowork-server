import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument } from 'mongoose';

export type ChannelMemberDocument = HydratedDocument<ChannelMember>;

@Schema({ timestamps: true })
export class ChannelMember {
    @Prop({ required: true }) channelId!: number;
    @Prop({ required: true }) userId!: number;
    @Prop({ default: 'MEMBER' }) role!: string;
}

export const ChannelMemberSchema = SchemaFactory.createForClass(ChannelMember);

ChannelMemberSchema.index({ channelId: 1, userId: 1 }, { unique: true });
