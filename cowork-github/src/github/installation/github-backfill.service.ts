import {randomUUID} from 'node:crypto';
import {Inject as inject, Injectable as injectable, Logger} from '@nestjs/common';
import type {Redis} from 'ioredis';
import {forEachSequential} from '../common/sequential.js';
import {REDIS_CLIENT} from '../constants.js';
import {githubCacheKeys, PULL_REQUEST_FILES_TTL_SECONDS} from '../github.cache.js';
import {IssueHttpService} from '../issue-http/issue-http.service.js';
import {LabelHttpService} from '../label-http/label-http.service.js';
import {ProjectBoardSyncService} from '../project-board/project-board-sync.service.js';
import {TimelineSyncService} from '../timeline/timeline-sync.service.js';
import {PullRequestService} from '../pull-request/pull-request.service.js';
import {InstallationApiClient} from './client/installation-api.client.js';
import {IssueCommentStateEvent} from './kafka/event/issue-comment-state.js';
import {syncCompletedMarker} from './kafka/event/sync-completed.js';
import {GithubReadModelEventProducers} from './kafka/github-read-model-event-producers.js';
import {toPullRequestFileStates} from './kafka/pull-request-file-state.mapper.js';

export type GithubSyncTarget =
	| {type: 'ORG_REPOSITORIES'; installationId: number; owner: string}
	| {type: 'REPOSITORY'; owner: string; repo: string}
	| {type: 'TIMELINE'; owner: string; repo: string; number: number; updatedAt?: string}
	| {type: 'PROJECT'; owner: string; projectNumber: number};

type SyncJob = {
	key: string;
	target: GithubSyncTarget;
	done: Promise<void>;
	finish: () => void;
};

// 정기 스캔이 채울 수 있는 슬롯 수. 초과분은 다음 스캔이나 상세 조회에서 동기화한다.
const MAX_PENDING_TIMELINE_BACKFILLS = 16;

// 타임라인은 페이지와 완료 레코드를 발행하기 전에 락을 갱신한다.
const SYNC_LOCK_TTL_MS = 30 * 60 * 1000;
// 목록의 updated_at이 같아도 하루마다 누락된 변경을 다시 확인한다.
const TIMELINE_BACKFILL_TTL_SECONDS = 24 * 60 * 60;
const RENEW_LOCK_SCRIPT = 'if redis.call("get", KEYS[1]) == ARGV[1] then return redis.call("pexpire", KEYS[1], ARGV[2]) else return 0 end';
const RELEASE_LOCK_SCRIPT = 'if redis.call("get", KEYS[1]) == ARGV[1] then return redis.call("del", KEYS[1]) else return 0 end';

const syncTargetKey = (target: GithubSyncTarget): string => {
	switch (target.type) {
		case 'TIMELINE': {
			return `timeline:${target.owner.toLowerCase()}/${target.repo.toLowerCase()}#${target.number}`;
		}

		case 'REPOSITORY': {
			return `repo:${target.owner}/${target.repo}`;
		}

		case 'PROJECT': {
			return `project:${target.owner}#${target.projectNumber}`;
		}

		case 'ORG_REPOSITORIES': {
			return `org:${target.owner}`;
		}
	}
};

const mergeByNumber = <T extends {number: number}>(...lists: T[][]): T[] => {
	const seen = new Set<number>();
	return lists.flat().filter(item => {
		if (seen.has(item.number)) {
			return false;
		}

		seen.add(item.number);
		return true;
	});
};

