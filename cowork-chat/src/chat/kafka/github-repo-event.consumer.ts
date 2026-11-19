import {
	forwardRef,
	Inject as inject,
	Injectable as injectable,
	OnModuleDestroy,
	OnModuleInit,
	Logger,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Kafka, Consumer} from 'kafkajs';
import mongoose from 'mongoose';
import {Server} from 'socket.io';
import {ChatService} from '../chat.service.js';
import {ProjectClient, ChannelMessageReadAccessService} from '../service/index.js';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {waitForProjectionReadiness} from '../../common/kafka/projection-readiness-wait.js';
import {toMessageBroadcastPayload} from '../repository/index.js';
import {connectWithRetry} from '../../common/kafka/connect-with-retry.js';
import {type Nullable} from '../../common/type/nullable.js';
import {type ForwardReference} from '../../common/type/forward-reference.js';
import {GithubRepoEvent} from './event/github-repo.event.js';
import {type KafkaMessageSource, systemMessageKey} from './system-message-key.js';

/**
`Message.content`(`schema/message.schema.ts`)의 `maxlength` 제약과 동일하다.
*/
const MESSAGE_CONTENT_MAX_LENGTH = 25_000;

/**
 Kafka `github.repo.event` 토픽을 구독하여 GitHub 저장소 활동(push/issues/pull_request)을 처리하는 컨슈머.

 이벤트가 발생한 저장소(`owner`/`repo`)에 연결된 프로젝트의 GitHub 알림 채널을 로컬 projection에서 조회하고,
 알림 채널이 지정되어 있으면 `summary`를 SYSTEM 메시지로 MongoDB에 저장한 후 Socket.IO로 브로드캐스트한다.
 Socket.IO 서버 인스턴스는 `ChatGateway`의 `afterInit`에서 {@link setSocketServer}로 주입된다.
 */
@injectable()
export class GithubRepoEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(GithubRepoEventConsumer.name);
	private consumer!: Consumer;
	private io?: Server;

	@inject(forwardRef(() => ChatService))
	private readonly chatService!: ForwardReference<ChatService>;

	@inject()
	private readonly projectClient!: ProjectClient;

	@inject()
	private readonly configService!: ConfigService;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	@inject()
	private readonly channelMessageReadAccess!: ChannelMessageReadAccessService;

	/**
     GitHub 저장소 활동 이벤트를 처리한다.

     서로 다른 팀이 같은 레포를 연결할 수 있으므로, 알림 채널이 설정된 대상 전부(0개 이상)에
     `summary`를 SYSTEM 메시지로 저장하고 각 채널에 Socket.IO로 브로드캐스트한다.

     `source`(Kafka 메시지 위치)로 채널별 시스템 메시지 멱등성 키를 만든다.
     오래 걸리는 준비 대기 동안 `heartbeat`로 Kafka consumer heartbeat를 보낸다.
     */
	private async handleRepoEvent(event: GithubRepoEvent, source: KafkaMessageSource, heartbeat: () => Promise<void> = async () => {/* Direct calls need no Kafka heartbeat. */}): Promise<void> {
		const summary = this.sanitizeSummary(event.summary);
		if (summary === null) {
			this.logger.warn(`Skipping repo event with empty summary owner=${event.owner} repo=${event.repo}`);
			return;
		}

		await waitForProjectionReadiness(this.projectionReadiness, heartbeat);
		if (!this.projectionReadiness.isReady()) {
			throw new Error('Kafka projections became unavailable while processing github.repo.event');
		}

		const targets = await this.projectClient.getGithubWebhookTargets(event.owner, event.repo);

		await Promise.all(targets.map(async target => {
			const saved = await this.chatService.saveSystemMessage(
				target.teamId, target.channelId, summary, systemMessageKey(source, target.channelId),
			);
			if (!saved) {
				return;
			}

			await this.notifyClient(target.channelId, toMessageBroadcastPayload(saved));
		}));
	}

	/**
     `Message.content`는 required + maxlength 25000이다. 빈 값은 저장 자체를 스킵하고(`null` 반환),
     초과분은 잘라서 ValidationError로 인한 무한 재전달을 막는다.
     */
	private sanitizeSummary(summary: string): Nullable<string> {
		const trimmed = summary?.trim();
		if (trimmed === undefined || trimmed === '') {
			return null;
		}

		return trimmed.length > MESSAGE_CONTENT_MAX_LENGTH ? trimmed.slice(0, MESSAGE_CONTENT_MAX_LENGTH) : trimmed;
	}

	/**
     Socket.IO를 통해 특정 채널의 클라이언트에게 메시지를 브로드캐스트한다.

     Socket.IO 서버가 주입되지 않은 경우 (`io`가 `undefined`) 경고 로그를 남기고 무시된다.
     */
	private async notifyClient(channelId: number, message: unknown): Promise<void> {
		if (!this.io) {
			this.logger.warn(`Socket.IO server not initialized yet, dropping message broadcast (channelId=${channelId})`);
			return;
		}

		await this.channelMessageReadAccess.emitToReadableChannelUsers(this.io, channelId, {event: 'message', payload: message});
	}

	/**
     Socket.IO 서버 인스턴스를 주입한다.

     `ChatGateway`의 `afterInit` 훅에서 호출되어야 하며,
     이 메서드가 호출되기 전에 수신된 이벤트는 Socket.IO 브로드캐스트 없이 저장만 된다.
     */
	setSocketServer(io: Server) {
		this.io = io;
	}

	/**
     모듈 초기화 시 Kafka 컨슈머를 시작하고 `github.repo.event` 토픽 구독을 설정한다.

     JSON 파싱 오류(`SyntaxError`)는 재처리 불가 메시지로 판단하여 무시한다.
     그 외 오류는 예외로 전파하여 Kafka offset을 커밋하지 않고 재전달을 유도한다.
     */
	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-chat-github-repo-event',
			...getKafkaClientConfig(this.configService),
		});
		this.consumer = kafka.consumer({groupId: 'cowork-chat-github-repo-event'});
		await connectWithRetry(this.logger, 'Kafka consumer startup: github.repo.event', async () => {
			await this.consumer.connect();
			await this.consumer.subscribe({topic: 'github.repo.event', fromBeginning: false});
		});

		void this.consumer
			.run({
				eachMessage: async payload => {
					const {topic, partition, message} = payload;
					if (!message.value) {
						return;
					}

					try {
						const event = JSON.parse(message.value.toString()) as GithubRepoEvent;
						await this.handleRepoEvent(event, {topic, partition, offset: message.offset}, async () => payload.heartbeat());
					} catch (error) {
						this.logger.error('Failed to process repo event', error);
						// 잘못된 JSON, Message 스키마 검증 실패(빈 값/길이 초과 등)는 재시도해도 성공할 수 없으므로 스킵한다.
						if (!(error instanceof SyntaxError) && !(error instanceof mongoose.Error.ValidationError)) {
							throw error;
						}
					}
				},
			})
			.catch(async (error: unknown) => {
				this.logger.error('github.repo.event Kafka consumer failed', error);
				// 처리되지 않은 rejection으로 남겨 프로세스를 종료하고 재시작을 맡긴다.
				throw error;
			});
		this.logger.log('Kafka consumer started: github.repo.event');
	}

	/**
     모듈 종료 시 Kafka 컨슈머 연결을 해제한다.
     */
	async onModuleDestroy() {
		await this.consumer.disconnect();
	}
}
