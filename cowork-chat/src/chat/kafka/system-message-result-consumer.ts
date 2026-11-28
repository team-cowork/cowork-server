import {type Logger, ServiceUnavailableException} from '@nestjs/common';
import {type ConfigService} from '@nestjs/config';
import {type KafkaConsumerHandle, startCommittedConsumer} from '../../common/kafka/partition-fetcher.js';
import {type ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {type KafkaMessageSource} from './system-message-key.js';

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
export async function startSystemMessageConsumer<E>(options: SystemMessageConsumerOptions<E>): Promise<KafkaConsumerHandle> {
	return startCommittedConsumer({
		logger: options.logger,
		configService: options.configService,
		clientId: options.clientId,
		groupId: options.groupId,
		topic: options.topic,
		fromBeginning: false,
		async handle({topic, partition, offset, value}) {
			if (value === null) {
				return;
			}

			try {
				const event = JSON.parse(value) as E;
				await options.handle(event, {topic, partition, offset});
			} catch (error) {
				if (isMissingChannelAfterReady(error, options.projectionReadiness)) {
					options.logger.warn(`Skipping result for a channel missing after projection readiness topic=${topic} partition=${partition} offset=${offset}`);
					return;
				}

				if (!(error instanceof SyntaxError)) {
					throw error;
				}

				options.logger.error(`Failed to process ${topic} event`, error);
			}
		},
	});
}
