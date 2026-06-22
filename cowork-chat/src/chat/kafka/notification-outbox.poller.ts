import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { Types } from 'mongoose';
import { Message } from '../schema/message.schema';
import { ChannelMember } from '../schema/channel-member.schema';
import { NotificationTriggerProducer } from './notification-trigger.producer';
import { MessageRepository, NotificationMessage } from '../repository/message.repository';
import { ChannelMemberRepository } from '../repository/channel-member.repository';

const POLL_INTERVAL_MS = 5_000;
const BATCH_SIZE = 10;
const MAX_RETRY = 3;
/** 이 시간(2분) 이상 PROCESSING에 머문 메시지를 PENDING으로 회수한다 */
const PROCESSING_STALE_THRESHOLD_MS = 2 * 60 * 1_000;
/** reclaimStaleProcessing 실행 최소 간격 — 5초마다 updateMany를 보내지 않도록 스로틀 */
const RECLAIM_INTERVAL_MS = 60_000;

/**
 * 알림 발송 대기 중인 메시지를 주기적으로 조회하여 알림 트리거를 발행하는 폴러.
 *
 * **Outbox 패턴** 구현체로, 5초 간격으로 PENDING 상태의 메시지를 최대 10개씩 배치 처리한다.
 * 처리 실패 시 최대 {@link MAX_RETRY}회까지 재시도하며, 초과 시 FAILED 상태로 전환한다.
 *
 * 배치 내 중복 DB 조회를 줄이기 위해 `memberCache`와 `parentCache`를 활용한다.
 */
