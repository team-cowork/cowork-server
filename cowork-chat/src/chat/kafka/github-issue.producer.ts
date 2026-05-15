import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Producer } from 'kafkajs';
import { GithubIssueCreateEvent } from './event/github-issue.event';
import { getRequiredCsvConfig } from '../../common/config/config.util';

@Injectable()
export class GithubIssueProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(GithubIssueProducer.name);
    private producer!: Producer;
    private isConnected = false;
    private connectPromise?: Promise<void>;

    constructor(private readonly configService: ConfigService) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-github',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.producer = kafka.producer();
        void this.ensureConnected().catch((error: unknown) => {
            this.logger.error(`GitHub issue producer bootstrap connect failed: ${this.formatError(error)}`);
        });
    }

    async onModuleDestroy() {
        if (this.isConnected) {
            await this.producer.disconnect();
        }
    }

    async send(event: GithubIssueCreateEvent): Promise<void> {
        await this.ensureConnected();
        await this.producer.send({
            topic: 'github.issue.create',
            messages: [
                {
                    key: event.channelId.toString(),
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
                    this.logger.log('GitHub issue producer connected');
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
