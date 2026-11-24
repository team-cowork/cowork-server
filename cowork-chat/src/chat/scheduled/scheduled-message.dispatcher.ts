import {
	Inject as inject,
	Injectable as injectable,
	Logger,
	NotFoundException,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ObjectStorageService} from '../../storage/object-storage.service.js';
import {ChatService} from '../chat.service.js';
import {ChatMessageProducer} from '../kafka/chat-message.producer.js';
import {NotificationTriggerProducer} from '../kafka/notification-trigger.producer.js';
import {AttachmentDeletionRepository, ChannelProjectionRepository, MessageRepository} from '../repository/index.js';
import {
	ChannelMessageReadAccessService,
	SlowmodeService,
	TagSpamService,
	type MessageScope,
	type TagSpamReservation,
} from '../service/index.js';
import {
	decideDispatchOutcome,
	describeDispatchError,
	isDispatchExpired,
	type DispatchStage,
} from './scheduled-message.policy.js';
import {ScheduledMessageRepository, type ClaimedScheduledMessage} from './scheduled-message.repository.js';
import {type ScheduledMessageFailureReason} from './scheduled-message.schema.js';

const POLL_INTERVAL_MS = 10_000;
const BATCH_SIZE = 20;
/**
한 주기에서 가득 찬 배치를 이어 꺼낼 수 있는 시간. 정각에 예약이 몰려도 1분 안에 보내도록 이어 처리하되,
폴링 간격(10초)보다 짧게 잡아 다음 주기와 겹치지 않게 한다. 예산은 배치 사이에서만 확인한다.
*/
const DRAIN_TIME_BUDGET_MS = 8000;
/**
점유 후 이 시간이 지나면 끝나지 않은 발송이나 후처리를 다시 한다. 다시 발행해도 같은 `clientMessageId`라 한 번만 저장된다.
*/
const LEASE_MS = 2 * 60 * 1000;
/**
Consumer가 메시지를 저장하기 전에 첨부가 회수되지 않도록 발송한 기록을 이 시간 동안 남긴다.
*/
const SENT_RETENTION_MS = 24 * 60 * 60 * 1000;
const FAILED_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;

type ClaimBatch = (now: Date, leaseMs: number, batchSize: number) => Promise<ClaimedScheduledMessage[]>;

/**
 발송 시각이 된 예약 메시지를 일반 메시지와 같은 Kafka 경로로 발행하는 워커.

 발송할 때 채널 존재, 작성자의 권한, 채널 보관 여부, DM 차단을 다시 검사한다. 검사에 실패하면 예약을 FAILED로 바꾼다.
 발행 오류는 브로커에 기록되었을 수 있으므로 예약을 DISPATCHING으로 두고 다시 발행한다.
 실패하거나 취소한 예약은 후처리 단계에서 첨부를 회수 대상으로 넘기고, 실패면 `notification.trigger`로 작성자에게 알린다.
 후처리를 끝내야 완료 시각을 기록하므로, 중간에 실패하거나 프로세스가 죽어도 점유가 끝나면 다시 한다.
 */
