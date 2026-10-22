import {
    Injectable,
    Logger,
    OnModuleDestroy,
    OnModuleInit,
} from '@nestjs/common';
import {Kafka, Producer} from 'kafkajs';
import {AppConfigService} from '../../../config/app-config.service';
import {RepoStateEvent} from './event/repo-state';
import {toRepoIdentity} from './event/repo-identity';
import {type SyncCompletedMarker, syncCompletedKey} from './event/sync-completed';

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
        const normalized = {...event, owner: toRepoIdentity(event.owner), repo: toRepoIdentity(event.repo)};
        await this.producer.send({
            topic: REPO_STATE_TOPIC,
            messages: [
                {
                    key: `${normalized.owner}/${normalized.repo}`,
                    value: JSON.stringify(normalized),
                },
            ],
        });
    }

    async sendSyncCompleted(marker: SyncCompletedMarker): Promise<void> {
        await this.producer.send({
            topic: REPO_STATE_TOPIC,
            messages: [
                {
                    key: syncCompletedKey(marker),
                    value: JSON.stringify(marker),
                },
            ],
        });
    }
}
