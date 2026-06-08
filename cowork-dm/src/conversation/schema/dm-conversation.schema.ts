import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument, Types } from 'mongoose';

export type DmConversationDocument = HydratedDocument<DmConversation>;

@Schema({ _id: false })
class Participant {
    @Prop({ required: true }) userId!: number;

    /** false = 목록에 노출, true = 숨김(Discord의 "닫기"). 상대방 메시지 수신 시 자동으로 false로 복구 */
    @Prop({ default: false }) isHidden!: boolean;

    @Prop({ type: Types.ObjectId, default: null }) lastReadMessageId!: Types.ObjectId | null;

    @Prop({ default: 0 }) unreadCount!: number;
}

const ParticipantSchema = SchemaFactory.createForClass(Participant);

@Schema({ timestamps: true, versionKey: false })
export class DmConversation {
    createdAt!: Date;
    updatedAt!: Date;

    /** 참여자 목록. 현재 1:1 = 2명, 그룹 DM 확장 시 n명 */
    @Prop({ type: [ParticipantSchema], required: true })
    participants!: Participant[];

    @Prop({ type: Types.ObjectId, default: null }) lastMessageId!: Types.ObjectId | null;

    @Prop({ type: Date, default: null }) lastMessageAt!: Date | null;
}

export const DmConversationSchema = SchemaFactory.createForClass(DmConversation);

DmConversationSchema.index({ 'participants.userId': 1 });
DmConversationSchema.index({ 'participants.userId': 1, lastMessageAt: -1 });
