import {
    Injectable,
    Logger,
    OnModuleDestroy,
    OnModuleInit,
} from '@nestjs/common';
import {Kafka, Producer} from 'kafkajs';
import {AppConfigService} from '../../../config/app-config.service';
import {PullRequestStateEvent} from './event/pull-request-state';

export const PULL_REQUEST_STATE_TOPIC = 'github.pull-request.state.v1';

@Injectable()
export class PullRequestStateProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(PullRequestStateProducer.name);
    private producer!: Producer;

    constructor(private readonly config: AppConfigService) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-github',
            brokers: this.config.kafkaBrokers,
        });
        this.producer = kafka.producer();
        await this.producer.connect();
        this.logger.log('Kafka pull-request-state producer connected');
    }

    async onModuleDestroy() {
        await this.producer.disconnect();
    }

    async send(event: PullRequestStateEvent): Promise<void> {
        await this.producer.send({
            topic: PULL_REQUEST_STATE_TOPIC,
            messages: [
                {
                    key: `${event.owner}/${event.repo}#${event.number}`,
                    value: JSON.stringify(event),
                },
            ],
        });
    }
}