/**
 대상(org의 repo 목록, 또는 repo 하나의 이슈·PR·라벨·댓글)의 현재 상태를 Kafka로 다시 publish하고,
 데이터를 보낸 state 토픽마다 마지막에 완료 마커를 남긴다. webhook은 이후 변경분만 전달하므로 이
 스냅샷이 cowork-project projection의 초기 상태이자 주기적 재동기화 경로다. 무엇을 언제 동기화할지는
 cowork-project가 정하고(`github.sync.command.v1`), 설치 콜백은 repo 목록만 바로 요청한다.

 저장소, PR, 이슈 보드 목록 조회는 `per_page=100` 단일 페이지만 사용한다(기존 HTTP 라우트와 동일한 제약).
 100개를 초과하는 repo/PR/이슈는 초과분이 동기화되지 않으며, 페이지네이션 지원과 함께 후속 과제로 남긴다.
 이슈/PR은 `all` 최신 100건에서 빠지는 오래된 열린 항목이 보드에서 사라지지 않게 `open` 100건을 따로 받아 합친다.
 */
@injectable()
export class GithubBackfillService {
	private readonly logger = new Logger(GithubBackfillService.name);
	private readonly foreground = new Map<string, SyncJob>();
	private readonly background = new Map<string, SyncJob>();
	private running?: SyncJob;

	@inject(InstallationApiClient)
	private readonly installationApiClient!: InstallationApiClient;

	@inject(IssueHttpService)
	private readonly issueHttpService!: IssueHttpService;

	@inject(PullRequestService)
	private readonly pullRequestService!: PullRequestService;

	@inject(TimelineSyncService)
	private readonly timelineSyncService!: TimelineSyncService;

	@inject(LabelHttpService)
	private readonly labelHttpService!: LabelHttpService;

	@inject(ProjectBoardSyncService)
	private readonly projectBoardSyncService!: ProjectBoardSyncService;

	@inject(GithubReadModelEventProducers)
	private readonly producers!: GithubReadModelEventProducers;

	@inject(REDIS_CLIENT)
	private readonly redis!: Redis;

	// 레플리카마다 대기열이 따로 있으므로, 같은 대상을 동시에 두 번 돌지 않게 대상 단위로 락을 잡는다.
	private async runWithLock(key: string, target: GithubSyncTarget): Promise<void> {
		const lockKey = githubCacheKeys.syncLock(key);
		const token = randomUUID();
		if (await this.redis.set(lockKey, token, 'PX', SYNC_LOCK_TTL_MS, 'NX') !== 'OK') {
			this.logger.log(`Skipping GitHub sync already running on another replica [target=${key}]`);
			return;
		}

		try {
			if (target.type === 'TIMELINE') {
				const versionKey = githubCacheKeys.timelineBackfillVersion(key);
				const renew = async () => {
					const renewed = await this.redis.eval(RENEW_LOCK_SCRIPT, 1, lockKey, token, SYNC_LOCK_TTL_MS);
					if (renewed !== 1) {
						throw new Error('Lost GitHub timeline sync lock');
					}
				};

				await this.timelineSyncService.sync(target.owner, target.repo, target.number, renew);
				if (target.updatedAt !== undefined) {
					await this.redis.set(versionKey, target.updatedAt, 'EX', TIMELINE_BACKFILL_TTL_SECONDS);
				}
			} else {
				await this.sync(target);
			}
		} finally {
			await this.redis.eval(RELEASE_LOCK_SCRIPT, 1, lockKey, token);
		}
	}

	private async sync(target: Exclude<GithubSyncTarget, {type: 'TIMELINE'}>): Promise<void> {
		switch (target.type) {
			case 'REPOSITORY': {
				await this.syncRepository(target.owner, target.repo);
				break;
			}

			case 'PROJECT': {
				await this.projectBoardSyncService.syncProject(target.owner, target.projectNumber);
				break;
			}

			case 'ORG_REPOSITORIES': {
				await this.syncOrgRepositories(target.installationId, target.owner);
				break;
			}
		}
	}

