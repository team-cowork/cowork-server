import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {Kafka, Producer} from 'kafkajs';
import {AppConfigService} from '../../../config/app-config.service.js';
import {IssueCommentStateEvent} from './event/issue-comment-state.js';
import {IssueStateEvent} from './event/issue-state.js';
import {LabelStateEvent} from './event/label-state.js';
import {PullRequestStateEvent} from './event/pull-request-state.js';
import {toRepoIdentity} from './event/repo-identity.js';
import {RepoStateEvent} from './event/repo-state.js';
import {type SyncCompletedMarker, syncCompletedKey} from './event/sync-completed.js';

export const REPO_STATE_TOPIC = 'github.repo.state.v1';
export const ISSUE_STATE_TOPIC = 'github.issue.state.v1';
export const PULL_REQUEST_STATE_TOPIC = 'github.pull-request.state.v1';
export const LABEL_STATE_TOPIC = 'github.label.state.v1';
export const ISSUE_COMMENT_STATE_TOPIC = 'github.issue-comment.state.v1';

type RepoScopedEvent = {owner: string; repo: string};

/**
 State 토픽 하나에 owner/repo를 정규화한 이벤트와 동기화 완료 마커를 보낸다.
 */
export class ReadModelStateProducer<E extends RepoScopedEvent> {
	constructor(
		private readonly producer: () => Producer,
		private readonly topic: string,
		private readonly keyOf: (normalized: E) => string,
	) {}

	async send(event: E): Promise<void> {
		const normalized = {...event, owner: toRepoIdentity(event.owner), repo: toRepoIdentity(event.repo)};
		await this.producer().send({
			topic: this.topic,
			messages: [
				{
					key: this.keyOf(normalized),
					value: JSON.stringify(normalized),
				},
			],
		});
	}

	async sendSyncCompleted(marker: SyncCompletedMarker): Promise<void> {
		await this.producer().send({
			topic: this.topic,
			messages: [
				{
					key: syncCompletedKey(marker),
					value: JSON.stringify(marker),
				},
			],
		});
	}
}

/**
 Repo/issue/PR/라벨/댓글 read-model state producer 5개를 한데 묶는다 — webhook 컨트롤러와
 backfill 서비스 둘 다 이 5개를 전부 쓰므로, 묶어서 주입받으면 생성자 파라미터가 줄어든다.
 5개 토픽은 Kafka client 하나를 공유한다.
 */
@injectable()
export class GithubReadModelEventProducers implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(GithubReadModelEventProducers.name);
	private producer!: Producer;

	readonly repo = this.stateProducer<RepoStateEvent>(REPO_STATE_TOPIC, event => `${event.owner}/${event.repo}`);

	readonly issue = this.stateProducer<IssueStateEvent>(
		ISSUE_STATE_TOPIC,
		event => `${event.owner}/${event.repo}#${event.number}`,
	);

	readonly pullRequest = this.stateProducer<PullRequestStateEvent>(
		PULL_REQUEST_STATE_TOPIC,
		event => `${event.owner}/${event.repo}#${event.number}`,
	);

	readonly label = this.stateProducer<LabelStateEvent>(
		LABEL_STATE_TOPIC,
		event => `${event.owner}/${event.repo}:${event.name}`,
	);

	readonly issueComment = this.stateProducer<IssueCommentStateEvent>(
		ISSUE_COMMENT_STATE_TOPIC,
		event => `${event.owner}/${event.repo}#${event.commentId}`,
	);

	constructor(private readonly config: AppConfigService) {}

	private stateProducer<E extends RepoScopedEvent>(topic: string, keyOf: (normalized: E) => string) {
		return new ReadModelStateProducer<E>(() => this.producer, topic, keyOf);
	}

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-github',
			...this.config.kafkaClientConfig,
		});
		this.producer = kafka.producer();
		await this.producer.connect();
		this.logger.log('Kafka read-model state producer connected');
	}

	async onModuleDestroy() {
		await this.producer.disconnect();
	}
}
