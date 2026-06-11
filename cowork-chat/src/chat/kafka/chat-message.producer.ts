import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Producer } from 'kafkajs';
import { SendMessageDto } from '../dto/send-message.dto';
import { ChatMessageEvent } from './event/chat-message.event';
import { getRequiredCsvConfig } from '../../common/config/config.util';

/**
 * 채팅 메시지 이벤트를 Kafka `chat.message` 토픽으로 발행하는 프로듀서.
 *
 * **지연 연결 패턴(Lazy Connect)**:
 * `onModuleInit`에서 연결을 시작하지만 실패해도 모듈 초기화는 정상 완료된다.
 * 실제 메시지 발행 시점에 {@link ensureConnected}를 호출하여 연결 상태를 보장한다.
 */
@Injectable()
export class ChatMessageProducer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChatMessageProducer.name);
    private producer!: Producer;
    private isConnected = false;
    private connectPromise?: Promise<void>;

    constructor(private readonly configService: ConfigService) {}

    /**
     * 모듈 초기화 시 Kafka 프로듀서 인스턴스를 생성하고 백그라운드 연결을 시도한다.
     *
     * 연결 실패는 오류 로그로만 기록되며, 모듈 초기화 자체는 성공으로 처리된다.
     * 실제 연결은 첫 번째 메시지 발행 시 {@link ensureConnected}를 통해 재시도된다.
     */
    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.producer = kafka.producer();
        void this.ensureConnected().catch((error: unknown) => {
            this.logger.error(`Kafka producer bootstrap connect failed: ${this.formatError(error)}`);
        });
    }

    /**
     * 모듈 종료 시 Kafka 프로듀서 연결을 해제한다.
     *
     * 연결된 상태일 때만 disconnect를 호출한다.
     */
    async onModuleDestroy() {
        if (this.isConnected) {
            await this.producer.disconnect();
        }
    }

    /**
     * 채팅 메시지 이벤트를 Kafka `chat.message` 토픽으로 발행한다.
     *
     * 메시지 키는 `channelId`로 설정되어 같은 채널의 메시지가 동일 파티션에 순서대로 전달된다.
     * 발행 전 {@link ensureConnected}를 호출하여 연결을 보장한다.
     *
     * @param channelId - 메시지가 속한 채널 ID (파티션 키로 사용됨)
     * @param dto - 클라이언트로부터 수신한 메시지 전송 DTO
     * @param authorId - 메시지 작성자의 사용자 ID
     * @param authorRole - 메시지 작성자의 역할
     * @throws {Error} Kafka 연결 또는 메시지 발행 실패 시
     */
    async sendMessage(channelId: number, dto: SendMessageDto, authorId: number, authorRole: string): Promise<void> {
        await this.ensureConnected();
        const event: ChatMessageEvent = {
            eventType: 'MESSAGE_SENT',
            teamId: dto.teamId ?? null,
            projectId: dto.projectId ?? null,
            channelId,
            authorId,
            authorRole,
            content: dto.content,
            type: dto.type ?? 'TEXT',
            attachments: dto.attachments,
            parentMessageId: dto.parentMessageId,
            clientMessageId: dto.clientMessageId,
            occurredAt: new Date().toISOString(),
        };

        await this.producer.send({
            topic: 'chat.message',
            messages: [
                {
                    key: channelId.toString(),
                    value: JSON.stringify(event),
                },
            ],
        });
    }

    /**
     * Kafka 프로듀서의 연결 상태를 보장한다.
     *
     * 이미 연결된 경우 즉시 반환한다.
     * 연결 중인 경우 기존 `connectPromise`를 재사용하여 중복 연결 시도를 방지한다(싱글턴 패턴).
     * 연결에 실패하면 `connectPromise`를 초기화하여 이후 재시도를 허용한다.
     *
     * @returns 연결 완료 후 이행되는 Promise
     * @throws {Error} Kafka 브로커 연결 실패 시
     */
    private ensureConnected(): Promise<void> {
        if (this.isConnected) {
            return Promise.resolve();
        }

        if (!this.connectPromise) {
            this.connectPromise = this.producer
                .connect()
                .then(() => {
                    this.isConnected = true;
                    this.logger.log('Kafka producer connected');
                })
                .catch((error: unknown) => {
                    this.connectPromise = undefined;
                    throw error;
                });
        }

        return this.connectPromise;
    }

    private formatError(error: unknown): string {
        return error instanceof Error ? error.message : String(error);
    }
}
