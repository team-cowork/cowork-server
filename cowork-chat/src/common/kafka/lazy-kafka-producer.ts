import {Logger, type OnModuleDestroy, type OnModuleInit} from '@nestjs/common';
import {type ConfigService} from '@nestjs/config';
import {Kafka, type Producer, type ProducerRecord} from 'kafkajs';
import {getKafkaClientConfig} from '../config/config.util.js';
import {connectWithRetry} from './connect-with-retry.js';

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
	private producer!: Producer;
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

		this.connectPromise ??= this.producer
			.connect()
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
		const kafka = new Kafka({
			clientId: this.options.clientId,
			...getKafkaClientConfig(this.configService),
		});
		this.producer = kafka.producer({idempotent: true});
		const {startupRetryLabel} = this.options;
		const connect = startupRetryLabel === undefined
			? this.ensureConnected()
			: connectWithRetry(this.logger, startupRetryLabel, async () => this.ensureConnected());
		void connect.catch((error: unknown) => {
			this.logger.error(`${this.options.label} bootstrap connect failed: ${error instanceof Error ? error.message : String(error)}`);
		});
	}

	async onModuleDestroy() {
		if (this.isConnected) {
			await this.producer.disconnect();
		}
	}

	isReady(): boolean {
		return this.isConnected;
	}

	protected async publish(record: ProducerRecord): Promise<void> {
		await this.ensureConnected();
		await this.producer.send(record);
	}
}
