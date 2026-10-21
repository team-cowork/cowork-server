import {
    Injectable,
    Logger,
    OnModuleDestroy,
    OnModuleInit,
} from '@nestjs/common';
import {Kafka, Producer} from 'kafkajs';
import {AppConfigService} from '../../../config/app-config.service';
import {IssueStateEvent} from './event/issue-state';

export const ISSUE_STATE_TOPIC = 'github.issue.state.v1';

@Injectable()
export class IssueStateProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(IssueStateProducer.name);
    private producer!: Producer;

    constructor(private readonly config: AppConfigService) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-github',
            brokers: this.config.kafkaBrokers,
        });
        this.producer = kafka.producer();
        await this.producer.connect();
        this.logger.log('Kafka issue-state producer connected');
    }

    async onModuleDestroy() {
        await this.producer.disconnect();
    }

    async send(event: IssueStateEvent): Promise<void> {
        await this.producer.send({
            topic: ISSUE_STATE_TOPIC,
            messages: [
                {
                    key: `${event.owner}/${event.repo}#${event.number}`,
                    value: JSON.stringify(event),
                },
            ],
        });
    }
}
