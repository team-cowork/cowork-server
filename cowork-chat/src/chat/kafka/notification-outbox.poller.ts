import {
	Injectable as injectable,
	OnModuleDestroy,
	OnModuleInit,
	Logger,
	Inject as inject,
} from '@nestjs/common';
import {Types} from 'mongoose';
import {Message} from '../schema/index.js';
import {MessageRepository, NotificationMessage} from '../repository/index.js';
import {UnreadCounterService, ChannelMessageReadAccessService} from '../service/index.js';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {type Nullable} from '../../common/type/nullable.js';
import {NotificationTriggerProducer} from './notification-trigger.producer.js';

const POLL_INTERVAL_MS = 5000;
const BATCH_SIZE = 10;
/**
한 틱에서 가득 찬 배치를 이어 가져올 수 있는 시간. 폴링 간격(5초)보다 짧아야 다음 틱과 겹치지 않는다.
예산은 배치 사이에서만 확인하므로 마지막 배치는 예산을 조금 넘을 수 있다.
*/
const DRAIN_TIME_BUDGET_MS = 4000;
/**
재시도 한도. 백오프 상한과 함께 약 20분 동안 이어지는 broker 장애까지 견딘다
*/
const MAX_RETRY = 10;
const RETRY_BASE_DELAY_MS = 5000;
const RETRY_MAX_DELAY_MS = 5 * 60 * 1000;
/**
이 시간(2분) 이상 PROCESSING에 머문 메시지를 PENDING으로 회수한다
*/
const PROCESSING_STALE_THRESHOLD_MS = 2 * 60 * 1000;
/**
ReclaimStaleProcessing 실행 최소 간격 — 5초마다 updateMany를 보내지 않도록 스로틀
*/
const RECLAIM_INTERVAL_MS = 60_000;

/**
 실패 횟수에 따른 지수 백오프 시각 (5초, 10초, 20초 … 상한 5분)
 */
export function nextAttemptAt(retryCount: number, now = Date.now()): Date {
	const delay = Math.min(RETRY_BASE_DELAY_MS * (2 ** Math.min(retryCount - 1, 10)), RETRY_MAX_DELAY_MS);
	return new Date(now + delay);
}

/**
 알림 발송 대기 중인 메시지를 주기적으로 조회하여 알림 트리거를 발행하는 폴러.

 Outbox 패턴** 구현체로, 5초 간격으로 PENDING 상태의 메시지를 최대 10개씩 배치 처리한다.
 처리 실패 시 지수 백오프로 다음 시도 시각을 미루며 최대 {@link MAX_RETRY}회까지 재시도하고, 초과 시 FAILED 상태로 전환한다.

 배치 내 중복 DB 조회를 줄이기 위해 `recipientCache`와 `parentCache`를 활용한다.
 */
