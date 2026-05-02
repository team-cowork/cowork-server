import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Producer } from 'kafkajs';
import { SendMessageDto } from '../dto/send-message.dto';
import { ChatMessageEvent } from './event/chat-message.event';

@Injectable()
export class ChatMessageProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChatMessageProducer.name);
    private producer!: Producer;

    constructor(private readonly configService: ConfigService) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat',
            brokers: [this.configService.get<string>('KAFKA_BOOTSTRAP_SERVERS', 'localhost:9092')],
        });
        this.producer = kafka.producer();
        await this.producer.connect();
        this.logger.log('Kafka producer connected');
    }

    async onModuleDestroy() {
        await this.producer.disconnect();
    }

    async sendMessage(channelId: number, dto: SendMessageDto, authorId: number, authorRole: string): Promise<void> {
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
}
