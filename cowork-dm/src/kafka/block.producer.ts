import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Producer } from 'kafkajs';
import { getRequiredCsvConfig } from '../common/config/config.util';

export interface BlockEvent {
    blockerId: number;
    targetId: number;
    action: 'BLOCK' | 'UNBLOCK';
}

@Injectable()
export class BlockProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(BlockProducer.name);
    private producer!: Producer;
    private isConnected = false;
    private connectPromise?: Promise<void>;

    constructor(private readonly configService: ConfigService) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-dm-block',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.producer = kafka.producer();
        void this.ensureConnected().catch((error: unknown) => {
            this.logger.error(`Block producer bootstrap connect failed: ${this.formatError(error)}`);
        });
    }

    async onModuleDestroy() {
        if (this.isConnected) {
            await this.producer.disconnect();
        }
    }

    async send(event: BlockEvent): Promise<void> {
        await this.ensureConnected();
        await this.producer.send({
            topic: 'dm.block.updated',
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
                    this.logger.log('Block producer connected');
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
