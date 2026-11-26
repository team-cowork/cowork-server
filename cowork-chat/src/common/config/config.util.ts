import {readFileSync} from 'node:fs';
import {type ConfigService} from '@nestjs/config';
import {type BaseOptions, SASLMechanisms} from '@platformatic/kafka';

export function getRequiredConfig(configService: ConfigService, key: string): string {
	const value = getOptionalConfig(configService, key);
	if (value === undefined) {
		throw new Error(`Required configuration is missing: ${key}`);
	}

	return value;
}

export function getOptionalConfig(configService: ConfigService, key: string): string | undefined {
	const value = configService.get<string>(key);
	return value === '' ? undefined : value;
}

export function getMongoDbUri(configService: ConfigService): string {
	const host = getOptionalConfig(configService, 'DB_HOST');
	if (host === undefined) {
		return getRequiredConfig(configService, 'MONGODB_URI');
	}

	// Individual DB inputs take precedence over the complete URI.
	const port = getRequiredConfig(configService, 'DB_PORT');
	const database = encodeURIComponent(getRequiredConfig(configService, 'DB_NAME'));
	const username = encodeURIComponent(getRequiredConfig(configService, 'DB_USERNAME'));
	const password = encodeURIComponent(getRequiredConfig(configService, 'DB_PASSWORD'));
	return `mongodb://${username}:${password}@${host}:${port}/${database}?authSource=admin`;
}

export function getRequiredCsvConfig(configService: ConfigService, key: string): string[] {
	const values = getRequiredConfig(configService, key)
		.split(',')
		.map(value => value.trim())
		.filter(Boolean);

	if (values.length === 0) {
		throw new Error(`Required CSV configuration is empty: ${key}`);
	}

	return values;
}

export type KafkaClientConfig = Pick<BaseOptions, 'bootstrapBrokers' | 'tls' | 'sasl'>;

export function getKafkaClientConfig(configService: ConfigService): KafkaClientConfig {
	const protocol = (getOptionalConfig(configService, 'KAFKA_SECURITY_PROTOCOL') ?? 'PLAINTEXT').toUpperCase();
	if (!['PLAINTEXT', 'SSL', 'SASL_SSL'].includes(protocol)) {
		throw new Error('KAFKA_SECURITY_PROTOCOL must be PLAINTEXT, SSL or SASL_SSL');
	}

	const config: KafkaClientConfig = {
		bootstrapBrokers: getRequiredCsvConfig(configService, 'KAFKA_BOOTSTRAP_SERVERS'),
	};
	if (protocol !== 'PLAINTEXT') {
		const caFile = getOptionalConfig(configService, 'KAFKA_SSL_CA_LOCATION');
		config.tls = caFile === undefined
			? {rejectUnauthorized: true}
			: {rejectUnauthorized: true, ca: [readFileSync(caFile, 'utf8')]};
	}

	if (protocol === 'SASL_SSL') {
		const mechanism = (getOptionalConfig(configService, 'KAFKA_SASL_MECHANISM') ?? SASLMechanisms.SCRAM_SHA_512).toUpperCase();
		if (mechanism !== SASLMechanisms.SCRAM_SHA_256 && mechanism !== SASLMechanisms.SCRAM_SHA_512) {
			throw new Error('KAFKA_SASL_MECHANISM must be SCRAM-SHA-256 or SCRAM-SHA-512');
		}

		config.sasl = {
			mechanism,
			username: getRequiredConfig(configService, 'KAFKA_SASL_USERNAME'),
			password: getRequiredConfig(configService, 'KAFKA_SASL_PASSWORD'),
		};
	}

	return config;
}
