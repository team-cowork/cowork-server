import {Logger, type OnModuleDestroy, type OnModuleInit} from '@nestjs/common';
import {type ConfigService} from '@nestjs/config';
import {
	compatibilityPartitioner,
	type MessageToProduce,
	Producer,
	stringSerializers,
} from '@platformatic/kafka';
import {getKafkaClientConfig} from '../config/config.util.js';
import {connectWithRetry} from './connect-with-retry.js';

type StringProducer = Producer<string, string, string, string>;

/**
 Keyed messages use `compatibilityPartitioner`, the Java client's partitioner (murmur2 & 0x7fffffff), so each key
 keeps its partition; platformatic's default partitioner differs for negative hashes.
 A custom partitioner also receives keyless messages, and `compatibilityPartitioner` sends them all to
 partition 0, so keyless messages round-robin here.
 */
export function createKafkaProducer(configService: ConfigService, clientId: string): StringProducer {
	let keylessCount = 0;
	return new Producer({
		clientId,
		...getKafkaClientConfig(configService),
		idempotent: true,
		// Idempotent producers otherwise retry forever, so a down broker would never reject publish().
		retries: 5,
		partitioner: (message, key) => key === undefined ? keylessCount++ : compatibilityPartitioner(message, key),
		serializers: stringSerializers,
	});
}

type LazyKafkaProducerOptions = {
	clientId: string;
	/**
	Prefix of the connect and bootstrap-failure log lines.
	*/
	label: string;
	/**
	When set, startup connection retries through {@link connectWithRetry} under this label.
	*/
	startupRetryLabel?: string;
};

/**
 Startup never waits for the broker: a failed bootstrap connect is only logged, and every
 {@link publish} reconnects first. Concurrent callers share one in-flight connect, which is cleared
 on failure so the next call retries.
 */
export abstract class LazyKafkaProducer implements OnModuleInit, OnModuleDestroy {
	private producer!: StringProducer;
	private isConnected = false;
	private connectPromise?: Promise<void>;
	private readonly logger = new Logger(this.constructor.name);

	protected constructor(
		private readonly configService: ConfigService,
		private readonly options: LazyKafkaProducerOptions,
	) {}

	private async ensureConnected(): Promise<void> {
		if (this.isConnected) {
			return;
		}

		// Platformatic has no connect(); InitProducerId is the first broker round-trip.
		this.connectPromise ??= this.producer
			.initIdempotentProducer({})
			.then(() => {
				this.isConnected = true;
				this.logger.log(`${this.options.label} connected`);
			})
			.catch((error: unknown) => {
				this.connectPromise = undefined;
				throw error;
			});

		return this.connectPromise;
	}

	onModuleInit() {
		this.producer = createKafkaProducer(this.configService, this.options.clientId);
		const {startupRetryLabel} = this.options;
		const connect = startupRetryLabel === undefined
			? this.ensureConnected()
			: connectWithRetry(this.logger, startupRetryLabel, async () => this.ensureConnected());
		void connect.catch((error: unknown) => {
			this.logger.error(`${this.options.label} bootstrap connect failed: ${error instanceof Error ? error.message : String(error)}`);
		});
	}

	async onModuleDestroy() {
		await this.producer.close();
	}

	isReady(): boolean {
		return this.isConnected;
	}

	protected async publish(messages: Array<MessageToProduce<string, string, string, string>>): Promise<void> {
		await this.ensureConnected();
		await this.producer.send({messages});
	}
}
