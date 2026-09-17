import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Consumer, Kafka, KafkaMessage } from 'kafkajs';
import { DicoshotService } from 'dicoshot-nest';
import { Server } from 'socket.io';
import { getOptionalConfig, getRequiredCsvConfig } from '../../common/config/config.util';
import { buildErrorFields } from '../../common/util/discord-alert.util';
import { isSafePositiveInteger } from '../../common/util/safe-integer.util';
import { ChatMessageContractError, validateChatMessageEvent } from './event/chat-message-contract';
import { ChatMessageScopeError } from './chat-message-scope-validator';
import { ChatMessageProcessor } from './chat-message.processor';
import { ChatMessageQuarantineService } from '../service/chat-message-quarantine.service';

const CHAT_MESSAGE_TOPIC = 'chat.message';
/**
 * 파티션당 하나씩 순차 처리하는 KafkaJS 기본값(1) 대신, 서로 다른 채널(=파티션)의 메시지를
 * 동시에 처리하기 위한 동시성. `channelId`가 파티션 키이므로 채널 내 순서는 그대로 유지된다.
 * 실제 파티션 수보다 큰 값을 주면 KafkaJS가 초과분을 사용하지 않으므로 상한으로 안전하게 둔다.
 */
const DEFAULT_PARTITIONS_CONSUMED_CONCURRENTLY = 3;

/** Kafka `chat.message` record를 검증하고, poison record만 durable quarantine한다. */
@Injectable()
export class ChatMessageConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChatMessageConsumer.name);
    private consumer?: Consumer;

    constructor(
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
        private readonly processor: ChatMessageProcessor,
        private readonly quarantineService: ChatMessageQuarantineService,
    ) {}

    setSocketServer(io: Server): void {
        this.processor.setSocketServer(io);
    }

    /**
     * `CHAT_MESSAGE_CONSUMER_CONCURRENCY`를 안전한 양의 정수로 해석한다.
     *
     * 정수가 아니거나 1 미만이면(예: 오타로 `abc`, `2.5`, `-1`, `0`) 그대로 KafkaJS에 넘기지 않는다.
     * `0`은 예외 없이 워커 0개로 이어져 컨슈머가 아무 메시지도 처리하지 못한 채 조용히 멈추고,
     * 그 외 잘못된 값은 KafkaJS 내부에서 `RangeError`를 던져 `run()`이 거부되고 프로세스가 재시작
     * 크래시 루프에 빠진다. 값이 유효하지 않으면 경고 로그만 남기고 기본값으로 대체한다.
     */
    private resolveConcurrency(): number {
        const raw = getOptionalConfig(this.configService, 'CHAT_MESSAGE_CONSUMER_CONCURRENCY');
        if (raw === undefined) return DEFAULT_PARTITIONS_CONSUMED_CONCURRENTLY;
        const parsed = Number(raw);
        if (isSafePositiveInteger(parsed)) return parsed;
        this.logger.warn(
            `Invalid CHAT_MESSAGE_CONSUMER_CONCURRENCY value (${raw}), falling back to default (${DEFAULT_PARTITIONS_CONSUMED_CONCURRENTLY})`,
        );
        return DEFAULT_PARTITIONS_CONSUMED_CONCURRENTLY;
    }

    async onModuleInit(): Promise<void> {
        const kafka = new Kafka({
            clientId: 'cowork-chat-consumer',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.consumer = kafka.consumer({ groupId: 'cowork-chat' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: CHAT_MESSAGE_TOPIC, fromBeginning: false });
        const partitionsConsumedConcurrently = this.resolveConcurrency();
        void this.consumer.run({
            partitionsConsumedConcurrently,
            eachMessage: ({ topic, partition, message }) => this.processKafkaMessage(topic, partition, message),
        }).catch(async (error) => {
            this.logger.error('chat.message Kafka consumer failed', error);
            await this.dicoshot.sendCustom({
                title: '🔴 Kafka Consumer 중단',
                description: 'cowork-chat의 chat.message consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.',
                color: 'danger',
                fields: [{ name: 'Topic', value: CHAT_MESSAGE_TOPIC, inline: true }, ...buildErrorFields(error)],
            }).catch(() => {});
            process.exit(1);
        });
        this.logger.log(`Kafka consumer started: ${CHAT_MESSAGE_TOPIC}`);
    }

    async onModuleDestroy(): Promise<void> {
        await this.consumer?.disconnect();
    }

    /**
     * 반환하면 KafkaJS가 offset을 커밋할 수 있다. JSON/계약/범위 오류는 durable quarantine
     * 성공 뒤에만 반환하며, 저장소·Socket.IO·quarantine 오류는 반드시 전파한다.
     */
    async processKafkaMessage(topic: string, partition: number, message: KafkaMessage): Promise<void> {
        const eventKey = message.key?.toString() ?? null;
        const payload = message.value?.toString() ?? null;
        try {
            if (payload === null) {
                await this.quarantineService.quarantine({
                    topic, partition, messageOffset: message.offset, eventKey, payload: null,
                    contractVersion: 1, errorType: 'JSON_ERROR', reasonCode: 'MISSING_VALUE',
                    reason: 'Kafka message value is required',
                });
                return;
            }
            const event = validateChatMessageEvent(JSON.parse(payload) as unknown, eventKey);
            await this.processor.process(event);
        } catch (error) {
            const classification = classifyPoisonError(error);
            if (!classification) throw error;
            await this.quarantineService.quarantine({
                topic, partition, messageOffset: message.offset, eventKey, payload,
                contractVersion: 1, ...classification,
            });
        }
    }
}

function classifyPoisonError(error: unknown): {
    errorType: 'JSON_ERROR' | 'CONTRACT_ERROR' | 'SCOPE_ERROR'; reasonCode: string; reason: string;
} | null {
    if (error instanceof SyntaxError) return { errorType: 'JSON_ERROR', reasonCode: 'INVALID_JSON', reason: 'invalid JSON payload' };
    if (error instanceof ChatMessageContractError) {
        return { errorType: 'CONTRACT_ERROR', reasonCode: error.reasonCode, reason: error.message };
    }
    if (error instanceof ChatMessageScopeError) {
        return { errorType: 'SCOPE_ERROR', reasonCode: error.reasonCode, reason: error.message };
    }
    return null;
}
