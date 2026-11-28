import {type Logger} from '@nestjs/common';
import {type ConfigService} from '@nestjs/config';
import {getKafkaClientConfig} from '../config/config.util.js';
import {connectWithRetry} from './connect-with-retry.js';
import {createGroupConsumer, type KafkaConsumerHandle, PartitionFetcher} from './partition-fetcher.js';
import {applyProjectionMessage} from './projection-message.processor.js';
import {type ProjectionReadinessService, type ProjectionStream} from './projection-readiness.service.js';

/**
 Projection consumer의 startup(group 참여, readiness 등록)을 재시도한다. 파티션은 readiness가 Mongo checkpoint
 lease를 claim하고 그 위치로 seek한 뒤에만 fetch한다.
 */
export async function startProjectionConsumer(input: {
	logger: Logger;
	configService: ConfigService;
	clientId: string;
	readiness: ProjectionReadinessService;
	stream: ProjectionStream;
	handleEvent(payload: unknown, messageKey?: string): Promise<void>;
}): Promise<KafkaConsumerHandle> {
	const {logger, configService, clientId, readiness, stream, handleEvent} = input;
	const fetcher = new PartitionFetcher({
		logger,
		consumer: createGroupConsumer(configService, clientId, stream.groupId),
		topic: stream.topic,
		concurrency: 1,
		async handle(record) {
			await readiness.processMessage(stream, record.partition, record.offset, async () => applyProjectionMessage(stream, {
				record, readiness, apply: handleEvent,
			}));
		},
	});
	await connectWithRetry(logger, `Kafka consumer startup: ${stream.topic}`, async () => {
		await readiness.registerProjection(fetcher, stream, {clientId: `${clientId}-admin`, ...getKafkaClientConfig(configService)});
		await fetcher.start();
	});
	logger.log(`Kafka projection consumer started: ${stream.topic}`);
	return fetcher;
}
