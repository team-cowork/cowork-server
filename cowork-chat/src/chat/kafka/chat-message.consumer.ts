import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { Kafka, Consumer } from 'kafkajs';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
import { Server } from 'socket.io';
import { Message } from '../schema/message.schema';
import { ChatMessageEvent } from './chat-message.producer';

@Injectable()
export class ChatMessageConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChatMessageConsumer.name);
    private readonly kafka = new Kafka({
        clientId: 'cowork-chat-consumer',
        brokers: [(process.env.KAFKA_BOOTSTRAP_SERVERS ?? 'localhost:9092')],
    });
    private consumer!: Consumer;

    private io?: Server;

    constructor(
        @InjectModel(Message.name) private readonly messageModel: Model<Message>,
    ) {}

    setSocketServer(io: Server) {
        this.io = io;
    }

    async onModuleInit() {
        this.consumer = this.kafka.consumer({ groupId: 'cowork-chat' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: 'chat.message', fromBeginning: false });

        await this.consumer.run({
            eachMessage: async ({ message }) => {
                if (!message.value) return;
                let event: ChatMessageEvent;
                try {
                    event = JSON.parse(message.value.toString());
                } catch {
                    this.logger.error('Kafka 메시지 역직렬화 실패 — 스킵');
                    return;
                }
                await this.handleMessageEvent(event);
            },
        });

        this.logger.log('Kafka consumer started: chat.message');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private async handleMessageEvent(event: ChatMessageEvent): Promise<void> {
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
                clientMessageId: event.clientMessageId ?? null,
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
