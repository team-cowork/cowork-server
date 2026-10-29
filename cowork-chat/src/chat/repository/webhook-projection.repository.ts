import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {WebhookProjection} from '../schema/webhook-projection.schema.js';
import {type WebhookState} from '../webhook/webhook-policy.js';
import {type Nullable} from '../../common/type/nullable.js';
import {
	activeProjectionCondition,
	deletedProjectionCondition,
	projectionSourceVersion,
	projectionTimestampFields,
	PROJECTION_EPOCH,
} from './versioned-projection.util.js';

@injectable()
export class WebhookProjectionRepository {
	constructor(@injectModel(WebhookProjection.name) private readonly model: Model<WebhookProjection>) {}

	async upsert(input: WebhookState & {occurredAt: Date; sourceVersion: mongo.Long}): Promise<void> {
		const apply = input.deleted
			? deletedProjectionCondition(input.sourceVersion)
			: activeProjectionCondition(input.sourceVersion);
		const fields = Object.fromEntries(
			['executionId', 'channelId', 'name', 'avatarUrl', 'isSecure', 'secretHash', 'isActive', 'deleted'].map(key => [key, {
				$cond: [apply, {$literal: input[key as keyof WebhookState]}, `$${key}`],
			}]),
		);
		await this.model.updateOne(
			{webhookId: input.webhookId},
			[{
				$set: {
					webhookId: input.webhookId,
					...fields,
					sourceOccurredAt: {$cond: [apply, input.occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}]},
					sourceVersion: {$cond: [apply, input.sourceVersion, projectionSourceVersion]},
					...projectionTimestampFields(apply, input.occurredAt),
				},
			}],
			{upsert: true, timestamps: false, updatePipeline: true},
		);
	}

	async findByExecutionId(executionId: string): Promise<Nullable<WebhookState>> {
		return this.model.findOne({executionId}).lean<Nullable<WebhookState>>();
	}
}
