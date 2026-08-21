import {
  Injectable,
  Logger,
  OnModuleDestroy,
  OnModuleInit,
} from '@nestjs/common';
import { Kafka, Producer } from 'kafkajs';
import { AppConfigService } from '../../../config/app-config.service';
import {
  TeamGithubConnectedEvent,
  TeamGithubDisconnectedEvent,
} from './event/team-github.event';

export type TeamGithubTopic =
  | 'team.github.connected'
  | 'team.github.disconnected';

@Injectable()
export class TeamGithubProducer implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(TeamGithubProducer.name);
  private producer!: Producer;

  constructor(private readonly config: AppConfigService) {}

  async onModuleInit() {
    const kafka = new Kafka({
      clientId: 'cowork-github',
      brokers: this.config.kafkaBrokers,
    });
    this.producer = kafka.producer();
    await this.producer.connect();
    this.logger.log('Kafka team-github producer connected');
  }

  async onModuleDestroy() {
    await this.producer.disconnect();
  }

  async send(
    topic: TeamGithubTopic,
    event: TeamGithubConnectedEvent | TeamGithubDisconnectedEvent,
  ): Promise<void> {
    await this.producer.send({
      topic,
      messages: [
        {
          key: event.installationId.toString(),
          value: JSON.stringify(event),
        },
      ],
    });
  }
}
