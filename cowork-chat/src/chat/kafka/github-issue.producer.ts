import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Producer } from 'kafkajs';
import { GithubIssueCreateEvent } from './event/github-issue.event';

@Injectable()
export class GithubIssueProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(GithubIssueProducer.name);
    private producer!: Producer;

    constructor(private readonly configService: ConfigService) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-github',
            brokers: [this.configService.get<string>('KAFKA_BOOTSTRAP_SERVERS', 'localhost:9092')],
        });
        this.producer = kafka.producer();
        await this.producer.connect();
        this.logger.log('GitHub issue producer connected');
    }

    async onModuleDestroy() {
        await this.producer.disconnect();
    }

    async send(event: GithubIssueCreateEvent): Promise<void> {
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
}
