import {randomUUID} from 'node:crypto';
import {Inject, Injectable, Logger} from '@nestjs/common';
import type Redis from 'ioredis';
import {REDIS_CLIENT} from '../constants';
import {githubCacheKeys} from '../github.cache';
import {IssueHttpService} from '../issue-http/issue-http.service';
import {LabelHttpService} from '../label-http/label-http.service';
import {PullRequestService} from '../pull-request/pull-request.service';
import {InstallationApiClient} from './client/installation-api.client';
import {IssueCommentStateEvent} from './kafka/event/issue-comment-state';
import {syncCompletedMarker} from './kafka/event/sync-completed';
import {GithubReadModelEventProducers} from './kafka/github-read-model-event-producers';
import {toPullRequestFileStates} from './kafka/pull-request-file-state.mapper';

export type GithubSyncTarget =
    | {type: 'ORG_REPOSITORIES'; installationId: number; owner: string}
    | {type: 'REPOSITORY'; owner: string; repo: string};

// MVP: 락을 갱신하지 않는다. 동기화가 TTL보다 오래 걸리면 다른 레플리카가 같은 대상을 다시 돌 수 있지만,
// state 레코드는 버전으로 멱등 반영되므로 결과는 같다. 대상이 커져 TTL을 넘기기 시작하면 갱신을 추가한다.
const SYNC_LOCK_TTL_MS = 30 * 60 * 1000;
const RELEASE_LOCK_SCRIPT = 'if redis.call("get", KEYS[1]) == ARGV[1] then return redis.call("del", KEYS[1]) else return 0 end';

const syncTargetKey = (target: GithubSyncTarget): string =>
    target.type === 'REPOSITORY' ? `repo:${target.owner}/${target.repo}` : `org:${target.owner}`;

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

 MVP: 각 목록 조회는 `per_page=100` 단일 페이지만 사용한다(기존 HTTP 라우트와 동일한 제약).
 100개를 초과하는 repo/PR/이슈는 초과분이 동기화되지 않으며, 페이지네이션 지원과 함께 후속 과제로 남긴다.
 이슈/PR은 `all` 최신 100건에서 빠지는 오래된 열린 항목이 보드에서 사라지지 않게 `open` 100건을 따로 받아 합친다.
 */
@Injectable()
export class GithubBackfillService {
    private readonly logger = new Logger(GithubBackfillService.name);
    private readonly queued = new Set<string>();
    private queue: Promise<void> = Promise.resolve();

    // eslint-disable-next-line max-params -- NestJS 생성자 주입이라 의존성 수만큼 파라미터가 필요하다.
    constructor(
        private readonly installationApiClient: InstallationApiClient,
        private readonly issueHttpService: IssueHttpService,
        private readonly pullRequestService: PullRequestService,
        private readonly labelHttpService: LabelHttpService,
        private readonly producers: GithubReadModelEventProducers,
        @Inject(REDIS_CLIENT) private readonly redis: Redis,
    ) {}

    /**
     대상을 프로세스 안의 직렬 대기열에 넣고, 그 대상의 동기화가 끝나면 resolve되는 Promise를 돌려준다
     (실패해도 로그만 남기고 reject하지 않는다). 한 번에 하나씩 돌려 GitHub secondary rate limit을 피하고,
     이미 대기 중인 대상은 다시 넣지 않는다. 처리 전에 프로세스가 내려가면 cowork-project가 다시 요청한다.
     */
    async enqueue(target: GithubSyncTarget): Promise<void> {
        const key = syncTargetKey(target);
        if (this.queued.has(key)) {
            return this.queue;
        }

        this.queued.add(key);
        this.queue = this.queue.then(async () => {
            try {
                await this.runWithLock(key, target);
            } catch (error) {
                this.logger.error(
                    `GitHub sync failed [target=${key}]`,
                    error instanceof Error ? error.stack : String(error),
                );
            } finally {
                this.queued.delete(key);
            }
        });
        return this.queue;
    }

    // 레플리카마다 대기열이 따로 있으므로, 같은 대상을 동시에 두 번 돌지 않게 대상 단위로 락을 잡는다.
    private async runWithLock(key: string, target: GithubSyncTarget): Promise<void> {
        const lockKey = githubCacheKeys.syncLock(key);
        const token = randomUUID();
        if (await this.redis.set(lockKey, token, 'PX', SYNC_LOCK_TTL_MS, 'NX') !== 'OK') {
            this.logger.log(`Skipping GitHub sync already running on another replica [target=${key}]`);
            return;
        }

        try {
            await (target.type === 'REPOSITORY'
                ? this.syncRepository(target.owner, target.repo)
                : this.syncOrgRepositories(target.installationId, target.owner));
        } finally {
            await this.redis.eval(RELEASE_LOCK_SCRIPT, 1, lockKey, token);
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
        /* eslint-disable no-await-in-loop -- 이슈마다 댓글 조회가 나가므로 secondary rate limit을 피하려 순차 처리한다. */
        for (const issue of issues) {
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
            await this.syncComments({
                owner, repo, parentNumber: issue.number, parentType: 'ISSUE',
            });
        }
        /* eslint-enable no-await-in-loop */
    }

    private async syncPullRequests(owner: string, repo: string): Promise<void> {
        const summaries = mergeByNumber(
            await this.pullRequestService.listPullRequests(owner, repo, 'open'),
            await this.pullRequestService.listPullRequests(owner, repo, 'all'),
        );
        /* eslint-disable no-await-in-loop -- PR마다 상세/파일/댓글 조회가 나가므로 secondary rate limit을 피하려 순차 처리한다. */
        for (const summary of summaries) {
            const detail = await this.pullRequestService.getPullRequestDetail(owner, repo, summary.number);
            const files = await this.pullRequestService.listPullRequestFiles(owner, repo, summary.number);
            const fileStates = toPullRequestFileStates(files);
            // Webhook이 files 없는 이벤트를 받았을 때도(labeled 등) 실어 보낼 수 있도록, 백필이
            // 조회한 최신 파일 목록도 webhook과 같은 캐시에 남긴다 — compaction이 이 백필 메시지를
            // 치워도 파일 정보가 사라지지 않게 하기 위함이다.
            await this.redis.set(
                githubCacheKeys.pullRequestFiles(owner, repo, summary.number),
                JSON.stringify(fileStates),
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
            await this.syncComments({
                owner, repo, parentNumber: summary.number, parentType: 'PULL_REQUEST',
            });
        }
        /* eslint-enable no-await-in-loop */
    }

    private async syncComments(parameters: {
        owner: string;
        repo: string;
        parentNumber: number;
        parentType: IssueCommentStateEvent['parentType'];
    }): Promise<void> {
        const {owner, repo, parentNumber, parentType} = parameters;
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
}
