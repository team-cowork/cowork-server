import {Prop, Schema, SchemaFactory} from '@nestjs/mongoose';
import {Types} from 'mongoose';

/**
DM participants only. Team-channel permissions never create documents here.
*/
@Schema({timestamps: true, versionKey: false})
export class DmParticipant {
    @Prop({required: true}) channelId!: number;
    @Prop({required: true}) userId!: number;
    @Prop({type: Number, default: null}) teamId!: number | null;
    @Prop({default: 'DM'}) channelType!: string;
    @Prop({default: false}) isHidden!: boolean;
    @Prop({type: Types.ObjectId, default: null}) lastReadMessageId!: Types.ObjectId | null;
    @Prop({default: 'MEMBER'}) role!: string;
    @Prop({required: true, default: false}) deleted!: boolean;
    @Prop({required: true, type: Date}) sourceOccurredAt!: Date;
    @Prop({required: true}) sourceVersion!: bigint;
}

export const DmParticipantSchema = SchemaFactory.createForClass(DmParticipant);
DmParticipantSchema.index({channelId: 1, userId: 1}, {unique: true});
DmParticipantSchema.index({userId: 1, channelType: 1});
