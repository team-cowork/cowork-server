import {
    Injectable,
    Logger,
    OnModuleDestroy,
    OnModuleInit,
} from '@nestjs/common';
import {Kafka, Producer} from 'kafkajs';
import {AppConfigService} from '../../../config/app-config.service';
import {BackfillCompletedEvent} from './event/backfill-completed';

export const BACKFILL_COMPLETED_TOPIC = 'github.backfill.completed.v1';

@Injectable()
export class BackfillCompletedProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(BackfillCompletedProducer.name);
    private producer!: Producer;

    constructor(private readonly config: AppConfigService) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-github',
            brokers: this.config.kafkaBrokers,
        });
        this.producer = kafka.producer();
        await this.producer.connect();
        this.logger.log('Kafka backfill-completed producer connected');
    }

    async onModuleDestroy() {
        await this.producer.disconnect();
    }

    async send(event: BackfillCompletedEvent): Promise<void> {
        await this.producer.send({
            topic: BACKFILL_COMPLETED_TOPIC,
            messages: [
                {
                    key: event.installationId.toString(),
                    value: JSON.stringify(event),
                },
            ],
        });
    }
}
