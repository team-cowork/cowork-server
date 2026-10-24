import {Prop, Schema, SchemaFactory} from '@nestjs/mongoose';
import {Types} from 'mongoose';

@Schema({versionKey: false})
export class ChannelReadState {
    @Prop({required: true}) channelId!: number;
    @Prop({required: true}) userId!: number;
    @Prop({required: true, type: Types.ObjectId}) lastReadMessageId!: Types.ObjectId;
}

export const ChannelReadStateSchema = SchemaFactory.createForClass(ChannelReadState);
ChannelReadStateSchema.index({channelId: 1, userId: 1}, {unique: true});