@injectable()
export class NotificationOutboxPoller implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(NotificationOutboxPoller.name);
	private timer?: ReturnType<typeof setInterval>;
	private isPolling = false;
	private lastReclaimTime = 0;

	@inject()
	private readonly messageRepository!: MessageRepository;

	@inject()
	private readonly triggerProducer!: NotificationTriggerProducer;

	@inject()
	private readonly unreadCounterService!: UnreadCounterService;

	@inject()
	private readonly channelMessageReadAccess!: ChannelMessageReadAccessService;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	private async runPollCycle(): Promise<void> {
		if (!this.projectionReadiness.isReady() || this.isPolling) {
			return;
		}

		this.isPolling = true;
		try {
			await this.poll();
		} catch (error) {
			this.logger.error('Notification outbox polling failed', error);
		} finally {
			this.isPolling = false;
		}
	}

	/**
     한 번의 폴링 사이클을 실행한다.

     실행 순서:
     1. PENDING 상태 메시지를 최대 {@link BATCH_SIZE}(10)개 조회하며 PROCESSING으로 원자 전환한다.
     2. 배치 내 고유 `parentMessageId`를 한 번에 조회하여 `parentCache`를 사전 채운다.
     3. 각 메시지를 {@link processMessage}로 처리하고 성공 시 SENT로 전환한다.
     4. 처리 실패 시 재시도 횟수에 따라 PENDING 또는 FAILED로 전환한다.
     */
	private async poll(): Promise<void> {
		// 0단계: 크래시 등으로 stuck된 stale PROCESSING 메시지를 PENDING으로 회수 (1분 간격 스로틀)
		const now = Date.now();
		if (now - this.lastReclaimTime > RECLAIM_INTERVAL_MS) {
			this.lastReclaimTime = now;
			const reclaimed = await this.messageRepository.reclaimStaleProcessing(PROCESSING_STALE_THRESHOLD_MS);
			if (reclaimed > 0) {
				this.logger.warn(`Reclaimed ${reclaimed} stale PROCESSING message(s) back to PENDING`);
			}
		}

		await this.drainWhileFull(Date.now() + DRAIN_TIME_BUDGET_MS);
	}

	/**
     가득 찬 배치가 이어지면 시간 예산 안에서 계속 가져온다. 다음 틱까지 기다리면 백로그가 쌓인다.
     */
	private async drainWhileFull(deadline: number): Promise<void> {
		if (await this.processBatch() === BATCH_SIZE && Date.now() < deadline) {
			await this.drainWhileFull(deadline);
		}
	}

	/**
     PENDING 메시지를 한 배치 점유해 처리하고, 점유한 메시지 수를 반환한다.
     */
	private async processBatch(): Promise<number> {
		// 1단계: 배치 내 메시지 수집 (PENDING → PROCESSING 원자적 전환)
		const msgs = await this.messageRepository.findPendingAndMarkProcessing(BATCH_SIZE);
		if (msgs.length === 0) {
			return 0;
		}

		// 2단계: 배치 내 고유 channelId/parentMessageId를 한 번에 조회해 캐시 사전 채움
		const recipientCache = new Map<string, number[]>();
		const parentCache = new Map<string, Nullable<{authorId: Nullable<number>}>>();
		const parentRefs = new Map<string, {channelId: number; parentMessageId: Types.ObjectId}>();
		for (const message of msgs) {
			if (!message.parentMessageId) {
				continue;
			}

			const key = `${message.channelId}:${message.parentMessageId.toString()}`;
			parentRefs.set(key, {channelId: message.channelId, parentMessageId: message.parentMessageId});
		}

		if (parentRefs.size > 0) {
			const parentMap = await this.messageRepository.findParentAuthorsByChannel(parentRefs.values().toArray());
			for (const key of parentRefs.keys()) {
				parentCache.set(key, parentMap.get(key) ?? null);
			}
		}

		const channelIds = [...new Set(msgs.map(m => m.channelId))];
		const readableUsersByChannel = await this.channelMessageReadAccess.findReadableUsersByChannel(channelIds);
		for (const channelId of channelIds) {
			recipientCache.set(String(channelId), readableUsersByChannel.get(channelId) ?? []);
		}

		// 3단계: 각 메시지 처리 (메시지 간 의존관계 없어 병렬 처리)
		await Promise.all(msgs.map(async message => this.processMessageAndUpdateStatus(message, recipientCache, parentCache)));
		return msgs.length;
	}

	private async processMessageAndUpdateStatus(
		message: NotificationMessage,
		recipientCache: Map<string, number[]>,
		parentCache: Map<string, Nullable<{authorId: Nullable<number>}>>,
	): Promise<void> {
		try {
			await this.processMessage(message, recipientCache, parentCache);
			await this.messageRepository.updateNotificationStatus(message._id, message.notificationClaimId!, 'SENT');
		} catch (error) {
			const retryCount = (message.notificationRetryCount ?? 0) + 1;
			const nextStatus = retryCount >= MAX_RETRY ? 'FAILED' : 'PENDING';
			this.logger.error(`Outbox processing failed (messageId: ${message._id.toString()}, retry: ${retryCount}/${MAX_RETRY}), transitioning to ${nextStatus}`, error);
			await this.messageRepository.updateNotificationStatus(message._id, message.notificationClaimId!, nextStatus, {
				retryCount,
				...(nextStatus === 'PENDING' && {nextAttemptAt: nextAttemptAt(retryCount)}),
			});
		}
	}

	/**
     단일 메시지에 대한 알림 트리거를 생성하고 발행한다.

     수신자 분류 전략**:
     - `targetUserIds`: 읽기 권한이 있는 사용자 전체에서 메시지 작성자를 제외한 모든 사용자.
     - `forcedUserIds`: 멘션된 사용자 + 부모 메시지 작성자. 알림 수신 설정과 무관하게 항상 알림을 받는다.
     단, 메시지 작성자 본인이거나 읽기 권한이 있는 사용자가 아닌 경우는 제외된다.

     캐시 활용**:
     - `recipientCache`: 배치 시작 시 {@link poll}에서 일괄 채워지며, 읽기 권한이 있는 사용자 조회에 사용된다.
     - `parentCache`: 배치 시작 시 {@link poll}에서 일괄 채워지며, 부모 메시지 작성자 조회에 사용된다.

     @param message - 처리할 알림 대상 메시지
     @param recipientCache - 채널 ID를 키로 하는 읽기 권한이 있는 사용자 목록 캐시 (배치 범위 내 공유)
     @param parentCache - 부모 메시지 ID를 키로 하는 작성자 정보 캐시 (배치 범위 내 공유)
     @throws {Error} 읽기 권한이 있는 사용자 조회 또는 알림 트리거 발행 실패 시
     */
	private async processMessage(
		message: Message & {_id: Types.ObjectId; createdAt: Date},
		recipientCache: Map<string, number[]>,
		parentCache: Map<string, Nullable<{authorId: Nullable<number>}>>,
	): Promise<void> {
		if (message.authorId === null) {
			return;
		}

		const recipientIds = recipientCache.get(String(message.channelId)) ?? [];
		const recipientIdSet = new Set(recipientIds);

		const targetUserIds = [...recipientIdSet].filter(id => id !== message.authorId);

		if (message.notificationRetryCount === 0) {
			// Stale PROCESSING 회수는 재시도 횟수를 올리지 않으므로 메시지 ID로 (메시지, 수신자)당 한 번만 반영한다.
			await this.unreadCounterService.incrementIfPresent(message.channelId, targetUserIds, message._id.toString());
		}

		const forcedSet = new Set<number>();
		for (const mentionedId of message.mentions ?? []) {
			if (mentionedId !== message.authorId && recipientIdSet.has(mentionedId)) {
				forcedSet.add(mentionedId);
			}
		}

		if (message.parentMessageId) {
			const parent = parentCache.get(`${message.channelId}:${message.parentMessageId.toString()}`) ?? null;
			if (parent && parent.authorId !== null && parent.authorId !== message.authorId && recipientIdSet.has(parent.authorId)) {
				forcedSet.add(parent.authorId);
			}
		}

		await this.triggerProducer.send({
			// 메시지 자체의 안정적 식별자를 재사용한다. 이 폴러의 processMessageAndUpdateStatus가
			// 실패 시 같은 메시지를 다시 발행할 수 있으므로, 매번 새 id를 만들면 FCM 선택적 재시도가
			// 같은 논리적 알림을 서로 다른 이벤트로 취급해 중복 발송을 막지 못한다.
			eventId: message._id.toString(),
			type: 'CHAT_MESSAGE',
			targetUserIds,
			forcedUserIds: [...forcedSet],
			data: {
				channelId: message.channelId,
				teamId: message.teamId,
				authorId: message.authorId,
				content: message.content,
				occurredAt: message.createdAt.toISOString(),
			},
		});
	}

	/**
     모듈 초기화 시 {@link POLL_INTERVAL_MS}(5000ms) 간격으로 폴링 타이머를 시작한다.

     이전 폴링이 완료되지 않은 경우 (`isPolling === true`) 해당 인터벌은 건너뛴다.
     */
	onModuleInit() {
		this.timer = setInterval(() => {
			void this.runPollCycle();
		}, POLL_INTERVAL_MS);
		this.logger.log('Notification outbox poller started');
	}

	/**
     모듈 종료 시 폴링 타이머를 해제한다.
     */
	onModuleDestroy() {
		clearInterval(this.timer);
	}
}
