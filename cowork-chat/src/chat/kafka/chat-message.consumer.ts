import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Consumer, Kafka, KafkaMessage } from 'kafkajs';
import { DicoshotService } from 'dicoshot-nest';
import { Server } from 'socket.io';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { buildErrorFields } from '../../common/util/discord-alert.util';
import { ChatMessageContractError, validateChatMessageEvent } from './event/chat-message-contract';
import { ChatMessageScopeError } from './chat-message-scope-validator';
import { ChatMessageProcessor } from './chat-message.processor';
import { ChatMessageQuarantineService } from '../service/chat-message-quarantine.service';

const CHAT_MESSAGE_TOPIC = 'chat.message';

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

    async onModuleInit(): Promise<void> {
        const kafka = new Kafka({
            clientId: 'cowork-chat-consumer',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.consumer = kafka.consumer({ groupId: 'cowork-chat' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: CHAT_MESSAGE_TOPIC, fromBeginning: false });
        void this.consumer.run({
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
