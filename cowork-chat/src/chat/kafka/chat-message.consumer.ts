import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { Types } from 'mongoose';
import { Server } from 'socket.io';
import { ChatMessageEvent } from './event/chat-message.event';
import { ElasticsearchService } from '../../search/elasticsearch.service';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { MessageRepository } from '../repository/message.repository';

/**
 * Kafka `chat.message` 토픽을 구독하여 채팅 메시지를 처리하는 컨슈머.
 *
 * 메시지를 MongoDB에 저장한 후 Socket.IO로 실시간 브로드캐스트하며,
 * `projectId`가 있는 경우 Elasticsearch에 추가로 인덱싱한다.
 * Socket.IO 서버 인스턴스는 `ChatGateway`의 `afterInit`에서 {@link setSocketServer}로 주입된다.
 */
@Injectable()
export class ChatMessageConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChatMessageConsumer.name);
    private consumer!: Consumer;

    private io?: Server;

    constructor(
        private readonly messageRepository: MessageRepository,
        private readonly configService: ConfigService,
        private readonly elasticsearchService: ElasticsearchService,
    ) {}

    /**
     * Socket.IO 서버 인스턴스를 주입한다.
     *
     * `ChatGateway`의 `afterInit` 훅에서 호출되어야 하며,
     * 이 메서드가 호출되기 전에 수신된 메시지는 Socket.IO 브로드캐스트 없이 저장만 된다.
     *
     * @param io - Socket.IO 서버 인스턴스
     */
    setSocketServer(io: Server) {
        this.io = io;
    }

    /**
     * 모듈 초기화 시 Kafka 컨슈머를 시작하고 `chat.message` 토픽 구독을 설정한다.
     *
     * 컨슈머 실행 중 복구 불가능한 오류가 발생하면 `process.exit(1)`로 프로세스를 종료한다.
     * JSON 파싱 오류(`SyntaxError`)는 재처리 불가 메시지로 판단하여 무시하고 다음 메시지로 진행한다.
     */
    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-consumer',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.consumer = kafka.consumer({ groupId: 'cowork-chat' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: 'chat.message', fromBeginning: false });

        void this.consumer
            .run({
                eachMessage: async ({ message }) => {
                    if (!message.value) return;
                    try {
                        const event: ChatMessageEvent = JSON.parse(message.value.toString());
                        await this.handleMessageEvent(event);
                    } catch (err) {
                        this.logger.error('Kafka 메시지 처리 중 예외 발생', err);
                        if (!(err instanceof SyntaxError)) throw err;
                    }
                },
            })
            .catch((err) => {
                this.logger.error('chat.message Kafka consumer 실행 실패', err);
                process.exit(1);
            });
        this.logger.log('Kafka consumer started: chat.message');
    }

    /**
     * 모듈 종료 시 Kafka 컨슈머 연결을 해제한다.
     */
    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    /**
     * 메시지 내용에서 멘션된 사용자 ID 목록을 파싱한다.
     *
     * `<@userId>` 형식의 패턴을 인식하며, 중복 ID는 제거된다.
     *
     * @param content - 파싱할 메시지 내용
     * @returns 멘션된 사용자 ID 배열 (중복 제거됨)
     */
    private parseMentions(content: string): number[] {
        const ids: number[] = [];
        for (const match of content.matchAll(/<@(\d+)>/g)) {
            ids.push(parseInt(match[1], 10));
        }
        return [...new Set(ids)];
    }

    /**
     * 수신된 채팅 메시지 이벤트를 처리한다.
     *
     * 처리 순서:
     * 1. 메시지를 MongoDB에 저장한다.
     * 2. 저장 성공 시 Socket.IO `chat:{channelId}` 룸에 `message` 이벤트를 브로드캐스트한다.
     * 3. `projectId`가 있으면 Elasticsearch에 비동기로 인덱싱한다 (실패해도 전체 처리에 영향 없음).
     *
     * MongoDB 중복 키 오류 (error code 11000)가 발생하면 중복 메시지로 판단하여
     * 경고 로그를 남기고 정상 처리로 간주한다 (Kafka offset 커밋됨).
     * 그 외 오류는 예외로 전파하여 Kafka offset을 커밋하지 않고 재전달을 유도한다.
     *
     * @param event - Kafka에서 수신한 채팅 메시지 이벤트
     * @throws {Error} 중복 키 오류 외의 메시지 저장 실패 시
     */
    private async handleMessageEvent(event: ChatMessageEvent): Promise<void> {
        const content = event.content ?? '';
        const mentions = this.parseMentions(content);

        this.logger.log(`message received channelId=${event.channelId} authorId=${event.authorId} type=${event.type} contentLength=${content.length}`);

        try {
            const saved = await this.messageRepository.createMessage({
                teamId: event.teamId,
                projectId: event.projectId ?? null,
                channelId: event.channelId,
                authorId: event.authorId,
                content,
                type: event.type,
                attachments: event.attachments ?? [],
                parentMessageId: (event.parentMessageId && Types.ObjectId.isValid(event.parentMessageId))
                    ? new Types.ObjectId(event.parentMessageId)
                    : null,
                clientMessageId: event.clientMessageId,
                mentions,
                notificationStatus: 'PENDING',
            });

            this.logger.log(`message saved messageId=${saved._id} channelId=${event.channelId}`);
            this.io?.to(`chat:${event.channelId}`).emit('message', saved.toObject());

            if (event.projectId) {
                void this.elasticsearchService.indexMessage({
                    messageId: saved._id.toString(),
                    teamId: event.teamId,
                    projectId: event.projectId,
                    channelId: event.channelId,
                    authorId: event.authorId,
                    content,
                    type: event.type,
                    hasAttachments: (event.attachments?.length ?? 0) > 0,
                    isPinned: false,
                    createdAt: event.occurredAt,
                });
            }
        } catch (err: any) {
            if (err?.code === 11000) {
                this.logger.warn(`중복 메시지 감지, 스킵합니다 (clientMessageId: ${event.clientMessageId})`);
                return;
            }
            this.logger.error('메시지 저장 실패 — offset 미커밋으로 Kafka 재전달 예정', err);
            throw err;
        }
    }
}
