import {
    Injectable,
    Logger,
    OnModuleDestroy,
    OnModuleInit,
} from '@nestjs/common';
import {Kafka, Producer} from 'kafkajs';
import {AppConfigService} from '../../config/app-config.service';
import {IssueWriteResultEvent} from './event/issue-write-result.event';
import {ISSUE_WRITE_RESULT_TOPIC} from './issue-write-topics';

@Injectable()
export class IssueWriteResultProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(IssueWriteResultProducer.name);
    private producer!: Producer;

    constructor(private readonly config: AppConfigService) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-github',
            ...this.config.kafkaClientConfig,
        });
        this.producer = kafka.producer();
        await this.producer.connect();
        this.logger.log('Kafka issue-write result producer connected');
    }

    async onModuleDestroy() {
        await this.producer.disconnect();
    }

    async send(event: IssueWriteResultEvent): Promise<void> {
        await this.producer.send({
            topic: ISSUE_WRITE_RESULT_TOPIC,
            messages: [
                {
                    key: event.operationId,
                    value: JSON.stringify(event),
                },
            ],
        });
    }
}