@Injectable()
export class NotificationOutboxPoller implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(NotificationOutboxPoller.name);
    private timer?: ReturnType<typeof setInterval>;
    private isPolling = false;
    private lastReclaimTime = 0;

    constructor(
        private readonly messageRepository: MessageRepository,
        private readonly channelMemberRepository: ChannelMemberRepository,
        private readonly triggerProducer: NotificationTriggerProducer,
    ) {}

    /**
     * 모듈 초기화 시 {@link POLL_INTERVAL_MS}(5000ms) 간격으로 폴링 타이머를 시작한다.
     *
     * 이전 폴링이 완료되지 않은 경우 (`isPolling === true`) 해당 인터벌은 건너뛴다.
     */
    onModuleInit() {
        this.timer = setInterval(() => {
            void this.runPollCycle();
        }, POLL_INTERVAL_MS);
        this.logger.log('Notification outbox poller started');
    }

    private async runPollCycle(): Promise<void> {
        if (this.isPolling) return;
        this.isPolling = true;
        try {
            await this.poll();
        } finally {
            this.isPolling = false;
        }
    }

    /**
     * 모듈 종료 시 폴링 타이머를 해제한다.
     */
    onModuleDestroy() {
        clearInterval(this.timer);
    }

    /**
     * 한 번의 폴링 사이클을 실행한다.
     *
     * 실행 순서:
     * 1. PENDING 상태 메시지를 최대 {@link BATCH_SIZE}(10)개 조회하며 PROCESSING으로 원자 전환한다.
     * 2. 배치 내 고유 `parentMessageId`를 한 번에 조회하여 `parentCache`를 사전 채운다.
     * 3. 각 메시지를 {@link processMessage}로 처리하고 성공 시 SENT로 전환한다.
     * 4. 처리 실패 시 재시도 횟수에 따라 PENDING 또는 FAILED로 전환한다.
     */
    private async poll(): Promise<void> {
        // 0단계: 크래시 등으로 stuck된 stale PROCESSING 메시지를 PENDING으로 회수 (1분 간격 스로틀)
        const now = Date.now();
        if (now - this.lastReclaimTime > RECLAIM_INTERVAL_MS) {
            this.lastReclaimTime = now;
            const reclaimed = await this.messageRepository.reclaimStaleProcessing(PROCESSING_STALE_THRESHOLD_MS);
            if (reclaimed > 0) {
                this.logger.warn(`stale PROCESSING 메시지 ${reclaimed}개를 PENDING으로 회수했습니다`);
            }
        }

        // 1단계: 배치 내 메시지 수집 (PENDING → PROCESSING 원자적 전환)
        const msgs: NotificationMessage[] = [];
        for (let i = 0; i < BATCH_SIZE; i++) {
            const msg = await this.messageRepository.findOnePendingAndMarkProcessing();
            if (!msg) break;
            msgs.push(msg);
        }
        if (msgs.length === 0) return;

        // 2단계: 배치 내 고유 parentMessageId를 한 번에 조회해 parentCache 사전 채움
        const memberCache = new Map<string, ChannelMember[]>();
        const parentCache = new Map<string, { authorId: number } | null>();
        const parentIds = [...new Set(
            msgs.filter((m) => m.parentMessageId != null).map((m) => m.parentMessageId!),
        )];
        if (parentIds.length > 0) {
            const parentMap = await this.messageRepository.findParentAuthorsByIds(parentIds);
            for (const id of parentIds) {
                parentCache.set(id.toString(), parentMap.get(id.toString()) ?? null);
            }
        }

        // 3단계: 각 메시지 처리
        for (const msg of msgs) {
            try {
                await this.processMessage(msg, memberCache, parentCache);
                await this.messageRepository.updateNotificationStatus(msg._id, 'SENT');
            } catch (err) {
                const retryCount = (msg.notificationRetryCount ?? 0) + 1;
                const nextStatus = retryCount >= MAX_RETRY ? 'FAILED' : 'PENDING';
                this.logger.error(`outbox 처리 실패 (messageId: ${msg._id.toString()}, retry: ${retryCount}/${MAX_RETRY}), ${nextStatus} 전환`, err);
                await this.messageRepository.updateNotificationStatus(msg._id, nextStatus, retryCount);
            }
        }
    }

    /**
     * 단일 메시지에 대한 알림 트리거를 생성하고 발행한다.
     *
     * **수신자 분류 전략**:
     * - `targetUserIds`: 채널 멤버 전체에서 메시지 작성자를 제외한 모든 사용자.
     * - `forcedUserIds`: 멘션된 사용자 + 부모 메시지 작성자. 알림 수신 설정과 무관하게 항상 알림을 받는다.
     *   단, 메시지 작성자 본인이거나 채널 멤버가 아닌 경우는 제외된다.
     *
     * **캐시 활용**:
     * - `memberCache`: 같은 채널 ID가 배치 내에서 중복 조회되는 것을 방지한다.
     * - `parentCache`: 배치 시작 시 {@link poll}에서 일괄 채워지며, 부모 메시지 작성자 조회에 사용된다.
     *
     * @param msg - 처리할 알림 대상 메시지
     * @param memberCache - 채널 ID를 키로 하는 채널 멤버 목록 캐시 (배치 범위 내 공유)
     * @param parentCache - 부모 메시지 ID를 키로 하는 작성자 정보 캐시 (배치 범위 내 공유)
     * @throws {Error} 채널 멤버 조회 또는 알림 트리거 발행 실패 시
     */
    private async processMessage(
        msg: Message & { _id: Types.ObjectId; createdAt: Date },
        memberCache: Map<string, ChannelMember[]>,
        parentCache: Map<string, { authorId: number } | null>,
    ): Promise<void> {
        const cacheKey = String(msg.channelId);
        let members = memberCache.get(cacheKey);
        if (!members) {
            members = await this.channelMemberRepository.findByChannelId(msg.channelId);
            memberCache.set(cacheKey, members);
        }
        const memberIdSet = new Set(members.map((m) => m.userId));

        const targetUserIds = [...memberIdSet].filter((id) => id !== msg.authorId);

        const forcedSet = new Set<number>();
        for (const mentionedId of msg.mentions ?? []) {
            if (mentionedId !== msg.authorId && memberIdSet.has(mentionedId)) {
                forcedSet.add(mentionedId);
            }
        }
        if (msg.parentMessageId) {
            const parent = parentCache.get(msg.parentMessageId.toString()) ?? null;
            if (parent && parent.authorId !== msg.authorId && memberIdSet.has(parent.authorId)) {
                forcedSet.add(parent.authorId);
            }
        }

        await this.triggerProducer.send({
            type: 'CHAT_MESSAGE',
            targetUserIds,
            forcedUserIds: [...forcedSet],
            data: {
                channelId: msg.channelId,
                teamId: msg.teamId,
                authorId: msg.authorId,
                content: msg.content,
                occurredAt: msg.createdAt.toISOString(),
            },
        });
    }
}
