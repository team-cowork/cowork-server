import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import mongoose from 'mongoose';
import { Server } from 'socket.io';
import { DicoshotService } from 'dicoshot-nest';
import { ChatService } from '../chat.service';
import { ProjectClient } from '../service/project.client';
import { GithubRepoEvent } from './event/github-repo.event';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { buildErrorFields } from '../../common/util/discord-alert.util';
import { ProjectionReadinessService } from '../../common/kafka/projection-readiness.service';

/** `Message.content`(`schema/message.schema.ts`)의 `maxlength` 제약과 동일하다. */
const MESSAGE_CONTENT_MAX_LENGTH = 25000;
const PROJECTION_WAIT_HEARTBEAT_INTERVAL_MS = 5000;

/**
 * Kafka `github.repo.event` 토픽을 구독하여 GitHub 저장소 활동(push/issues/pull_request)을 처리하는 컨슈머.
 *
 * 이벤트가 발생한 저장소(`owner`/`repo`)에 연결된 프로젝트의 GitHub 알림 채널을 로컬 projection에서 조회하고,
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
        private readonly projectionReadiness: ProjectionReadinessService,
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
                eachMessage: async (payload) => {
                    const { message } = payload;
                    if (!message.value) return;
                    try {
                        const event = JSON.parse(message.value.toString()) as GithubRepoEvent;
                        await this.handleRepoEvent(event, () => payload.heartbeat());
                    } catch (err) {
                        this.logger.error('Failed to process repo event', err);
                        // 잘못된 JSON, Message 스키마 검증 실패(빈 값/길이 초과 등)는 재시도해도 성공할 수 없으므로 스킵한다.
                        if (!(err instanceof SyntaxError) && !(err instanceof mongoose.Error.ValidationError)) throw err;
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
    private async handleRepoEvent(event: GithubRepoEvent, heartbeat: () => Promise<void> = async () => {}): Promise<void> {
        const summary = this.sanitizeSummary(event.summary);
        if (summary === null) {
            this.logger.warn(`Skipping repo event with empty summary owner=${event.owner} repo=${event.repo}`);
            return;
        }

        await this.waitForProjectionReadiness(heartbeat);
        if (!this.projectionReadiness.isReady()) {
            throw new Error('Kafka projections became unavailable while processing github.repo.event');
        }
        const targets = await this.projectClient.getGithubWebhookTargets(event.owner, event.repo);

        for (const target of targets) {
            const saved = await this.chatService.saveSystemMessage(target.teamId, target.channelId, summary, target.projectId);
            this.notifyClient(target.channelId, saved.toObject());
        }
    }

    /**
     * 초기 projection 적재가 Kafka session timeout보다 길어져도 consumer가 재조정되지 않도록
     * 준비 상태를 기다리는 동안 주기적으로 heartbeat를 전송한다.
     */
    private async waitForProjectionReadiness(heartbeat: () => Promise<void>): Promise<void> {
        await this.waitWithHeartbeat(this.projectionReadiness.checkCatchup(), heartbeat);
        while (!this.projectionReadiness.isReady()) {
            await this.waitWithHeartbeat(this.projectionReadiness.whenReady(), heartbeat);
        }
    }

    private async waitWithHeartbeat(operation: Promise<void>, heartbeat: () => Promise<void>): Promise<void> {
        while (true) {
            let timer: ReturnType<typeof setTimeout> | undefined;
            const completed = operation.then(() => true);
            const heartbeatInterval = new Promise<boolean>((resolve) => {
                timer = setTimeout(() => resolve(false), PROJECTION_WAIT_HEARTBEAT_INTERVAL_MS);
            });

            try {
                if (await Promise.race([completed, heartbeatInterval])) return;
            } finally {
                if (timer) clearTimeout(timer);
            }
            await heartbeat();
        }
    }

    /**
     * `Message.content`는 required + maxlength 25000이다. 빈 값은 저장 자체를 스킵하고(`null` 반환),
     * 초과분은 잘라서 ValidationError로 인한 무한 재전달을 막는다.
     */
    private sanitizeSummary(summary: string): string | null {
        const trimmed = summary?.trim();
        if (!trimmed) return null;
        return trimmed.length > MESSAGE_CONTENT_MAX_LENGTH ? trimmed.slice(0, MESSAGE_CONTENT_MAX_LENGTH) : trimmed;
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
