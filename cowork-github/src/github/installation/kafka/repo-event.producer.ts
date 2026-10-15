import {
  Injectable,
  Logger,
  OnModuleDestroy,
  OnModuleInit,
} from '@nestjs/common';
import { Kafka, Producer } from 'kafkajs';
import { AppConfigService } from '../../../config/app-config.service';
import { RepoEvent } from './event/repo-event';

const REPO_EVENT_TOPIC = 'github.repo.event';

@Injectable()
export class RepoEventProducer implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(RepoEventProducer.name);
  private producer!: Producer;

  constructor(private readonly config: AppConfigService) {}

  async onModuleInit() {
    const kafka = new Kafka({
      clientId: 'cowork-github',
      brokers: this.config.kafkaBrokers,
    });
    this.producer = kafka.producer();
    await this.producer.connect();
    this.logger.log('Kafka repo-event producer connected');
  }

  async onModuleDestroy() {
    await this.producer.disconnect();
  }

  async send(event: RepoEvent): Promise<void> {
    await this.producer.send({
      topic: REPO_EVENT_TOPIC,
      messages: [
        {
          key: `${event.owner}/${event.repo}`,
          value: JSON.stringify(event),
        },
      ],
    });
  }
}
