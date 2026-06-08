import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Producer } from 'kafkajs';
import { getRequiredCsvConfig } from '../common/config/config.util';

export interface DmNotificationEvent {
    type: 'DM_MESSAGE';
    targetUserIds: number[];
    forcedUserIds: number[];
    data: {
        conversationId: string;
        authorId: number;
        content: string;
        occurredAt: string;
    };
}

@Injectable()
export class NotificationProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(NotificationProducer.name);
    private producer!: Producer;
    private isConnected = false;
    private connectPromise?: Promise<void>;

    constructor(private readonly configService: ConfigService) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-dm-notification',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.producer = kafka.producer();
        void this.ensureConnected().catch((error: unknown) => {
            this.logger.error(`Notification producer bootstrap connect failed: ${this.formatError(error)}`);
        });
    }

    async onModuleDestroy() {
        if (this.isConnected) {
            await this.producer.disconnect();
        }
    }

    async send(event: DmNotificationEvent): Promise<void> {
        await this.ensureConnected();
        await this.producer.send({
            topic: 'notification.trigger',
            messages: [{ value: JSON.stringify(event) }],
        });
    }

    private ensureConnected(): Promise<void> {
        if (this.isConnected) return Promise.resolve();

        if (!this.connectPromise) {
            this.connectPromise = this.producer
                .connect()
                .then(() => {
                    this.isConnected = true;
                    this.logger.log('Notification producer connected');
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
