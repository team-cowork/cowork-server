import {Prop, Schema, SchemaFactory} from '@nestjs/mongoose';

@Schema({timestamps: true, versionKey: false})
export class WebhookProjection {
    @Prop({required: true}) webhookId!: number;
    @Prop({required: true}) channelId!: number;
    @Prop({required: true}) name!: string;
    @Prop({type: String, default: null}) avatarUrl!: string | null;
    @Prop({required: true}) isSecure!: boolean;
    @Prop({type: String, default: null}) secretHash!: string | null;
    @Prop({required: true}) isActive!: boolean;
    @Prop({required: true}) deleted!: boolean;
    @Prop({required: true, type: Date}) sourceOccurredAt!: Date;
    @Prop({required: true}) sourceVersion!: bigint;
}

export const WebhookProjectionSchema = SchemaFactory.createForClass(WebhookProjection);
WebhookProjectionSchema.index({webhookId: 1}, {unique: true});
