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
import {ChatGithubIssueCreateResult} from './event/chat-github-issue.event.js';
import {type KafkaMessageSource, systemMessageKey} from './system-message-key.js';
import {startSystemMessageConsumer, SystemMessageBroadcaster} from './system-message-result-consumer.js';

/**
 Kafka `project.chat-github-issue.result` 토픽을 구독하여
 project의 채팅발 GitHub 이슈 생성 커맨드 권한 검증 결과를 처리하는 컨슈머.

 `REJECTED`인 경우에만 거부 사유를 SYSTEM 메시지로 저장하고 Socket.IO로 브로드캐스트한다.
 `ACCEPTED`는 권한 검증 통과만을 의미하므로 아무 동작도 하지 않는다(실제 이슈 생성 성공/실패는
 기존 `github.issue.result` 토픽/`GithubIssueResultConsumer`가 그대로 처리한다).
 Socket.IO 서버 인스턴스는 `ChatGateway`의 `afterInit`에서 {@link setSocketServer}로 주입된다.
 */
@injectable()
export class ChatGithubIssueResultConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(ChatGithubIssueResultConsumer.name);
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
     채팅발 GitHub 이슈 생성 커맨드의 권한 검증 결과 이벤트를 처리한다.

     `REJECTED`일 때만 거부 SYSTEM 메시지를 MongoDB에 저장하고,
     Socket.IO `chat:{channelId}` 룸에 `message` 이벤트를 브로드캐스트한다.
     `ACCEPTED`는 디버그 로그만 남기고 아무 동작도 하지 않는다.

     @param event - Kafka에서 수신한 권한 검증 결과 이벤트
     @param source - 이벤트의 Kafka 메시지 위치. 시스템 메시지 멱등성 키를 만드는 데 사용한다
     */
	private async handleResultEvent(event: ChatGithubIssueCreateResult, source: KafkaMessageSource): Promise<void> {
		if (event.status !== 'REJECTED') {
			this.logger.debug(`Chat GitHub issue command accepted [operationId=${event.operationId}]`);
			return;
		}

		const content = this.formatRejectionMessage(event);

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
     거부 결과를 사용자에게 표시할 메시지 문자열로 포맷한다.

     `❌ 이슈 생성 실패: {error.message}` 형식으로 반환한다.

     @param event - 포맷할 결과 이벤트
     @returns 포맷된 시스템 메시지 문자열
     */
	private formatRejectionMessage(event: ChatGithubIssueCreateResult): string {
		return `❌ 이슈 생성 실패: ${event.error?.message ?? '알 수 없는 오류'}`;
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
     모듈 초기화 시 Kafka 컨슈머를 시작하고 `project.chat-github-issue.result` 토픽 구독을 설정한다.
     */
	async onModuleInit() {
		this.consumer = await startSystemMessageConsumer<ChatGithubIssueCreateResult>({
			logger: this.logger,
			configService: this.configService,
			projectionReadiness: this.projectionReadiness,
			clientId: 'cowork-chat-chat-github-issue-result',
			groupId: 'cowork-chat-chat-github-issue-result',
			topic: 'project.chat-github-issue.result',
			handle: async (event, source) => this.handleResultEvent(event, source),
		});
		this.logger.log('Kafka consumer started: project.chat-github-issue.result');
	}

	/**
     모듈 종료 시 Kafka 컨슈머 연결을 해제한다.
     */
	async onModuleDestroy() {
		await this.consumer.disconnect();
	}
}
