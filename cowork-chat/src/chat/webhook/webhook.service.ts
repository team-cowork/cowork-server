import {
    ForbiddenException,
    Injectable,
    Logger,
    NotFoundException,
    ServiceUnavailableException,
} from '@nestjs/common';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service';
import {ChannelProjectionRepository, toMessageBroadcastPayload} from '../repository';
import {WebhookProjectionRepository} from '../repository/webhook-projection.repository';
import {ChannelMessageReadAccessService, UnreadCounterService, resolveMessageScope} from '../service';
import {ChatService} from '../chat.service';
import {WebhookMessageRepository} from './webhook-message.repository';
import {WebhookRateLimiter} from './webhook-rate-limiter';
import {authenticateWebhook, webhookDeliveryKey, webhookRequestHash} from './webhook-policy';

@Injectable()
export class WebhookService {
    private readonly logger = new Logger(WebhookService.name);

    constructor(
        private readonly webhooks: WebhookProjectionRepository,
        private readonly channels: ChannelProjectionRepository,
        private readonly readiness: ProjectionReadinessService,
        private readonly limiter: WebhookRateLimiter,
        private readonly messages: WebhookMessageRepository,
        private readonly chat: ChatService,
        private readonly readAccess: ChannelMessageReadAccessService,
        private readonly unread: UnreadCounterService,
    ) {}

    async execute(webhookId: number, input: {content: string; secret?: string; clientIp?: string; idempotencyKey?: string}) {
        if (!this.readiness.areReady(['webhook', 'channel', 'teamMember', 'teamRole', 'channelRolePolicy'])) {
            throw new ServiceUnavailableException('웹훅 정보가 아직 동기화되지 않았습니다.');
        }

        const webhook = await this.webhooks.findById(webhookId);
        if (!webhook || webhook.deleted) {
            throw new NotFoundException('웹훅을 찾을 수 없습니다.');
        }

        authenticateWebhook(webhook, input.secret);
        if (!webhook.isActive) {
            throw new ForbiddenException('비활성화된 웹훅입니다.');
        }

        const channel = await this.channels.findById(webhook.channelId);
        const scope = channel && resolveMessageScope(channel);
        if (channel?.type !== 'TEXT' || scope?.teamId == null) {
            throw new NotFoundException('웹훅 채널을 찾을 수 없습니다.');
        }

        const deliveryKey = webhookDeliveryKey(input.idempotencyKey);
        await this.limiter.acquire(webhookId, webhook.isSecure, input.clientIp);
        const result = await this.messages.save({
            webhook,
            teamId: scope.teamId,
            projectId: scope.projectId,
            content: input.content,
            deliveryKey,
            requestHash: webhookRequestHash(input.content),
        });
        const response = toMessageBroadcastPayload(result.message);
        if (result.created) {
            // HTTP success follows durable storage; failed realtime delivery is reconciled by message reads.
            const delivery = await Promise.allSettled([
                this.chat.emitToReadableChannelUsers(webhook.channelId, 'message', response),
                this.readAccess.findReadableUsersByChannel([webhook.channelId]).then(async users =>
                    this.unread.incrementIfPresent(webhook.channelId, users.get(webhook.channelId) ?? [])),
            ]);
            if (delivery.some(outcome => outcome.status === 'rejected')) {
                this.logger.warn(`Failed to deliver persisted webhook message [messageId=${result.message._id.toString()}]`);
            }
        }

        return response;
    }
}
