import {type Logger} from '@nestjs/common';
import {type Consumer, type Kafka} from 'kafkajs';
import {connectWithRetry} from './connect-with-retry.js';
import {applyProjectionMessage} from './projection-message.processor.js';
import {type ProjectionReadinessService, type ProjectionStream} from './projection-readiness.service.js';

/**
 Projection consumer의 startup(connect, subscribe, readiness 등록)을 재시도하고, run 실패는 rejection으로 남겨 프로세스 재시작에 맡긴다.
 */
export async function startProjectionConsumer(input: {
	logger: Logger;
	kafka: Kafka;
	consumer: Consumer;
	readiness: ProjectionReadinessService;
	stream: ProjectionStream;
	handleEvent(payload: unknown, messageKey?: string): Promise<void>;
}): Promise<void> {
	const {logger, kafka, consumer, readiness, stream, handleEvent} = input;
	await connectWithRetry(logger, `Kafka consumer startup: ${stream.topic}`, async () => {
		await consumer.connect();
		await consumer.subscribe({topic: stream.topic, fromBeginning: true});
		await readiness.registerProjection(kafka, consumer, stream);
	});

	void consumer.run({
		async eachMessage({partition, message}): Promise<void> {
			await readiness.processMessage(stream, partition, message.offset, async () => applyProjectionMessage(stream, {
				partition, message, readiness, apply: handleEvent,
			}));
		},
	}).catch((error: unknown) => {
		logger.error(`${stream.topic} Kafka consumer failed`, error);
		// 처리되지 않은 rejection으로 남겨 프로세스를 종료하고 재시작을 맡긴다.
		throw error;
	});
	logger.log(`Kafka projection consumer started: ${stream.topic}`);
}
