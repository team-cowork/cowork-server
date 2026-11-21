import {type Logger} from '@nestjs/common';
import {type ConfigService} from '@nestjs/config';
import {Redis, type RedisOptions} from 'ioredis';
import {getOptionalConfig, getRequiredConfig} from '../config/config.util.js';

/**
 Starts connecting in the background so a Redis outage does not block startup.
 Without an `error` listener ioredis only writes "Unhandled error event" to the console, outside the JSON log.
 */
export function connectRedis(configService: ConfigService, logger: Logger, options: RedisOptions = {}): Redis {
	const client = new Redis({
		host: getRequiredConfig(configService, 'REDIS_HOST'),
		port: Number(getOptionalConfig(configService, 'REDIS_PORT') ?? 6379),
		lazyConnect: true,
		...options,
	});
	client.on('error', (error: unknown) => {
		logger.error(`Redis client error: ${error instanceof Error ? error.message : String(error)}`);
	});
	void client.connect().catch((error: unknown) => {
		logger.warn(`Redis initial connection failed: ${error instanceof Error ? error.message : String(error)}`);
	});
	return client;
}
