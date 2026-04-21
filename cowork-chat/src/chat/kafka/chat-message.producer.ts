import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { Kafka, Producer } from 'kafkajs';
import { SendMessageDto } from '../dto/send-message.dto';

export interface ChatMessageEvent {
    eventType: string;
    teamId: number;
    projectId?: number | null;
    channelId: number;
    authorId: number;
    authorRole: string;
    content: string;
    type: string;
    attachments?: object[];
    parentMessageId?: string;
    clientMessageId?: string;
    occurredAt: string;
}

@Injectable()
export class ChatMessageProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChatMessageProducer.name);
    private readonly kafka = new Kafka({
        clientId: 'cowork-chat',
        brokers: [(process.env.KAFKA_BOOTSTRAP_SERVERS ?? 'localhost:9092')],
    });
    private producer!: Producer;

    async onModuleInit() {
        this.producer = this.kafka.producer();
        await this.producer.connect();
        this.logger.log('Kafka producer connected');
    }

    async onModuleDestroy() {
        await this.producer.disconnect();
    }

    async sendMessage(dto: SendMessageDto, authorId: number, authorRole: string): Promise<void> {
        const event: ChatMessageEvent = {
            eventType: 'MESSAGE_SENT',
            teamId: dto.teamId,
            projectId: dto.projectId ?? null,
            channelId: dto.channelId,
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
                    key: dto.channelId.toString(),
                    value: JSON.stringify(event),
                },
            ],
        });
    }
}
