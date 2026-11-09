import {
	forwardRef,
	Inject as inject,
	Injectable as injectable,
	OnModuleDestroy,
	OnModuleInit,
	Logger,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer} from 'kafkajs';
import {Server} from 'socket.io';
import {ChatService} from '../chat.service.js';
import {ChannelMessageReadAccessService} from '../service/index.js';
import {toMessageBroadcastPayload} from '../repository/index.js';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {type ForwardReference} from '../../common/type/forward-reference.js';
import {GithubIssueResultEvent} from './event/github-issue.event.js';
import {type KafkaMessageSource, systemMessageKey} from './system-message-key.js';
import {startSystemMessageConsumer, SystemMessageBroadcaster} from './system-message-result-consumer.js';

/**
 Kafka `github.issue.result` 토픽을 구독하여 GitHub 이슈 생성 결과를 처리하는 컨슈머.

 이슈 생성 결과를 SYSTEM 메시지로 MongoDB에 저장한 후 Socket.IO로 브로드캐스트한다.
 Socket.IO 서버 인스턴스는 `ChatGateway`의 `afterInit`에서 {@link setSocketServer}로 주입된다.
 */
@injectable()
export class GithubIssueResultConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(GithubIssueResultConsumer.name);
	private readonly broadcaster: SystemMessageBroadcaster;
	private consumer!: Consumer;

	constructor(
		@inject(forwardRef(() => ChatService))
		private readonly chatService: ForwardReference<ChatService>,
		private readonly configService: ConfigService,
		private readonly channelMessageReadAccess: ChannelMessageReadAccessService,
		private readonly projectionReadiness: ProjectionReadinessService,
	) {
		this.broadcaster = new SystemMessageBroadcaster(this.logger, this.channelMessageReadAccess);
	}

	/**
     GitHub 이슈 생성 결과 이벤트를 처리한다.

     성공/실패 여부에 따라 포맷된 SYSTEM 메시지를 MongoDB에 저장하고,
     Socket.IO `chat:{channelId}` 룸에 `message` 이벤트를 브로드캐스트한다.

     @param event - Kafka에서 수신한 GitHub 이슈 생성 결과 이벤트
     @param source - 이벤트의 Kafka 메시지 위치. 시스템 메시지 멱등성 키를 만드는 데 사용한다
     */
	private async handleResultEvent(event: GithubIssueResultEvent, source: KafkaMessageSource): Promise<void> {
		const content = this.formatIssueResultMessage(event);

		const saved = await this.chatService.saveSystemMessage(
			event.teamId,
			event.channelId,
			content,
			systemMessageKey(source, event.channelId),
		);
		if (!saved) {
			return;
		}

		await this.broadcaster.notifyClient(event.channelId, toMessageBroadcastPayload(saved));
	}

	/**
     GitHub 이슈 생성 결과를 사용자에게 표시할 메시지 문자열로 포맷한다.

     성공 시 `✅ 이슈가 생성됐어요: {issueUrl}` 형식으로 반환한다.
     실패 시 `❌ 이슈 생성 실패: {error}` 형식으로 반환한다.

     @param event - 포맷할 GitHub 이슈 생성 결과 이벤트
     @returns 포맷된 시스템 메시지 문자열
     */
	private formatIssueResultMessage(event: GithubIssueResultEvent): string {
		return event.success ? `✅ 이슈가 생성됐어요: ${event.issueUrl}` : `❌ 이슈 생성 실패: ${event.error ?? '알 수 없는 오류'}`;
	}

	/**
     Socket.IO 서버 인스턴스를 주입한다.

     `ChatGateway`의 `afterInit` 훅에서 호출되어야 하며,
     이 메서드가 호출되기 전에 수신된 결과는 Socket.IO 브로드캐스트 없이 저장만 된다.

     @param io - Socket.IO 서버 인스턴스
     */
	setSocketServer(io: Server) {
		this.broadcaster.setSocketServer(io);
	}

	/**
     모듈 초기화 시 Kafka 컨슈머를 시작하고 `github.issue.result` 토픽 구독을 설정한다.
     */
	async onModuleInit() {
		this.consumer = await startSystemMessageConsumer<GithubIssueResultEvent>({
			logger: this.logger,
			configService: this.configService,
			projectionReadiness: this.projectionReadiness,
			clientId: 'cowork-chat-github-result',
			groupId: 'cowork-chat-github-result',
			topic: 'github.issue.result',
			handle: async (event, source) => this.handleResultEvent(event, source),
		});
		this.logger.log('Kafka consumer started: github.issue.result');
	}

	/**
     모듈 종료 시 Kafka 컨슈머 연결을 해제한다.
     */
	async onModuleDestroy() {
		await this.consumer.disconnect();
	}
}
