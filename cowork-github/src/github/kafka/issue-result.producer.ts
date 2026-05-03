import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { Kafka, Producer } from 'kafkajs';
import { AppConfigService } from '../../config/app-config.service';
import { IssueResultEvent } from './event/issue-result.event';

@Injectable()
export class IssueResultProducer implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(IssueResultProducer.name);
  private producer!: Producer;

  constructor(private readonly config: AppConfigService) {}

  async onModuleInit() {
    const kafka = new Kafka({
      clientId: 'cowork-github',
      brokers: this.config.kafkaBrokers,
    });
    this.producer = kafka.producer();
    await this.producer.connect();
    this.logger.log('Kafka result producer connected');
  }

  async onModuleDestroy() {
    await this.producer.disconnect();
  }

  async send(event: IssueResultEvent): Promise<void> {
    await this.producer.send({
      topic: 'github.issue.result',
      messages: [
        {
          key: event.channelId.toString(),
          value: JSON.stringify(event),
        },
      ],
    });
  }
}
