import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {WebhookProjection} from '../schema/webhook-projection.schema';
import {type WebhookState} from '../webhook/webhook-policy';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    projectionSourceVersion,
    projectionTimestampFields,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

@Injectable()
export class WebhookProjectionRepository {
    constructor(@InjectModel(WebhookProjection.name) private readonly model: Model<WebhookProjection>) {}

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

    async findByExecutionId(executionId: string): Promise<WebhookState | null> {
        return this.model.findOne({executionId}).lean<WebhookState | null>();
    }
}
