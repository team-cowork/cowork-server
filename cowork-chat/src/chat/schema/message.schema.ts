import { Prop, Schema, SchemaFactory } from '@nestjs/mongoose';
import { HydratedDocument } from 'mongoose';

export type MessageDocument = HydratedDocument<Message>;

@Schema({ timestamps: true })
export class Message {
    @Prop({ required: true }) teamId!: number;
    @Prop({ type: Number, required: false, default: null }) projectId!: number | null;
    @Prop({ required: true }) channelId!: number;
    @Prop({ required: true }) authorId!: number;
    @Prop({ required: true }) content!: string;
}

export const MessageSchema = SchemaFactory.createForClass(Message);
