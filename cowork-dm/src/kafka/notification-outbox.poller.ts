import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { Types } from 'mongoose';
import { MessageRepository, NotificationMessage } from '../message/message.repository';
import { ConversationRepository } from '../conversation/conversation.repository';
import { NotificationProducer } from './notification.producer';

const POLL_INTERVAL_MS = 5_000;
const BATCH_SIZE = 10;
const MAX_RETRY = 3;

@Injectable()
export class NotificationOutboxPoller implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(NotificationOutboxPoller.name);
    private timer?: ReturnType<typeof setInterval>;
    private isPolling = false;

    constructor(
        private readonly messageRepository: MessageRepository,
        private readonly conversationRepository: ConversationRepository,
        private readonly notificationProducer: NotificationProducer,
    ) {}

    onModuleInit() {
        this.timer = setInterval(async () => {
            if (this.isPolling) return;
            this.isPolling = true;
            try {
                await this.poll();
            } finally {
                this.isPolling = false;
            }
        }, POLL_INTERVAL_MS);
        this.logger.log('Notification outbox poller started');
    }

    onModuleDestroy() {
        clearInterval(this.timer);
    }

    private async poll(): Promise<void> {
        const msgs: NotificationMessage[] = [];
        for (let i = 0; i < BATCH_SIZE; i++) {
            const msg = await this.messageRepository.findOnePendingAndMarkProcessing();
            if (!msg) break;
            msgs.push(msg);
        }
        if (msgs.length === 0) return;

        for (const msg of msgs) {
            try {
                await this.processMessage(msg);
                await this.messageRepository.updateNotificationStatus(msg._id, 'SENT');
            } catch (err) {
                const retryCount = (msg.notificationRetryCount ?? 0) + 1;
                const nextStatus = retryCount >= MAX_RETRY ? 'FAILED' : 'PENDING';
                this.logger.error(
                    `outbox 처리 실패 (messageId: ${msg._id}, retry: ${retryCount}/${MAX_RETRY}), ${nextStatus} 전환`,
                    err,
                );
                await this.messageRepository.updateNotificationStatus(msg._id, nextStatus, retryCount);
            }
        }
    }

    private async processMessage(msg: NotificationMessage): Promise<void> {
        const conversation = await this.conversationRepository.findById(msg.conversationId.toString());
        if (!conversation) return;

        const receiverIds = conversation.participants
            .map((p) => p.userId)
            .filter((id) => id !== msg.authorId);

        if (receiverIds.length === 0) return;

        const forcedSet = new Set<number>();
        for (const mentionedId of msg.mentions ?? []) {
            if (mentionedId !== msg.authorId && receiverIds.includes(mentionedId)) {
                forcedSet.add(mentionedId);
            }
        }

        await this.notificationProducer.send({
            type: 'DM_MESSAGE',
            targetUserIds: receiverIds,
            forcedUserIds: [...forcedSet],
            data: {
                conversationId: msg.conversationId.toString(),
                authorId: msg.authorId,
                content: msg.content,
                occurredAt: msg.createdAt.toISOString(),
            },
        });
    }
}
