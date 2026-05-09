import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Producer } from 'kafkajs';
import { SendMessageDto } from '../dto/send-message.dto';
import { ChatMessageEvent } from './event/chat-message.event';
import { getRequiredCsvConfig } from '../../common/config/config.util';

@Injectable()
export class ChatMessageProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChatMessageProducer.name);
    private producer!: Producer;
    private isConnected = false;
    private connectPromise?: Promise<void>;

    constructor(private readonly configService: ConfigService) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.producer = kafka.producer();
        void this.ensureConnected().catch((error: unknown) => {
            this.logger.error(`Kafka producer bootstrap connect failed: ${this.formatError(error)}`);
        });
    }

    async onModuleDestroy() {
        if (this.isConnected) {
            await this.producer.disconnect();
        }
    }

    async sendMessage(channelId: number, dto: SendMessageDto, authorId: number, authorRole: string): Promise<void> {
        await this.ensureConnected();
        const event: ChatMessageEvent = {
            eventType: 'MESSAGE_SENT',
            teamId: dto.teamId,
            projectId: dto.projectId ?? null,
            channelId,
            authorId,
            authorRole,
            content: dto.content,
            type: dto.type ?? 'TEXT',
            attachments: dto.attachments,
            parentMessageId: dto.parentMessageId,
            clientMessageId: dto.clientMessageId,
            occurredAt: new Date().toISOString(),
        };

        await this.producer.send({
            topic: 'chat.message',
            messages: [
                {
                    key: channelId.toString(),
                    value: JSON.stringify(event),
                },
            ],
        });
    }

    private ensureConnected(): Promise<void> {
        if (this.isConnected) {
            return Promise.resolve();
        }

        if (!this.connectPromise) {
            this.connectPromise = this.producer
                .connect()
                .then(() => {
                    this.isConnected = true;
                    this.logger.log('Kafka producer connected');
                })
                .catch((error: unknown) => {
                    this.connectPromise = undefined;
                    throw error;
                });
        }

        return this.connectPromise;
    }

    private formatError(error: unknown): string {
        return error instanceof Error ? error.message : String(error);
    }
}
