import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
import { Message, MessageDocument } from '../schema/message.schema';
import { ChannelMember } from '../schema/channel-member.schema';
import { NotificationTriggerProducer } from './notification-trigger.producer';

const POLL_INTERVAL_MS = 5_000;
const BATCH_SIZE = 10;
const MAX_RETRY = 3;

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
        // 1단계: 배치 내 메시지 수집 (PENDING → PROCESSING 원자적 전환)
        type MsgDoc = Message & { _id: Types.ObjectId; createdAt: Date };
        const msgs: MsgDoc[] = [];
        for (let i = 0; i < BATCH_SIZE; i++) {
            const msg = await this.messageModel.findOneAndUpdate(
                { notificationStatus: 'PENDING' },
                { $set: { notificationStatus: 'PROCESSING' } },
                { new: true },
            ).lean() as MsgDoc | null;
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
            const parents = await this.messageModel
                .find({ _id: { $in: parentIds } })
                .select('authorId')
                .lean() as { _id: Types.ObjectId; authorId: number }[];
            const parentMap = new Map(parents.map((p) => [p._id.toString(), p]));
            for (const id of parentIds) {
                parentCache.set(id.toString(), parentMap.get(id.toString()) ?? null);
            }
        }

        // 3단계: 각 메시지 처리
        for (const msg of msgs) {
            try {
                await this.processMessage(msg, memberCache, parentCache);
                await this.messageModel.updateOne(
                    { _id: msg._id },
                    { $set: { notificationStatus: 'SENT' } },
                );
            } catch (err) {
                const retryCount = (msg.notificationRetryCount ?? 0) + 1;
                const nextStatus = retryCount >= MAX_RETRY ? 'FAILED' : 'PENDING';
                this.logger.error(`outbox 처리 실패 (messageId: ${msg._id}, retry: ${retryCount}/${MAX_RETRY}), ${nextStatus} 전환`, err);
                await this.messageModel.updateOne(
                    { _id: msg._id },
                    { $set: { notificationStatus: nextStatus, notificationRetryCount: retryCount } },
                );
            }
        }
    }

    private async processMessage(
        msg: Message & { _id: Types.ObjectId; createdAt: Date },
        memberCache: Map<string, ChannelMember[]>,
        parentCache: Map<string, { authorId: number } | null>,
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
