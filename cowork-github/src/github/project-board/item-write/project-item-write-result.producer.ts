import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {Kafka, Producer} from 'kafkajs';
import {AppConfigService} from '../../../config/app-config.service.js';
import {type ProjectItemWriteResultEvent} from './project-item-write-result.event.js';
import {PROJECT_ITEM_WRITE_RESULT_TOPIC} from './project-item-write-topics.js';

@injectable()
export class ProjectItemWriteResultProducer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(ProjectItemWriteResultProducer.name);
	private producer!: Producer;

	constructor(private readonly config: AppConfigService) {}

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-github',
			...this.config.kafkaClientConfig,
		});
		this.producer = kafka.producer();
		await this.producer.connect();
		this.logger.log('Kafka project item write result producer connected');
	}

	async onModuleDestroy() {
		await this.producer.disconnect();
	}

	async send(event: ProjectItemWriteResultEvent): Promise<void> {
		await this.producer.send({
			topic: PROJECT_ITEM_WRITE_RESULT_TOPIC,
			messages: [{key: event.operationId, value: JSON.stringify(event)}],
		});
	}
}
