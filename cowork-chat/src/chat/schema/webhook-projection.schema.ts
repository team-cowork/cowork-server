import {Prop as prop, Schema as schema, SchemaFactory} from '@nestjs/mongoose';
import {type Nullable} from '../../common/type/nullable.js';

@schema({timestamps: true, versionKey: false})
export class WebhookProjection {
	@prop({required: true}) webhookId!: number;
	@prop({required: true}) executionId!: string;
	@prop({required: true}) channelId!: number;
	@prop({required: true}) name!: string;
	@prop({type: String, default: null}) avatarUrl!: Nullable<string>;
	@prop({required: true}) isSecure!: boolean;
	@prop({type: String, default: null}) secretHash!: Nullable<string>;
	@prop({required: true}) isActive!: boolean;
	@prop({required: true}) deleted!: boolean;
	@prop({required: true, type: Date}) sourceOccurredAt!: Date;
	@prop({required: true}) sourceVersion!: bigint;
}

export const webhookProjectionSchema = SchemaFactory.createForClass(WebhookProjection)
	.index({webhookId: 1}, {unique: true})
	.index({executionId: 1}, {unique: true, partialFilterExpression: {executionId: {$type: 'string'}}});
