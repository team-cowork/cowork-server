import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { Server } from 'socket.io';
import { DicoshotService } from 'dicoshot-nest';
import { ChatService } from '../chat.service';
import { ChatGithubIssueCreateResult } from './event/chat-github-issue.event';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { buildErrorFields } from '../../common/util/discord-alert.util';
import { ChannelMessageReadAccessService } from '../service/channel-message-read-access.service';
import { toMessageBroadcastPayload } from '../repository/message.repository';

/**
 * Kafka `project.chat-github-issue.result` 토픽을 구독하여
 * project의 채팅발 GitHub 이슈 생성 커맨드 권한 검증 결과를 처리하는 컨슈머.
 *
 * `REJECTED`인 경우에만 거부 사유를 SYSTEM 메시지로 저장하고 Socket.IO로 브로드캐스트한다.
 * `ACCEPTED`는 권한 검증 통과만을 의미하므로 아무 동작도 하지 않는다(실제 이슈 생성 성공/실패는
 * 기존 `github.issue.result` 토픽/`GithubIssueResultConsumer`가 그대로 처리한다).
 * Socket.IO 서버 인스턴스는 `ChatGateway`의 `afterInit`에서 {@link setSocketServer}로 주입된다.
 */
@Injectable()
export class ChatGithubIssueResultConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChatGithubIssueResultConsumer.name);
    private consumer!: Consumer;
    private io?: Server;

    constructor(
        private readonly chatService: ChatService,
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
        private readonly channelMessageReadAccess: ChannelMessageReadAccessService,
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
     * 모듈 초기화 시 Kafka 컨슈머를 시작하고 `project.chat-github-issue.result` 토픽 구독을 설정한다.
     *
     * JSON 파싱 오류(`SyntaxError`)는 재처리 불가 메시지로 판단하여 무시한다.
     * 그 외 오류는 예외로 전파하여 Kafka offset을 커밋하지 않고 재전달을 유도한다.
     */
    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-chat-github-issue-result',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.consumer = kafka.consumer({ groupId: 'cowork-chat-chat-github-issue-result' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: 'project.chat-github-issue.result', fromBeginning: false });

        void this.consumer
            .run({
                eachMessage: async ({ message }) => {
                    if (!message.value) return;
                    try {
                        const event = JSON.parse(message.value.toString()) as ChatGithubIssueCreateResult;
                        await this.handleResultEvent(event);
                    } catch (err) {
                        this.logger.error('Failed to process chat GitHub issue command result event', err);
                        if (!(err instanceof SyntaxError)) throw err;
                    }
                },
            })
            .catch(async (err) => {
                this.logger.error('project.chat-github-issue.result Kafka consumer failed', err);
                await this.dicoshot.sendCustom({
                    title: '🔴 Kafka Consumer 중단',
                    description: 'cowork-chat의 project.chat-github-issue.result consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.',
                    color: 'danger',
                    fields: [
                        { name: 'Topic', value: 'project.chat-github-issue.result', inline: true },
                        ...buildErrorFields(err),
                    ],
                }).catch(() => {});
                process.exit(1);
            });
        this.logger.log('Kafka consumer started: project.chat-github-issue.result');
    }

    /**
     * 모듈 종료 시 Kafka 컨슈머 연결을 해제한다.
     */
    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    /**
     * 채팅발 GitHub 이슈 생성 커맨드의 권한 검증 결과 이벤트를 처리한다.
     *
     * `REJECTED`일 때만 거부 SYSTEM 메시지를 MongoDB에 저장하고,
     * Socket.IO `chat:{channelId}` 룸에 `message` 이벤트를 브로드캐스트한다.
     * `ACCEPTED`는 디버그 로그만 남기고 아무 동작도 하지 않는다.
     *
     * @param event - Kafka에서 수신한 권한 검증 결과 이벤트
     */
    private async handleResultEvent(event: ChatGithubIssueCreateResult): Promise<void> {
        if (event.status !== 'REJECTED') {
            this.logger.debug(`Chat GitHub issue command accepted [operationId=${event.operationId}]`);
            return;
        }

        const content = this.formatRejectionMessage(event);

        const saved = await this.chatService.saveSystemMessage(
            event.teamId,
            event.channelId,
            content,
            event.projectId,
        );

        await this.notifyClient(event.channelId, toMessageBroadcastPayload(saved));
    }

    /**
     * 거부 결과를 사용자에게 표시할 메시지 문자열로 포맷한다.
     *
     * `❌ 이슈 생성 실패: {error.message}` 형식으로 반환한다.
     *
     * @param event - 포맷할 결과 이벤트
     * @returns 포맷된 시스템 메시지 문자열
     */
    private formatRejectionMessage(event: ChatGithubIssueCreateResult): string {
        return `❌ 이슈 생성 실패: ${event.error?.message ?? '알 수 없는 오류'}`;
    }

    /**
     * Socket.IO를 통해 특정 채널의 클라이언트에게 메시지를 브로드캐스트한다.
     *
     * Socket.IO 서버가 주입되지 않은 경우 (`io`가 `undefined`) 경고 로그를 남기고 무시된다.
     *
     * @param channelId - 브로드캐스트 대상 채널 ID
     * @param message - 전송할 메시지 객체
     */
    private async notifyClient(channelId: number, message: unknown): Promise<void> {
        if (!this.io) {
            this.logger.warn(`Socket.IO server not initialized yet, dropping message broadcast (channelId=${channelId})`);
            return;
        }
        await this.channelMessageReadAccess.emitToReadableChannelUsers(this.io, channelId, 'message', message);
    }
}
