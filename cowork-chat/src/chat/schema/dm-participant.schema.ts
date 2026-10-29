import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {Types} from 'mongoose';
import {type Nullable} from '../../common/type/nullable.js';

/**
DM participants only. Team-channel permissions never create documents here.
*/
@schema({timestamps: true, versionKey: false})
export class DmParticipant {
	@prop({required: true}) channelId!: number;
	@prop({required: true}) userId!: number;
	@prop({type: Number, default: null}) teamId!: Nullable<number>;
	@prop({default: 'DM'}) channelType!: string;
	@prop({default: false}) isHidden!: boolean;
	@prop({type: Types.ObjectId, default: null}) lastReadMessageId!: Nullable<Types.ObjectId>;
	@prop({default: 'MEMBER'}) role!: string;
	@prop({required: true, default: false}) deleted!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
}

export const dmParticipantSchema = SchemaFactory.createForClass(DmParticipant)
	.index({channelId: 1, userId: 1}, {unique: true})
	.index({userId: 1, channelType: 1});
