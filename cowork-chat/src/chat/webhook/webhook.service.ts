import {
	ForbiddenException,
	Injectable as injectable,
	Logger,
	NotFoundException,
	ServiceUnavailableException,
	Inject as inject,
} from '@nestjs/common';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ChannelProjectionRepository, toMessageBroadcastPayload} from '../repository/index.js';
import {WebhookProjectionRepository} from '../repository/webhook-projection.repository.js';
import {ChannelMessageReadAccessService, UnreadCounterService, resolveMessageScope} from '../service/index.js';
import {ChatService} from '../chat.service.js';
import {WebhookMessageRepository} from './webhook-message.repository.js';
import {WebhookRateLimiter} from './webhook-rate-limiter.js';
import {
	authenticateWebhook,
	normalizeWebhookExecutionId,
	webhookDeliveryKey,
	webhookRequestHash,
} from './webhook-policy.js';

@injectable()
export class WebhookService {
	private readonly logger = new Logger(WebhookService.name);

	@inject()
	private readonly webhooks!: WebhookProjectionRepository;

	@inject()
	private readonly channels!: ChannelProjectionRepository;

	@inject()
	private readonly readiness!: ProjectionReadinessService;

	@inject()
	private readonly limiter!: WebhookRateLimiter;

	@inject()
	private readonly messages!: WebhookMessageRepository;

	@inject()
	private readonly chat!: ChatService;

	@inject()
	private readonly readAccess!: ChannelMessageReadAccessService;

	@inject()
	private readonly unread!: UnreadCounterService;

	async execute(executionId: string, input: {content: string; secret?: string; idempotencyKey?: string}) {
		if (!this.readiness.areReady(['webhook', 'channel', 'teamMember', 'teamRole', 'channelRolePolicy'])) {
			throw new ServiceUnavailableException('웹훅 정보가 아직 동기화되지 않았습니다.');
		}

		const canonicalExecutionId = normalizeWebhookExecutionId(executionId);
		const webhook = await this.webhooks.findByExecutionId(canonicalExecutionId);
		if (!webhook || webhook.deleted) {
			throw new NotFoundException('웹훅을 찾을 수 없습니다.');
		}

		authenticateWebhook(webhook, input.secret);
		if (!webhook.isActive) {
			throw new ForbiddenException('비활성화된 웹훅입니다.');
		}

		const channel = await this.channels.findById(webhook.channelId);
		const scope = channel && resolveMessageScope(channel);
		if (channel?.type !== 'TEXT' || (scope?.teamId === null || scope?.teamId === undefined)) {
			throw new NotFoundException('웹훅 채널을 찾을 수 없습니다.');
		}

		const deliveryKey = webhookDeliveryKey(input.idempotencyKey);
		const messageInput = {
			webhook,
			teamId: scope.teamId,
			projectId: scope.projectId,
			content: input.content,
			deliveryKey,
			requestHash: webhookRequestHash(input.content),
		};
		const existing = await this.messages.findDelivery(messageInput);
		if (existing) {
			return toMessageBroadcastPayload(existing);
		}

		await this.limiter.acquire(webhook.webhookId, webhook.isSecure);
		const result = await this.messages.save(messageInput);
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
