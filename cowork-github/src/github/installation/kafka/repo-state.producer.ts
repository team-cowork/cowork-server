import {
    Injectable,
    Logger,
    OnModuleDestroy,
    OnModuleInit,
} from '@nestjs/common';
import {Kafka, Producer} from 'kafkajs';
import {AppConfigService} from '../../../config/app-config.service';
import {RepoStateEvent} from './event/repo-state';

export const REPO_STATE_TOPIC = 'github.repo.state.v1';

@Injectable()
export class RepoStateProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(RepoStateProducer.name);
    private producer!: Producer;

    constructor(private readonly config: AppConfigService) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-github',
            brokers: this.config.kafkaBrokers,
        });
        this.producer = kafka.producer();
        await this.producer.connect();
        this.logger.log('Kafka repo-state producer connected');
    }

    async onModuleDestroy() {
        await this.producer.disconnect();
    }

    async send(event: RepoStateEvent): Promise<void> {
        await this.producer.send({
            topic: REPO_STATE_TOPIC,
            messages: [
                {
                    key: `${event.owner}/${event.repo}`,
                    value: JSON.stringify(event),
                },
            ],
        });
    }
}
