import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
	Inject as inject,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {PROJECTION_STREAMS, ProjectionReadinessService} from './projection-readiness.service.js';
import {startProjectionConsumer} from './projection-consumer.js';
import {type KafkaConsumerHandle} from './partition-fetcher.js';
import {AccountPolicyService} from './account-policy.service.js';

@injectable()
export class AccountPolicyConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(AccountPolicyConsumer.name);
	private consumer?: KafkaConsumerHandle;

	@inject()
	private readonly configService!: ConfigService;

	@inject()
	private readonly policies!: AccountPolicyService;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	async onModuleInit() {
		const stream = PROJECTION_STREAMS.accountPolicy;
		this.consumer = await startProjectionConsumer({
			logger: this.logger, configService: this.configService, clientId: 'cowork-chat-account-role-policy-event', readiness: this.projectionReadiness, stream,
			handleEvent: async (payload, key) => this.policies.apply(payload, key),
		});
	}

	async onModuleDestroy(): Promise<void> {
		await this.consumer?.close();
	}
}
