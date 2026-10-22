import {
    Injectable,
    Logger,
    OnModuleDestroy,
    OnModuleInit,
} from '@nestjs/common';
import {Kafka, Producer} from 'kafkajs';
import {AppConfigService} from '../../../config/app-config.service';
import {LabelStateEvent} from './event/label-state';

export const LABEL_STATE_TOPIC = 'github.label.state.v1';

@Injectable()
export class LabelStateProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(LabelStateProducer.name);
    private producer!: Producer;

    constructor(private readonly config: AppConfigService) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-github',
            brokers: this.config.kafkaBrokers,
        });
        this.producer = kafka.producer();
        await this.producer.connect();
        this.logger.log('Kafka label-state producer connected');
    }

    async onModuleDestroy() {
        await this.producer.disconnect();
    }

    async send(event: LabelStateEvent): Promise<void> {
        await this.producer.send({
            topic: LABEL_STATE_TOPIC,
            messages: [
                {
                    key: `${event.owner}/${event.repo}:${event.name}`,
                    value: JSON.stringify(event),
                },
            ],
        });
    }
}
