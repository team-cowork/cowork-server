import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { Server } from 'socket.io';
import { DicoshotService } from 'dicoshot-nest';
import { ChatService } from '../chat.service';
import { GithubIssueResultEvent } from './event/github-issue.event';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { buildErrorFields } from '../../common/util/discord-alert.util';

/**
 * Kafka `github.issue.result` 토픽을 구독하여 GitHub 이슈 생성 결과를 처리하는 컨슈머.
 *
 * 이슈 생성 결과를 SYSTEM 메시지로 MongoDB에 저장한 후 Socket.IO로 브로드캐스트한다.
 * Socket.IO 서버 인스턴스는 `ChatGateway`의 `afterInit`에서 {@link setSocketServer}로 주입된다.
 */
@Injectable()
export class GithubIssueResultConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(GithubIssueResultConsumer.name);
    private consumer!: Consumer;
    private io?: Server;

    constructor(
        private readonly chatService: ChatService,
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
    ) {}

    /**
     * Socket.IO 서버 인스턴스를 주입한다.
     *
     * `ChatGateway`의 `afterInit` 훅에서 호출되어야 하며,
     * 이 메서드가 호출되기 전에 수신된 결과는 Socket.IO 브로드캐스트 없이 저장만 된다.
     *
     * @param io - Socket.IO 서버 인스턴스
     */
    setSocketServer(io: Server) {
        this.io = io;
    }

    /**
     * 모듈 초기화 시 Kafka 컨슈머를 시작하고 `github.issue.result` 토픽 구독을 설정한다.
     *
     * JSON 파싱 오류(`SyntaxError`)는 재처리 불가 메시지로 판단하여 무시한다.
     * 그 외 오류는 예외로 전파하여 Kafka offset을 커밋하지 않고 재전달을 유도한다.
     */
    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-github-result',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.consumer = kafka.consumer({ groupId: 'cowork-chat-github-result' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: 'github.issue.result', fromBeginning: false });

        void this.consumer
            .run({
                eachMessage: async ({ message }) => {
                    if (!message.value) return;
                    try {
                        const event = JSON.parse(message.value.toString()) as GithubIssueResultEvent;
                        await this.handleResultEvent(event);
                    } catch (err) {
                        this.logger.error('이슈 결과 처리 중 오류 발생', err);
                        if (!(err instanceof SyntaxError)) throw err;
                    }
                },
            })
            .catch((err) => {
                this.logger.error('github.issue.result Kafka consumer 실행 실패', err);
                void this.dicoshot.sendCustom({
                    title: '🔴 Kafka Consumer 중단',
                    description: 'cowork-chat의 github.issue.result consumer가 복구 불가능한 오류로 종료됐습니다.',
                    color: 'danger',
                    fields: [
                        { name: 'Topic', value: 'github.issue.result', inline: true },
                        ...buildErrorFields(err),
                    ],
                }).catch(() => {});
            });
        this.logger.log('Kafka consumer started: github.issue.result');
    }

    /**
     * 모듈 종료 시 Kafka 컨슈머 연결을 해제한다.
     */
    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    /**
     * GitHub 이슈 생성 결과 이벤트를 처리한다.
     *
     * 성공/실패 여부에 따라 포맷된 SYSTEM 메시지를 MongoDB에 저장하고,
     * Socket.IO `chat:{channelId}` 룸에 `message` 이벤트를 브로드캐스트한다.
     *
     * @param event - Kafka에서 수신한 GitHub 이슈 생성 결과 이벤트
     */
    private async handleResultEvent(event: GithubIssueResultEvent): Promise<void> {
        const content = this.formatIssueResultMessage(event);

        const saved = await this.chatService.saveSystemMessage(
            event.teamId,
            event.channelId,
            content,
            event.projectId ?? null,
        );

        this.notifyClient(event.channelId, saved.toObject());
    }

    /**
     * GitHub 이슈 생성 결과를 사용자에게 표시할 메시지 문자열로 포맷한다.
     *
     * 성공 시 `✅ 이슈가 생성됐어요: {issueUrl}` 형식으로 반환한다.
     * 실패 시 `❌ 이슈 생성 실패: {error}` 형식으로 반환한다.
     *
     * @param event - 포맷할 GitHub 이슈 생성 결과 이벤트
     * @returns 포맷된 시스템 메시지 문자열
     */
    private formatIssueResultMessage(event: GithubIssueResultEvent): string {
        if (event.success) {
            return `✅ 이슈가 생성됐어요: ${event.issueUrl}`;
        }
        return `❌ 이슈 생성 실패: ${event.error ?? '알 수 없는 오류'}`;
    }

    /**
     * Socket.IO를 통해 특정 채널의 클라이언트에게 메시지를 브로드캐스트한다.
     *
     * Socket.IO 서버가 주입되지 않은 경우 (`io`가 `undefined`) 조용히 무시된다.
     *
     * @param channelId - 브로드캐스트 대상 채널 ID
     * @param message - 전송할 메시지 객체
     */
    private notifyClient(channelId: number, message: unknown): void {
        this.io?.to(`chat:${channelId}`).emit('message', message);
    }
}
