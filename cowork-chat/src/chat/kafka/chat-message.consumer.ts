import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
import { Server } from 'socket.io';
import { Message } from '../schema/message.schema';
import { ChatMessageEvent } from './event/chat-message.event';
import { getRequiredCsvConfig } from '../../common/config/config.util';

@Injectable()
export class ChatMessageConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChatMessageConsumer.name);
    private consumer!: Consumer;

    private io?: Server;

    constructor(
        @InjectModel(Message.name) private readonly messageModel: Model<Message>,
        private readonly configService: ConfigService,
    ) {}

    setSocketServer(io: Server) {
        this.io = io;
    }

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-consumer',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.consumer = kafka.consumer({ groupId: 'cowork-chat' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: 'chat.message', fromBeginning: false });

        await this.consumer.run({
            eachMessage: async ({ message }) => {
                if (!message.value) return;
                try {
                    const event: ChatMessageEvent = JSON.parse(message.value.toString());
                    await this.handleMessageEvent(event);
                } catch (err) {
                    this.logger.error('Kafka 메시지 처리 중 예외 발생', err);
                    // Critical logic error or serialization error - might need manual intervention or dead-letter queue
                }
            },
        });

        this.logger.log('Kafka consumer started: chat.message');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private parseMentions(content: string): number[] {
        const ids: number[] = [];
        for (const match of content.matchAll(/<@(\d+)>/g)) {
            ids.push(parseInt(match[1], 10));
        }
        return [...new Set(ids)];
    }

    private async handleMessageEvent(event: ChatMessageEvent): Promise<void> {
        const mentions = this.parseMentions(event.content);

        try {
            const saved = await this.messageModel.create({
                teamId: event.teamId,
                projectId: event.projectId ?? null,
                channelId: event.channelId,
                authorId: event.authorId,
                content: event.content,
                type: event.type,
                attachments: event.attachments ?? [],
                parentMessageId: (event.parentMessageId && Types.ObjectId.isValid(event.parentMessageId))
                    ? new Types.ObjectId(event.parentMessageId)
                    : null,
                clientMessageId: event.clientMessageId,
                mentions,
                notificationStatus: 'PENDING',
            });

            this.io?.to(`chat:${event.channelId}`).emit('message', saved.toObject());
        } catch (err: any) {
            if (err?.code === 11000) {
                this.logger.warn(`중복 메시지 감지, 스킵합니다 (clientMessageId: ${event.clientMessageId})`);
                return;
            }
            this.logger.error('메시지 저장 실패 — offset 미커밋으로 Kafka 재전달 예정', err);
            throw err;
        }
    }
}