	private async syncOrgRepositories(installationId: number, orgLogin: string): Promise<void> {
		const occurredAt = new Date().toISOString();
		const repos = await this.installationApiClient.listRepositories(orgLogin);
		await Promise.all(repos.map(async repo => {
			const [owner, repoName] = repo.fullName.split('/', 2);
			await this.producers.repo.send({
				installationId,
				owner,
				repo: repoName,
				fullName: repo.fullName,
				private: repo.private,
				htmlUrl: `https://github.com/${repo.fullName}`,
				deleted: false,
				sourceOccurredAt: occurredAt,
			});
		}));
		await this.producers.repo.sendSyncCompleted(syncCompletedMarker(orgLogin, null));
		this.logger.log(`Finished GitHub repository list sync [org=${orgLogin}, repos=${repos.length}]`);
	}

	// 이슈/PR/댓글은 webhook과 같은 기준(엔티티 updated_at)을 버전으로 써야 이후 webhook 변경·삭제가
	// 동기화 시각보다 오래된 이벤트로 버려지지 않는다. 라벨은 updated_at이 없어 동기화 시각을 쓴다.
	private async syncRepository(owner: string, repo: string): Promise<void> {
		await this.syncLabels(owner, repo);
		await this.producers.label.sendSyncCompleted(syncCompletedMarker(owner, repo));
		await this.syncIssues(owner, repo);
		await this.producers.issue.sendSyncCompleted(syncCompletedMarker(owner, repo));
		await this.syncPullRequests(owner, repo);
		await this.producers.pullRequest.sendSyncCompleted(syncCompletedMarker(owner, repo));
		// 댓글은 이슈와 PR 동기화 양쪽에서 보내므로 둘 다 끝난 뒤에 마커를 남긴다.
		await this.producers.issueComment.sendSyncCompleted(syncCompletedMarker(owner, repo));
		this.logger.log(`Finished GitHub repository sync [repo=${owner}/${repo}]`);
	}

	private async syncLabels(owner: string, repo: string): Promise<void> {
		const occurredAt = new Date().toISOString();
		const labels = await this.labelHttpService.listLabels(owner, repo);
		await Promise.all(labels.map(async label => this.producers.label.send({
			owner,
			repo,
			name: label.name,
			color: label.color,
			deleted: false,
			sourceOccurredAt: occurredAt,
		})));
	}

	private async syncIssues(owner: string, repo: string): Promise<void> {
		const issues = mergeByNumber(
			await this.issueHttpService.listIssues(owner, repo, 'open'),
			await this.issueHttpService.listIssues(owner, repo, 'all'),
		);
		// 이슈마다 댓글 조회가 나가므로 secondary rate limit을 피하려 순차 처리한다.
		await forEachSequential(issues, async issue => {
			await this.producers.issue.send({
				owner,
				repo,
				number: issue.number,
				title: issue.title,
				author: issue.author,
				state: issue.state,
				htmlUrl: issue.htmlUrl,
				labels: issue.labels,
				createdAt: issue.createdAt,
				updatedAt: issue.updatedAt,
				deleted: false,
				sourceOccurredAt: issue.updatedAt,
			});
			await this.syncDiscussion({
				owner, repo, parentNumber: issue.number, updatedAt: issue.updatedAt, parentType: 'ISSUE',
			});
		});
	}

	private async syncPullRequests(owner: string, repo: string): Promise<void> {
		const summaries = mergeByNumber(
			await this.pullRequestService.listPullRequests(owner, repo, 'open'),
			await this.pullRequestService.listPullRequests(owner, repo, 'all'),
		);
		// PR마다 상세/파일/댓글 조회가 나가므로 secondary rate limit을 피하려 순차 처리한다.
		await forEachSequential(summaries, async summary => {
			const detail = await this.pullRequestService.getPullRequestDetail(owner, repo, summary.number);
			const files = await this.pullRequestService.listPullRequestFiles(owner, repo, summary.number);
			const fileStates = toPullRequestFileStates(files);
			// Webhook이 files 없는 이벤트를 받았을 때도(labeled 등) 실어 보낼 수 있도록, 백필이
			// 조회한 최신 파일 목록도 webhook과 같은 캐시에 남긴다 — compaction이 이 백필 메시지를
			// 치워도 파일 정보가 사라지지 않게 하기 위함이다.
			await this.redis.set(
				githubCacheKeys.pullRequestFiles(owner, repo, summary.number),
				JSON.stringify(fileStates),
				'EX',
				PULL_REQUEST_FILES_TTL_SECONDS,
			);
			await this.producers.pullRequest.send({
				owner,
				repo,
				number: summary.number,
				title: detail.title,
				body: detail.body,
				author: summary.author,
				state: summary.state,
				draft: summary.draft,
				merged: summary.merged,
				htmlUrl: summary.htmlUrl,
				labels: summary.labels,
				headRef: detail.headRef,
				baseRef: detail.baseRef,
				createdAt: summary.createdAt,
				updatedAt: summary.updatedAt,
				sourceOccurredAt: summary.updatedAt,
				files: fileStates,
			});
			await this.syncDiscussion({
				owner, repo, parentNumber: summary.number, updatedAt: summary.updatedAt, parentType: 'PULL_REQUEST',
			});
		});
	}

