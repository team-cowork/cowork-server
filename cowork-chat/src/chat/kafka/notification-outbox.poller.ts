import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
import { Message, MessageDocument } from '../schema/message.schema';
import { ChannelMember } from '../schema/channel-member.schema';
import { NotificationTriggerProducer } from './notification-trigger.producer';

const POLL_INTERVAL_MS = 5_000;
const BATCH_SIZE = 10;

@Injectable()
export class NotificationOutboxPoller implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(NotificationOutboxPoller.name);
    private timer?: ReturnType<typeof setInterval>;
    private isPolling = false;

    constructor(
        @InjectModel(Message.name) private readonly messageModel: Model<Message>,
        @InjectModel(ChannelMember.name) private readonly memberModel: Model<ChannelMember>,
        private readonly triggerProducer: NotificationTriggerProducer,
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
        const memberCache = new Map<string, ChannelMember[]>();
        for (let i = 0; i < BATCH_SIZE; i++) {
            const msg = await this.messageModel.findOneAndUpdate(
                { notificationStatus: 'PENDING' },
                { $set: { notificationStatus: 'PROCESSING' } },
                { new: true },
            ).lean() as (Message & { _id: Types.ObjectId; createdAt: Date }) | null;

            if (!msg) break;

            try {
                await this.processMessage(msg, memberCache);
                await this.messageModel.updateOne(
                    { _id: msg._id },
                    { $set: { notificationStatus: 'SENT' } },
                );
            } catch (err) {
                this.logger.error(`outbox 처리 실패 (messageId: ${msg._id}), PENDING 복구`, err);
                await this.messageModel.updateOne(
                    { _id: msg._id },
                    { $set: { notificationStatus: 'PENDING' } },
                );
            }
        }
    }

    private async processMessage(
        msg: Message & { _id: Types.ObjectId; createdAt: Date },
        memberCache: Map<string, ChannelMember[]>,
    ): Promise<void> {
        const cacheKey = String(msg.channelId);
        let members = memberCache.get(cacheKey);
        if (!members) {
            members = await this.memberModel.find({ channelId: msg.channelId }).lean() as ChannelMember[];
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
            const parent = await this.messageModel
                .findById(msg.parentMessageId)
                .select('authorId')
                .lean();
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
