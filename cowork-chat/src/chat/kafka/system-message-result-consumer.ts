import {type Logger, ServiceUnavailableException} from '@nestjs/common';
import {type ConfigService} from '@nestjs/config';
import {type Consumer, Kafka} from 'kafkajs';
import {type Server} from 'socket.io';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {connectWithRetry} from '../../common/kafka/connect-with-retry.js';
import {type ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {type ChannelMessageReadAccessService} from '../service/index.js';
import {type KafkaMessageSource} from './system-message-key.js';

/**
 SYSTEM 메시지 결과 컨슈머들이 공유하는 Socket.IO 브로드캐스트 도우미.

 `ChatGateway`의 `afterInit`에서 {@link setSocketServer}가 호출되기 전에 수신된 결과는 저장만 되고 브로드캐스트되지 않는다.
 */
export class SystemMessageBroadcaster {
	private io?: Server;

	constructor(
		private readonly logger: Logger,
		private readonly channelMessageReadAccess: ChannelMessageReadAccessService,
	) {}

	setSocketServer(io: Server): void {
		this.io = io;
	}

	/**
     Socket.IO를 통해 특정 채널의 클라이언트에게 메시지를 브로드캐스트한다.

     Socket.IO 서버가 주입되지 않은 경우 (`io`가 `undefined`) 경고 로그를 남기고 무시된다.
     */
	async notifyClient(channelId: number, message: unknown): Promise<void> {
		if (!this.io) {
			this.logger.warn(`Socket.IO server not initialized yet, dropping message broadcast (channelId=${channelId})`);
			return;
		}

		await this.channelMessageReadAccess.emitToReadableChannelUsers(this.io, channelId, {event: 'message', payload: message});
	}
}

/**
 채널 projection이 준비된 뒤에도 저장이 채널 부재(`ServiceUnavailableException`)로 실패했는지 판별한다.

 준비 완료 뒤 채널이 없다면 같은 이벤트를 다시 받아도 채널이 생기지 않으므로 건너뛰어야 한다.
 준비 전이라면 projection이 따라잡을 때까지 재시도해야 한다.
 */
export function isMissingChannelAfterReady(error: unknown, projectionReadiness: Pick<ProjectionReadinessService, 'areReady'>): boolean {
	return error instanceof ServiceUnavailableException && projectionReadiness.areReady(['channel']);
}

export type SystemMessageConsumerOptions<E> = {
	logger: Logger;
	configService: ConfigService;
	projectionReadiness: Pick<ProjectionReadinessService, 'areReady'>;
	clientId: string;
	groupId: string;
	topic: string;
	handle: (event: E, source: KafkaMessageSource) => Promise<void>;
};

/**
 결과 토픽을 구독하고 메시지마다 `handle`을 호출한다.

 JSON 파싱 오류(`SyntaxError`)는 재처리 불가 메시지로 판단하여 무시한다. 채널 부재는 {@link isMissingChannelAfterReady}일 때만
 건너뛴다. 그 외 오류는 예외로 전파하여 Kafka offset을 커밋하지 않고 재전달을 유도한다.
 */
export async function startSystemMessageConsumer<E>(options: SystemMessageConsumerOptions<E>): Promise<Consumer> {
	const kafka = new Kafka({
		clientId: options.clientId,
		...getKafkaClientConfig(options.configService),
	});
	const consumer = kafka.consumer({groupId: options.groupId});
	await connectWithRetry(options.logger, `Kafka consumer startup: ${options.topic}`, async () => {
		await consumer.connect();
		await consumer.subscribe({topic: options.topic, fromBeginning: false});
	});

	void consumer
		.run({
			async eachMessage({topic, partition, message}) {
				if (!message.value) {
					return;
				}

				try {
					const event = JSON.parse(message.value.toString()) as E;
					await options.handle(event, {topic, partition, offset: message.offset});
				} catch (error) {
					if (isMissingChannelAfterReady(error, options.projectionReadiness)) {
						options.logger.warn(`Skipping result for a channel missing after projection readiness topic=${topic} partition=${partition} offset=${message.offset}`);
						return;
					}

					options.logger.error(`Failed to process ${topic} event`, error);
					if (!(error instanceof SyntaxError)) {
						throw error;
					}
				}
			},
		})
		.catch(async (error: unknown) => {
			options.logger.error(`${options.topic} Kafka consumer failed`, error);
			// 처리되지 않은 rejection으로 남겨 프로세스를 종료하고 재시작을 맡긴다.
			throw error;
		});

	return consumer;
}
