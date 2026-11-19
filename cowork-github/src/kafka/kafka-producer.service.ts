import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {Kafka, Producer} from 'kafkajs';
import {AppConfigService} from '../config/app-config.service.js';

/**
 모든 토픽 producer가 공유하는 Kafka 연결. 값은 JSON으로 직렬화해 보낸다.
 */
@injectable()
export class KafkaProducerService implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(KafkaProducerService.name);
	private producer!: Producer;

	constructor(private readonly config: AppConfigService) {}

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-github',
			...this.config.kafkaClientConfig,
		});
		this.producer = kafka.producer();
		await this.producer.connect();
		this.logger.log('Kafka producer connected');
	}

	async onModuleDestroy() {
		await this.producer.disconnect();
	}

	async send(topic: string, key: string, value: unknown): Promise<void> {
		await this.producer.send({topic, messages: [{key, value: JSON.stringify(value)}]});
	}
}
