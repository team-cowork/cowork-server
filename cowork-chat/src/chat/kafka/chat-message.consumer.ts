import {
	Injectable as injectable,
	OnModuleDestroy,
	OnModuleInit,
	Logger,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Server} from 'socket.io';
import {getOptionalConfig} from '../../common/config/config.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {ChatMessageQuarantineService} from '../service/index.js';
import {type ConsumedRecord, type KafkaConsumerHandle, startCommittedConsumer} from '../../common/kafka/partition-fetcher.js';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {waitForProjectionReadiness} from '../../common/kafka/projection-readiness-wait.js';
import {CHANNEL_PERMISSION_STREAMS, type ProjectionName} from '../../common/kafka/projection-streams.js';
import {type Nullable} from '../../common/type/nullable.js';
import {
	ChatMessageContractError,
	validateChatMessageEvent,
	CHAT_MESSAGE_TOPIC,
	CHAT_MESSAGE_CONTRACT_VERSION,
} from './event/chat-message-contract.js';
import {ChatMessageScopeError} from './chat-message-scope-validator.js';
import {ChatMessageProcessor} from './chat-message.processor.js';

/**
 한 번에 한 파티션만 처리하는 기본값(1) 대신, 서로 다른 채널(=파티션)의 메시지를
 동시에 처리하기 위한 동시성. `channelId`가 파티션 키이므로 채널 내 순서는 그대로 유지된다.
 실제 파티션 수보다 큰 값은 쓰이지 않으므로 상한으로 안전하게 둔다.
 */
const DEFAULT_PARTITIONS_CONSUMED_CONCURRENTLY = 3;

/**
 메시지 처리에 필요한 projection. 전송 판정에는 채널 권한 stream이, 범위 재검증에는 `channel`과 `thread`가 필요하다.
 */
const CHAT_MESSAGE_REQUIRED_STREAMS: readonly ProjectionName[] = [...CHANNEL_PERMISSION_STREAMS, 'thread'];

/**
Kafka `chat.message.event.v3` record를 검증하고, poison record만 durable quarantine한다.
*/
@injectable()
export class ChatMessageConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(ChatMessageConsumer.name);
	private consumer?: KafkaConsumerHandle;

	constructor(
		private readonly configService: ConfigService,
		private readonly processor: ChatMessageProcessor,
		private readonly quarantineService: ChatMessageQuarantineService,
		private readonly projectionReadiness: ProjectionReadinessService,
	) {}

	/**
     `CHAT_MESSAGE_CONSUMER_CONCURRENCY`를 안전한 양의 정수로 해석한다.

     정수가 아니거나 1 미만이면(예: 오타로 `abc`, `2.5`, `-1`, `0`) 그대로 컨슈머에 넘기지 않는다.
     `0`이면 컨슈머가 아무 메시지도 처리하지 못한 채 조용히 멈춘다.
     값이 유효하지 않으면 경고 로그만 남기고 기본값으로 대체한다.
     */
	private resolveConcurrency(): number {
		const raw = getOptionalConfig(this.configService, 'CHAT_MESSAGE_CONSUMER_CONCURRENCY');
		if (raw === undefined) {
			return DEFAULT_PARTITIONS_CONSUMED_CONCURRENTLY;
		}

		const parsed = Number(raw);
		if (isSafePositiveInteger(parsed)) {
			return parsed;
		}

		this.logger.warn(
			`Invalid CHAT_MESSAGE_CONSUMER_CONCURRENCY value (${raw}), falling back to default (${DEFAULT_PARTITIONS_CONSUMED_CONCURRENTLY})`,
		);
		return DEFAULT_PARTITIONS_CONSUMED_CONCURRENTLY;
	}

	setSocketServer(io: Server): void {
		this.processor.setSocketServer(io);
	}

	async onModuleInit(): Promise<void> {
		this.consumer = await startCommittedConsumer({
			logger: this.logger,
			configService: this.configService,
			clientId: 'cowork-chat-consumer',
			groupId: 'cowork-chat',
			topic: CHAT_MESSAGE_TOPIC,
			fromBeginning: true,
			concurrency: this.resolveConcurrency(),
			handle: async record => this.processKafkaMessage(record),
		});
		this.logger.log(`Kafka consumer started: ${CHAT_MESSAGE_TOPIC}`);
	}

	async onModuleDestroy(): Promise<void> {
		await this.consumer?.close();
	}

	/**
     반환하면 컨슈머가 offset을 커밋할 수 있다. JSON/계약/범위 오류는 durable quarantine
     성공 뒤에만 반환하며, 저장소·Socket.IO·quarantine 오류는 반드시 전파한다.

     권한 projection이 준비되지 않으면 브로드캐스트할 수 없으므로 처리 전에 준비를 기다린다.
     */
	async processKafkaMessage({topic, partition, offset, key: eventKey, value: payload}: ConsumedRecord): Promise<void> {
		try {
			if (payload === null) {
				await this.quarantineService.quarantine({
					topic, partition, messageOffset: offset, eventKey, payload: null,
					contractVersion: CHAT_MESSAGE_CONTRACT_VERSION, errorType: 'JSON_ERROR', reasonCode: 'MISSING_VALUE',
					reason: 'Kafka message value is required',
				});
				return;
			}

			const event = validateChatMessageEvent(JSON.parse(payload) as unknown, eventKey);
			await waitForProjectionReadiness(this.projectionReadiness, CHAT_MESSAGE_REQUIRED_STREAMS);
			await this.processor.process(event);
		} catch (error) {
			const classification = classifyPoisonError(error);
			if (!classification) {
				throw error;
			}

			await this.quarantineService.quarantine({
				topic, partition, messageOffset: offset, eventKey, payload,
				contractVersion: CHAT_MESSAGE_CONTRACT_VERSION, ...classification,
			});
		}
	}
}

function classifyPoisonError(error: unknown): Nullable<{
	errorType: 'JSON_ERROR' | 'CONTRACT_ERROR' | 'SCOPE_ERROR'; reasonCode: string; reason: string;
}> {
	if (error instanceof SyntaxError) {
		return {errorType: 'JSON_ERROR', reasonCode: 'INVALID_JSON', reason: 'invalid JSON payload'};
	}

	if (error instanceof ChatMessageContractError) {
		return {errorType: 'CONTRACT_ERROR', reasonCode: error.reasonCode, reason: error.message};
	}

	return error instanceof ChatMessageScopeError ? {errorType: 'SCOPE_ERROR', reasonCode: error.reasonCode, reason: error.message} : null;
}
