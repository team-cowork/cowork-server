import process from 'node:process';
import {readFileSync} from 'node:fs';
import {type ConfigService} from '@nestjs/config';
import {type KafkaConfig, type SASLOptions} from 'kafkajs';

type ConfigKey = string | string[];

function normalizeKeys(keys: ConfigKey): string[] {
	return Array.isArray(keys) ? keys : [keys];
}

export function getRequiredConfig(configService: ConfigService, keys: ConfigKey): string {
	const value = getConfigValue(configService, keys);
	if (value !== undefined) {
		return value;
	}

	throw new Error(`Required configuration is missing: ${normalizeKeys(keys).join(' or ')}`);
}

export function getOptionalConfig(configService: ConfigService, keys: ConfigKey): string | undefined {
	return getConfigValue(configService, keys);
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

export function getRequiredCsvConfig(configService: ConfigService, keys: ConfigKey): string[] {
	const values = getRequiredConfig(configService, keys)
		.split(',')
		.map(value => value.trim())
		.filter(Boolean);

	if (values.length === 0) {
		throw new Error(`Required CSV configuration is empty: ${normalizeKeys(keys).join(' or ')}`);
	}

	return values;
}

export function getKafkaClientConfig(configService: ConfigService): KafkaConfig {
	const protocol = (getOptionalConfig(configService, 'KAFKA_SECURITY_PROTOCOL') ?? 'PLAINTEXT').toUpperCase();
	if (!['PLAINTEXT', 'SSL', 'SASL_SSL'].includes(protocol)) {
		throw new Error('KAFKA_SECURITY_PROTOCOL must be PLAINTEXT, SSL or SASL_SSL');
	}

	const config: KafkaConfig = {
		brokers: getRequiredCsvConfig(configService, 'KAFKA_BOOTSTRAP_SERVERS'),
	};
	if (protocol !== 'PLAINTEXT') {
		const caFile = getOptionalConfig(configService, 'KAFKA_SSL_CA_LOCATION');
		config.ssl = caFile === undefined
			? true
			: {
				rejectUnauthorized: true,
				ca: [readFileSync(caFile, 'utf8')],
			};
	}

	if (protocol === 'SASL_SSL') {
		const mechanism = (getOptionalConfig(configService, 'KAFKA_SASL_MECHANISM') ?? 'SCRAM-SHA-512').toLowerCase();
		if (mechanism !== 'scram-sha-256' && mechanism !== 'scram-sha-512') {
			throw new Error('KAFKA_SASL_MECHANISM must be SCRAM-SHA-256 or SCRAM-SHA-512');
		}

		const sasl = {
			mechanism,
			username: getRequiredConfig(configService, 'KAFKA_SASL_USERNAME'),
			password: getRequiredConfig(configService, 'KAFKA_SASL_PASSWORD'),
		};
		config.sasl = sasl as SASLOptions;
	}

	return config;
}

function getConfigValue(configService: ConfigService, keys: ConfigKey): string | undefined {
	for (const key of normalizeKeys(keys)) {
		const value = configService.get<string>(key) ?? process.env[key];
		if (value !== undefined && value !== '') {
			return value;
		}
	}

	return undefined;
}
