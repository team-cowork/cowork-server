import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
	Inject as inject,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {getKafkaClientConfig} from '../config/config.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from './projection-readiness.service.js';
import {startProjectionConsumer} from './projection-consumer.js';
import {AccountPolicyService} from './account-policy.service.js';

@injectable()
export class AccountPolicyConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(AccountPolicyConsumer.name);
	private consumer!: Consumer;

	@inject()
	private readonly configService!: ConfigService;

	@inject()
	private readonly policies!: AccountPolicyService;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-chat-account-role-policy-event',
			...getKafkaClientConfig(this.configService),
		});
		const stream = PROJECTION_STREAMS.accountPolicy;
		this.consumer = kafka.consumer({groupId: stream.groupId});
		await startProjectionConsumer({
			logger: this.logger, kafka, consumer: this.consumer, readiness: this.projectionReadiness, stream,
			handleEvent: async (payload, key) => this.policies.apply(payload, key),
		});
	}

	async onModuleDestroy(): Promise<void> {
		await this.consumer.disconnect();
	}
}