	private async syncDiscussion(parameters: {
		owner: string;
		repo: string;
		parentNumber: number;
		parentType: IssueCommentStateEvent['parentType'];
		updatedAt: string;
	}): Promise<void> {
		const {owner, repo, parentNumber, parentType} = parameters;
		await this.scheduleTimelineBackfill({
			type: 'TIMELINE', owner, repo, number: parentNumber, updatedAt: parameters.updatedAt,
		});
		const comments = await this.issueHttpService.listComments(owner, repo, parentNumber);
		await Promise.all(comments.map(async comment => this.producers.issueComment.send({
			owner,
			repo,
			commentId: comment.id,
			parentType,
			parentNumber,
			author: comment.author,
			body: comment.body,
			htmlUrl: comment.htmlUrl,
			createdAt: comment.createdAt,
			updatedAt: comment.updatedAt,
			deleted: false,
			sourceOccurredAt: comment.updatedAt,
		})));
	}

	private async scheduleTimelineBackfill(target: Extract<GithubSyncTarget, {type: 'TIMELINE'}>): Promise<void> {
		const key = syncTargetKey(target);
		if (this.background.size >= MAX_PENDING_TIMELINE_BACKFILLS || this.background.has(key)) {
			return;
		}

		const versionKey = githubCacheKeys.timelineBackfillVersion(key);
		try {
			if (await this.redis.get(versionKey) !== target.updatedAt) {
				void this.queueJob(key, target, this.background);
			}
		} catch (error) {
			// 정기 타임라인 조회를 예약하지 못해도 저장소의 이슈와 댓글 동기화는 계속한다.
			this.logger.warn(`Failed to schedule GitHub timeline backfill [target=${key}]`, error);
		}
	}

	private async queueJob(key: string, target: GithubSyncTarget, queue: Map<string, SyncJob>): Promise<void> {
		const pending = queue.get(key);
		if (pending !== undefined) {
			return pending.done;
		}

		const {promise, resolve} = Promise.withResolvers<void>();
		queue.set(key, {
			key, target, done: promise, finish: resolve,
		});
		this.runNext();
		return promise;
	}

	private runNext(): void {
		if (this.running !== undefined) {
			return;
		}

		const queue = this.foreground.size > 0 ? this.foreground : this.background;
		const first = queue.values().next();
		if (first.done) {
			return;
		}

		const job = first.value;
		queue.delete(job.key);
		this.running = job;
		void this.runWithLock(job.key, job.target).catch((error: unknown) => {
			this.logger.error(`GitHub sync failed [target=${job.key}]`, error instanceof Error ? error.stack : String(error));
		}).finally(() => {
			this.running = undefined;
			job.finish();
			this.runNext();
		});
	}

	async enqueue(target: GithubSyncTarget): Promise<void> {
		const key = syncTargetKey(target);
		return target.type !== 'TIMELINE' && this.running?.key === key ? this.running.done : this.queueJob(key, target, this.foreground);
	}
}
