import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { Server } from 'socket.io';
import { DicoshotService } from 'dicoshot-nest';
import { ChatService } from '../chat.service';
import { ProjectClient } from '../service/project.client';
import { GithubRepoEvent } from './event/github-repo.event';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { buildErrorFields } from '../../common/util/discord-alert.util';

/**
 * Kafka `github.repo.event` 토픽을 구독하여 GitHub 저장소 활동(push/issues/pull_request)을 처리하는 컨슈머.
 *
 * 이벤트가 발생한 저장소(`owner`/`repo`)에 연결된 프로젝트의 GitHub 알림 채널을 `project-service`에 조회하고,
 * 알림 채널이 지정되어 있으면 `summary`를 SYSTEM 메시지로 MongoDB에 저장한 후 Socket.IO로 브로드캐스트한다.
 * Socket.IO 서버 인스턴스는 `ChatGateway`의 `afterInit`에서 {@link setSocketServer}로 주입된다.
 */
@Injectable()
export class GithubRepoEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(GithubRepoEventConsumer.name);
    private consumer!: Consumer;
    private io?: Server;

    constructor(
        private readonly chatService: ChatService,
        private readonly projectClient: ProjectClient,
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
    ) {}

    /**
     * Socket.IO 서버 인스턴스를 주입한다.
     *
     * `ChatGateway`의 `afterInit` 훅에서 호출되어야 하며,
     * 이 메서드가 호출되기 전에 수신된 이벤트는 Socket.IO 브로드캐스트 없이 저장만 된다.
     *
     * @param io - Socket.IO 서버 인스턴스
     */
    setSocketServer(io: Server) {
        this.io = io;
    }

    /**
     * 모듈 초기화 시 Kafka 컨슈머를 시작하고 `github.repo.event` 토픽 구독을 설정한다.
     *
     * JSON 파싱 오류(`SyntaxError`)는 재처리 불가 메시지로 판단하여 무시한다.
     * 그 외 오류는 예외로 전파하여 Kafka offset을 커밋하지 않고 재전달을 유도한다.
     */
    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-github-repo-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        this.consumer = kafka.consumer({ groupId: 'cowork-chat-github-repo-event' });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: 'github.repo.event', fromBeginning: false });

        void this.consumer
            .run({
                eachMessage: async ({ message }) => {
                    if (!message.value) return;
                    try {
                        const event = JSON.parse(message.value.toString()) as GithubRepoEvent;
                        await this.handleRepoEvent(event);
                    } catch (err) {
                        this.logger.error('Failed to process repo event', err);
                        if (!(err instanceof SyntaxError)) throw err;
                    }
                },
            })
            .catch(async (err) => {
                this.logger.error('github.repo.event Kafka consumer failed', err);
                await this.dicoshot.sendCustom({
                    title: '🔴 Kafka Consumer 중단',
                    description: 'cowork-chat의 github.repo.event consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.',
                    color: 'danger',
                    fields: [
                        { name: 'Topic', value: 'github.repo.event', inline: true },
                        ...buildErrorFields(err),
                    ],
                }).catch(() => {});
                process.exit(1);
            });
        this.logger.log('Kafka consumer started: github.repo.event');
    }

    /**
     * 모듈 종료 시 Kafka 컨슈머 연결을 해제한다.
     */
    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    /**
     * GitHub 저장소 활동 이벤트를 처리한다.
     *
     * 서로 다른 팀이 같은 레포를 연결할 수 있으므로, 알림 채널이 설정된 대상 전부(0개 이상)에
     * `summary`를 SYSTEM 메시지로 저장하고 각 채널에 Socket.IO로 브로드캐스트한다.
     *
     * @param event - Kafka에서 수신한 GitHub 저장소 활동 이벤트
     */
    private async handleRepoEvent(event: GithubRepoEvent): Promise<void> {
        const targets = await this.projectClient.getGithubWebhookTargets(event.owner, event.repo);

        for (const target of targets) {
            const saved = await this.chatService.saveSystemMessage(target.teamId, target.channelId, event.summary, target.projectId);
            this.notifyClient(target.channelId, saved.toObject());
        }
    }

    /**
     * Socket.IO를 통해 특정 채널의 클라이언트에게 메시지를 브로드캐스트한다.
     *
     * Socket.IO 서버가 주입되지 않은 경우 (`io`가 `undefined`) 경고 로그를 남기고 무시된다.
     *
     * @param channelId - 브로드캐스트 대상 채널 ID
     * @param message - 전송할 메시지 객체
     */
    private notifyClient(channelId: number, message: unknown): void {
        if (!this.io) {
            this.logger.warn(`Socket.IO server not initialized yet, dropping message broadcast (channelId=${channelId})`);
            return;
        }
        this.io.to(`chat:${channelId}`).emit('message', message);
    }
}
