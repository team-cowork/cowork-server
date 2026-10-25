import {GoneException, Injectable, OnModuleInit} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model} from 'mongoose';
import {Message, MessageDocument} from '../schema/message.schema';
import {isSearchIndexed} from '../search/message-index-scope';
import {requireSameWebhookRequest, WebhookState} from './webhook-policy';

type WebhookMessageInput = {
    webhook: WebhookState;
    teamId: number;
    projectId: number | null;
    content: string;
    deliveryKey?: string;
    requestHash: string;
};

@Injectable()
export class WebhookMessageRepository implements OnModuleInit {
    constructor(@InjectModel(Message.name) private readonly model: Model<Message>) {}

    async onModuleInit(): Promise<void> {
        // Do not accept deliveries before the durable deduplication index exists.
        await this.model.collection.createIndex(
            {'webhook.id': 1, webhookDeliveryKey: 1},
            {name: 'webhook_idempotency', unique: true, partialFilterExpression: {webhookDeliveryKey: {$type: 'string'}}},
        );
    }

    async save(input: WebhookMessageInput): Promise<{message: MessageDocument; created: boolean}> {
        const existing = await this.findDelivery(input);
        if (existing) {
            return {message: existing, created: false};
        }

        const isIndexed = isSearchIndexed({...input, type: 'TEXT'});
        try {
            const message = await this.model.create({
                teamId: input.teamId,
                projectId: input.projectId,
                channelId: input.webhook.channelId,
                authorId: null,
                webhook: {id: input.webhook.webhookId, name: input.webhook.name, avatarUrl: input.webhook.avatarUrl},
                content: input.content,
                type: 'TEXT',
                attachments: [],
                mentions: [],
                notificationStatus: 'SENT',
                ...(input.deliveryKey !== undefined && {webhookDeliveryKey: input.deliveryKey, webhookRequestHash: input.requestHash}),
                searchIndexStatus: isIndexed ? 'PENDING' : 'SKIPPED',
                searchIndexVersion: isIndexed ? 1 : 0,
                searchIndexNextAttemptAt: isIndexed ? new Date() : null,
            });
            return {message, created: true};
        } catch (error) {
            if (input.deliveryKey !== undefined && typeof error === 'object' && error !== null && 'code' in error && error.code === 11_000) {
                const duplicate = await this.findDelivery(input);
                if (duplicate) {
                    return {message: duplicate, created: false};
                }
            }

            throw error;
        }
    }

    private async findDelivery(input: WebhookMessageInput): Promise<MessageDocument | null> {
        if (input.deliveryKey === undefined) {
            return null;
        }

        const existing = await this.model.findOne({'webhook.id': input.webhook.webhookId, webhookDeliveryKey: input.deliveryKey});
        if (existing) {
            requireSameWebhookRequest(existing.webhookRequestHash, input.requestHash);
            if (existing.webhookDeleted === true || existing.searchIndexStatus === 'DELETING') {
                throw new GoneException('이미 삭제된 웹훅 메시지입니다.');
            }
        }

        return existing;
    }
}
