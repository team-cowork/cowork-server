import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Producer } from 'kafkajs';

export interface NotificationTriggerEvent {
    type: string;
    targetUserIds: number[];
    forcedUserIds: number[];
    data: {
        channelId: number;
        teamId: number;
        authorId: number;
        content: string;
        occurredAt: string;
    };
}

@Injectable()
export class NotificationTriggerProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(NotificationTriggerProducer.name);
    private producer!: Producer;

    constructor(private readonly configService: ConfigService) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-notification',
            brokers: [this.configService.get<string>('KAFKA_BOOTSTRAP_SERVERS', 'localhost:9092')],
        });
        this.producer = kafka.producer();
        await this.producer.connect();
        this.logger.log('Notification trigger producer connected');
    }

    async onModuleDestroy() {
        await this.producer.disconnect();
    }

    async send(event: NotificationTriggerEvent): Promise<void> {
        await this.producer.send({
            topic: 'notification.trigger',
            messages: [{ value: JSON.stringify(event) }],
        });
    }
}