@injectable()
export class ScheduledMessageDispatcher implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(ScheduledMessageDispatcher.name);
	private timer?: ReturnType<typeof setInterval>;
	private running = false;

	@inject()
	private readonly repository!: ScheduledMessageRepository;

	@inject()
	private readonly chatService!: ChatService;

	@inject()
	private readonly channelProjectionRepository!: ChannelProjectionRepository;

	@inject()
	private readonly readAccess!: ChannelMessageReadAccessService;

	@inject()
	private readonly slowmodeService!: SlowmodeService;

	@inject()
	private readonly tagSpamService!: TagSpamService;

	@inject()
	private readonly chatMessageProducer!: ChatMessageProducer;

	@inject()
	private readonly notificationProducer!: NotificationTriggerProducer;

	@inject()
	private readonly attachmentDeletions!: AttachmentDeletionRepository;

	@inject()
	private readonly objectStorageService!: ObjectStorageService;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	@inject()
	private readonly messages!: MessageRepository;

	private async publish(message: ClaimedScheduledMessage, scope: MessageScope): Promise<void> {
		const {channelId, authorId, clientMessageId} = message;
		const slowmodeReservation = await this.slowmodeService.reserve(channelId, scope.teamId, authorId, clientMessageId);
		let tagSpamReservation: TagSpamReservation | undefined;
		try {
			tagSpamReservation = await this.tagSpamService.reserve(scope.teamId, authorId, message.content, clientMessageId);
			await this.chatMessageProducer.sendMessage(channelId, {
				...scope,
				content: message.content,
				type: message.type,
				attachments: message.attachments,
				clientMessageId,
			}, authorId);
		} catch (error) {
			await Promise.all([
				this.slowmodeService.release(slowmodeReservation),
				this.tagSpamService.release(tagSpamReservation),
			]);
			throw error;
		}
	}

	private async markFailed(message: ClaimedScheduledMessage, reason: ScheduledMessageFailureReason, now: Date): Promise<void> {
		if (await this.repository.markFailed(message, reason, now)) {
			this.logger.log(`Scheduled message was not sent [scheduledMessageId=${message._id.toHexString()}, reason=${reason}]`);
		}
	}

	private async handleDispatchError(message: ClaimedScheduledMessage, stage: DispatchStage, error: unknown, now: Date): Promise<void> {
		const outcome = decideDispatchOutcome(stage, error, message, now);
		if (outcome.kind === 'FAIL') {
			await this.markFailed(message, outcome.reason, now);
			return;
		}

		this.logger.warn(`Scheduled message dispatch failed, will retry [scheduledMessageId=${message._id.toHexString()}, attempts=${message.attempts + 1}]`, error);
		const retry = [message._id, message.claimId, outcome.nextAttemptAt, describeDispatchError(error)] as const;
		await (stage === 'PUBLISH' ? this.repository.deferPublish(...retry) : this.repository.markRetry(...retry));
	}

	private async dispatch(message: ClaimedScheduledMessage, now: Date): Promise<void> {
		if (isDispatchExpired(message.scheduledAt, now)) {
			await this.markFailed(message, 'DISPATCH_TIMEOUT', now);
			return;
		}

		const {channelId, authorId} = message;
		let stage: DispatchStage = 'TARGET';
		try {
			// 권한 판정은 삭제된 채널을 권한 없음으로 거부하므로, 실패 사유를 구분하려고 채널을 먼저 확인한다.
			if (!await this.channelProjectionRepository.findById(channelId)) {
				throw new NotFoundException('채널을 찾을 수 없습니다');
			}

			stage = 'MESSAGE_SEND';
			await this.readAccess.requirePermission(channelId, authorId, 'message_send');
			if (message.attachments.length > 0) {
				stage = 'FILE_ATTACH';
				await this.readAccess.requirePermission(channelId, authorId, 'file_attach');
			}

			stage = 'TARGET';
			const scope = await this.chatService.requireSendable(
				{channelId, userId: authorId},
				{attachmentCount: message.attachments.length, revealDm: true},
			);
			stage = 'PUBLISH';
			await this.publish(message, scope);
		} catch (error) {
			await this.handleDispatchError(message, stage, error, now);
			return;
		}

		await this.repository.markSent(message._id, message.claimId, new Date(now.getTime() + SENT_RETENTION_MS));
	}

	/**
     보내지 않을 예약의 첨부를 회수 대상으로 넘긴다. 업로드 기록이 이미 만료되었을 수 있으므로 삭제 의도를 남기고,
     정리 워커는 이 ID의 메시지가 없으므로 객체를 지운다. 같은 기록을 반복해도 결과가 같다.
     */
	private async releaseAttachments(message: ClaimedScheduledMessage): Promise<void> {
		const objectKeys = message.attachments.map(attachment => attachment.objectKey);
		if (objectKeys.length === 0) {
			return;
		}

		await this.attachmentDeletions.record({messageId: message._id.toHexString(), channelId: message.channelId, objectKeys});
		await this.objectStorageService.releaseUploads(objectKeys);
	}

	private async notifyFailure(message: ClaimedScheduledMessage, now: Date): Promise<void> {
		await this.notificationProducer.send({
			eventId: `scheduled-message-failed:${message._id.toHexString()}`,
			type: 'SCHEDULED_MESSAGE_FAILED',
			targetUserIds: [],
			forcedUserIds: [message.authorId],
			data: {
				channelId: message.channelId,
				teamId: message.teamId,
				authorId: message.authorId,
				content: message.content,
				reason: message.failureReason ?? undefined,
				occurredAt: (message.failedAt ?? now).toISOString(),
			},
		});
	}

	private async finalize(message: ClaimedScheduledMessage, now: Date): Promise<void> {
		try {
			// 발행 결과를 모른 채 실패했거나, 발행 후 SENT 기록 전에 실패한 예약은 저장되었을 수 있다. 저장된 메시지의 첨부를 지우지 않는다.
			if (await this.messages.existsByClientMessageId(message.clientMessageId)) {
				await this.repository.markDelivered(message, new Date(now.getTime() + SENT_RETENTION_MS));
				return;
			}

			await this.releaseAttachments(message);
			if (message.status === 'FAILED') {
				await this.notifyFailure(message, now);
			}

			// 실패 기록은 작성자가 목록에서 사유를 보도록 남기고, 취소 기록은 바로 지운다.
			const retentionMs = message.status === 'FAILED' ? FAILED_RETENTION_MS : 0;
			await this.repository.markFinalized(message, now, new Date(now.getTime() + retentionMs));
		} catch (error) {
			this.logger.warn(`Scheduled message finalization failed, will retry [scheduledMessageId=${message._id.toHexString()}]`, error);
		}
	}

	/**
     배치가 가득 차 있는 동안 시간 예산 안에서 이어 꺼내 처리한다. 다음 주기까지 기다리면 정각에 몰린 예약이 늦어진다.
     처리 시각은 배치마다 지난 시간만큼 앞당기고, 드레인 중 readiness가 닫히면 권한을 판정할 수 없으므로 멈춘다.
     */
	private async drain(
		claim: ClaimBatch,
		handle: (message: ClaimedScheduledMessage, now: Date) => Promise<void>,
		cycle: {now: Date; startedAt: number},
	): Promise<void> {
		const now = new Date(cycle.now.getTime() + (Date.now() - cycle.startedAt));
		const batch = await claim(now, LEASE_MS, BATCH_SIZE);
		await Promise.all(batch.map(async message => handle(message, now)));
		if (batch.length === BATCH_SIZE && Date.now() - cycle.startedAt < DRAIN_TIME_BUDGET_MS && this.projectionReadiness.isReady()) {
			await this.drain(claim, handle, cycle);
		}
	}

	async runCycle(now = new Date()): Promise<void> {
		if (this.running || !this.projectionReadiness.isReady()) {
			return;
		}

		this.running = true;
		const cycle = {now, startedAt: Date.now()};
		try {
			await this.drain(async (...arguments_) => this.repository.claimDue(...arguments_), async (message, at) => this.dispatch(message, at), cycle);
			await this.drain(async (...arguments_) => this.repository.claimUnfinalized(...arguments_), async (message, at) => this.finalize(message, at), cycle);
		} catch (error) {
			this.logger.error('Scheduled message dispatch cycle failed', error);
		} finally {
			this.running = false;
		}
	}

	onModuleInit(): void {
		this.timer = setInterval(() => {
			void this.runCycle();
		}, POLL_INTERVAL_MS);
		this.logger.log('Scheduled message dispatcher started');
	}

	onModuleDestroy(): void {
		clearInterval(this.timer);
	}
}
