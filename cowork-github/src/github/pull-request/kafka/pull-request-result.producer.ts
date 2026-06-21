import {
  Injectable,
  Logger,
  OnModuleDestroy,
  OnModuleInit,
} from '@nestjs/common';
import { Kafka, Producer } from 'kafkajs';
import { AppConfigService } from '../../../config/app-config.service';
import { PullRequestResultEvent } from './event/pull-request-result.event';

export type PullRequestResultTopic =
  | 'github.pr.merge.result'
  | 'github.pr.approve.result';

@Injectable()
export class PullRequestResultProducer
  implements OnModuleInit, OnModuleDestroy
{
  private readonly logger = new Logger(PullRequestResultProducer.name);
  private producer!: Producer;

  constructor(private readonly config: AppConfigService) {}

  async onModuleInit() {
    const kafka = new Kafka({
      clientId: 'cowork-github',
      brokers: this.config.kafkaBrokers,
    });
    this.producer = kafka.producer();
    await this.producer.connect();
    this.logger.log('Kafka PR result producer connected');
  }

  async onModuleDestroy() {
    await this.producer.disconnect();
  }

  async send(
    topic: PullRequestResultTopic,
    event: PullRequestResultEvent,
  ): Promise<void> {
    await this.producer.send({
      topic,
      messages: [
        {
          key: event.channelId.toString(),
          value: JSON.stringify(event),
        },
      ],
    });
  }
}
