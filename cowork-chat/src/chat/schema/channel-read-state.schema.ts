import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {Types} from 'mongoose';

@schema({versionKey: false})
export class ChannelReadState {
	@prop({required: true}) channelId!: number;
	@prop({required: true}) userId!: number;
	@prop({required: true, type: Types.ObjectId}) lastReadMessageId!: Types.ObjectId;
}

export const channelReadStateSchema = SchemaFactory.createForClass(ChannelReadState)
	.index({channelId: 1, userId: 1}, {unique: true});
