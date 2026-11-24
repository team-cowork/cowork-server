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
import {ChatService} from '../chat.service.js';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {waitForProjectionReadiness} from '../../common/kafka/projection-readiness-wait.js';
import {CHANNEL_PERMISSION_STREAMS} from '../../common/kafka/projection-streams.js';
import {type ForwardReference} from '../../common/type/forward-reference.js';
import {GithubIssueResultEvent} from './event/github-issue.event.js';
import {ChatGithubIssueCreateResult} from './event/chat-github-issue.event.js';
import {type KafkaMessageSource, systemMessageKey} from './system-message-key.js';
import {startSystemMessageConsumer} from './system-message-result-consumer.js';

/**
 GitHub 이슈 생성 결과를 SYSTEM 메시지로 저장하고 브로드캐스트한다.

 - `github.issue.result`: 실제 이슈 생성 성공/실패.
 - `project.chat-github-issue.result`: project의 커맨드 권한 검증 결과. `ACCEPTED`는 권한 검증 통과만을
 의미하므로 `REJECTED`일 때만 메시지를 남긴다.
 */
async function noHeartbeat(): Promise<void> {
	// 직접 호출할 때는 Kafka heartbeat가 필요 없다.
}

@injectable()
export class GithubIssueResultConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(GithubIssueResultConsumer.name);
	private consumers: Consumer[] = [];

	constructor(
		@inject(forwardRef(() => ChatService))
		private readonly chatService: ForwardReference<ChatService>,
		private readonly configService: ConfigService,
		private readonly projectionReadiness: ProjectionReadinessService,
	) {}

	private async handleIssueResult(event: GithubIssueResultEvent, source: KafkaMessageSource, heartbeat: () => Promise<void> = noHeartbeat): Promise<void> {
		const content = event.success ? `✅ 이슈가 생성됐어요: ${event.issueUrl}` : `❌ 이슈 생성 실패: ${event.error ?? '알 수 없는 오류'}`;
		await this.saveAndBroadcast(event, content, source, heartbeat);
	}

	private async handleCommandResult(event: ChatGithubIssueCreateResult, source: KafkaMessageSource, heartbeat: () => Promise<void> = noHeartbeat): Promise<void> {
		if (event.status !== 'REJECTED') {
			this.logger.debug(`Chat GitHub issue command accepted [operationId=${event.operationId}]`);
			return;
		}

		await this.saveAndBroadcast(event, `❌ 이슈 생성 실패: ${event.error?.message ?? '알 수 없는 오류'}`, source, heartbeat);
	}

	/**
     채널 권한 projection이 준비될 때까지 기다린 뒤 저장하고 보낸다.
     준비되지 않은 동안에는 전송이 실패해 재전달이 반복되므로, 다른 시스템 메시지 소비자처럼 먼저 기다린다.
     */
	private async saveAndBroadcast(
		event: {teamId: number; channelId: number}, content: string, source: KafkaMessageSource, heartbeat: () => Promise<void>,
	): Promise<void> {
		await waitForProjectionReadiness(this.projectionReadiness, heartbeat, CHANNEL_PERMISSION_STREAMS);
		const saved = await this.chatService.saveSystemMessage(
			event.teamId,
			event.channelId,
			content,
			systemMessageKey(source, event.channelId),
		);
		if (!saved) {
			return;
		}

		await this.chatService.broadcastSystemMessage(saved);
	}

	async onModuleInit() {
		this.consumers = await Promise.all([
			startSystemMessageConsumer<GithubIssueResultEvent>({
				logger: this.logger,
				configService: this.configService,
				projectionReadiness: this.projectionReadiness,
				clientId: 'cowork-chat-github-result',
				groupId: 'cowork-chat-github-result',
				topic: 'github.issue.result',
				handle: async (event, source, heartbeat) => this.handleIssueResult(event, source, heartbeat),
			}),
			startSystemMessageConsumer<ChatGithubIssueCreateResult>({
				logger: this.logger,
				configService: this.configService,
				projectionReadiness: this.projectionReadiness,
				clientId: 'cowork-chat-chat-github-issue-result',
				groupId: 'cowork-chat-chat-github-issue-result',
				topic: 'project.chat-github-issue.result',
				handle: async (event, source, heartbeat) => this.handleCommandResult(event, source, heartbeat),
			}),
		]);
		this.logger.log('Kafka consumer started: github.issue.result, project.chat-github-issue.result');
	}

	async onModuleDestroy() {
		await Promise.all(this.consumers.map(async consumer => consumer.disconnect()));
	}
}
