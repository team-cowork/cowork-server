import {
    Body,
    Controller,
    Headers,
    HttpCode,
    Inject,
    Logger,
    Post,
    UseGuards,
} from '@nestjs/common';
import type Redis from 'ioredis';
import {
    extractCommentAuthor,
} from '../../common/comment-author-marker';
import {REDIS_CLIENT} from '../../constants';
import {githubCacheKeys, PULL_REQUEST_FILES_TTL_SECONDS} from '../../github.cache';
import {PullRequestService} from '../../pull-request/pull-request.service';
import type {PullRequestFileState, PullRequestStateEvent} from '../kafka/event/pull-request-state';
import {toRepoIdentity} from '../kafka/event/repo-identity';
import {GithubReadModelEventProducers} from '../kafka/github-read-model-event-producers';
import {toPullRequestFileStates} from '../kafka/pull-request-file-state.mapper';
import {RepoEventProducer} from '../kafka/repo-event.producer';
import {TeamGithubProducer} from '../kafka/team-github.producer';
import type {GithubWebhookPayload} from './github-webhook-payload';
import {GithubWebhookSignatureGuard} from './github-webhook-signature.guard';

const PR_FILE_REFRESH_ACTIONS = new Set(['opened', 'synchronize', 'reopened']);

@Controller('github/webhooks')
@UseGuards(GithubWebhookSignatureGuard)
export class GithubWebhookController {
    private static readonly DISCONNECT_ACTIONS = new Set(['deleted']);

    private readonly logger = new Logger(GithubWebhookController.name);

    // eslint-disable-next-line max-params -- NestJS 생성자 주입이라 의존성 수만큼 파라미터가 필요하다.
    constructor(
        private readonly producer: TeamGithubProducer,
        private readonly repoEventProducer: RepoEventProducer,
        private readonly readModelProducers: GithubReadModelEventProducers,
        private readonly pullRequestService: PullRequestService,
        @Inject(REDIS_CLIENT) private readonly redis: Redis,
    ) {}

    @Post()
    @HttpCode(200)
    async handle(
        @Headers('x-github-event') eventName: string,
        @Body() payload: GithubWebhookPayload,
    ): Promise<{ok: true}> {
        switch (eventName) {
            case 'installation': {
                await this.handleInstallation(payload);
                break;
            }

            case 'push': {
                await this.handleRepoEvent(eventName, payload);
                break;
            }

            case 'issues': {
                await Promise.all([
                    this.handleIssueState(payload),
                    this.handleRepoEvent(eventName, payload).catch((error: unknown) => {
                        this.logNotificationFailure(eventName, error);
                    }),
                ]);
                break;
            }

            case 'pull_request': {
                await Promise.all([
                    this.handlePullRequestState(payload),
                    this.handleRepoEvent(eventName, payload).catch((error: unknown) => {
                        this.logNotificationFailure(eventName, error);
                    }),
                ]);
                break;
            }

            case 'installation_repositories': {
                await this.handleInstallationRepositoriesState(payload);
                break;
            }

            case 'repository': {
                await this.handleRepositoryState(payload);
                break;
            }

            case 'label': {
                await this.handleLabelState(payload);
                break;
            }

            case 'issue_comment': {
                await this.handleIssueCommentState(payload);
                break;
            }

            default: {
                this.logger.log(`Ignoring unhandled GitHub event: ${eventName}`);
            }
        }

        return {ok: true};
    }

    // 알림(github.repo.event)은 사용자에게 보여주는 부가 정보라 실패해도 webhook 응답은 유지한다.
    // read-model state 발행은 반대로 실패 시 그대로 throw해 500을 반환한다 — GitHub가 전송 실패를
    // 재시도/재전송 대상으로 인지해야, 조용히 삼켜서 projection이 영구히 stale해지는 것을 막을 수 있다.
    // state를 먼저 실행하므로 state 실패로 인한 재시도가 이 알림을 중복 발행하지 않는다.
    private logNotificationFailure(eventName: string, error: unknown): void {
        this.logger.error(
            `Failed to publish GitHub notification [event=${eventName}]`,
            error instanceof Error ? error.stack : String(error),
        );
    }

    private async handleInstallation(
        payload: GithubWebhookPayload,
    ): Promise<void> {
        // 'created'는 state가 없어 상관관계를 지을 수 없음 — setup 콜백에서만 처리
        // 'suspend'는 일시 중단일 뿐이라 연동을 끊지 않음 — 'unsuspend' 시 별도 이벤트 없이 API 호출이 다시 성공하며 자연히 복구됨
        const action = payload?.action;
        if (action === undefined
            || !GithubWebhookController.DISCONNECT_ACTIONS.has(action) || !payload.installation) {
            return;
        }

        const installationId = payload.installation.id;
        const revision = await this.redis.incr(
            githubCacheKeys.installationRevision(installationId),
        );
        await this.producer.send('team.github.disconnected', {
            installationId,
            revision,
        });
    }

    private async handleRepoEvent(
        eventName: string,
        payload: GithubWebhookPayload,
    ): Promise<void> {
        const fullName = payload?.repository?.full_name;
        if (fullName === undefined || fullName === '') {
            return;
        }

        const [owner, repo] = fullName.split('/', 2);
        const action: string = payload.action ?? 'pushed';
        const summary = this.buildSummary(eventName, action, payload);

        await this.repoEventProducer.send({
            owner,
            repo,
            eventType: eventName,
            action,
            summary,
        });
    }

    private buildSummary(
        eventName: string,
        action: string,
        payload: GithubWebhookPayload,
    ): string {
        if (eventName === 'push') {
            const commitCount = payload.commits?.length ?? 0;
            const ref = (payload.ref ?? '').replace(/^refs\/(?:heads|tags)\//v, '');
            const pusher = payload.pusher?.name ?? 'unknown';
            return `📦 ${pusher}님이 ${ref}에 커밋 ${commitCount}개를 푸시했습니다.`;
        }

        if (eventName === 'issues') {
            return `📝 이슈 #${payload.issue?.number} ${action}: ${payload.issue?.title} (${payload.issue?.html_url})`;
        }

        return eventName === 'pull_request' ? `🔀 PR #${payload.pull_request?.number} ${action}: ${payload.pull_request?.title} (${payload.pull_request?.html_url})` : `${eventName} 이벤트가 발생했습니다.`;
    }

    private async handleInstallationRepositoriesState(payload: GithubWebhookPayload): Promise<void> {
        const installationId = payload.installation?.id;
        if (installationId === undefined) {
            return;
        }

        const sourceOccurredAt = new Date().toISOString();
        const changes = [
            ...(payload.repositories_added ?? []).map(repository => ({repository, deleted: false})),
            ...(payload.repositories_removed ?? []).map(repository => ({repository, deleted: true})),
        ];
        await Promise.all(changes.map(async ({repository, deleted}) => this.sendRepoState({
            installationId,
            fullName: repository.full_name,
            isPrivate: repository.private ?? false,
            deleted,
            sourceOccurredAt,
        })));
    }

    private async handleRepositoryState(payload: GithubWebhookPayload): Promise<void> {
        const installationId = payload.installation?.id;
        const fullName = payload.repository?.full_name;
        if (installationId === undefined || fullName === undefined || fullName === '') {
            return;
        }

        const [owner, repo] = fullName.split('/', 2);
        const isPrivate = payload.repository?.private ?? false;
        // 백필과 같은 기준(수신 시각)을 버전으로 쓴다 — repository.updated_at은 삭제 시 갱신되지 않아
        // 백필 시각보다 오래된 이벤트로 버려질 수 있다.
        const sourceOccurredAt = new Date().toISOString();
        // 이름 변경(renamed)·이전(transferred)은 projection 키(owner/repo)가 바뀌므로 이전 키를 삭제 처리한다.
        const previousFullName = this.previousRepoFullName(payload.changes, owner, repo);
        // 대소문자만 바뀐 경우는 projection 키가 같으므로 이전 키를 삭제하지 않는다.
        if (toRepoIdentity(previousFullName) !== toRepoIdentity(fullName)) {
            await this.sendRepoState({
                installationId, fullName: previousFullName, isPrivate, deleted: true, sourceOccurredAt,
            });
        }

        await this.sendRepoState({
            installationId, fullName, isPrivate, deleted: payload.action === 'deleted', sourceOccurredAt,
        });
    }

    private previousRepoFullName(
        changes: GithubWebhookPayload['changes'],
        owner: string,
        repo: string,
    ): string {
        const previousOwner = changes?.owner?.from?.organization?.login ?? changes?.owner?.from?.user?.login ?? owner;
        return `${previousOwner}/${changes?.repository?.name?.from ?? repo}`;
    }

    private async sendRepoState(parameters: {
        installationId: number;
        fullName: string;
        isPrivate: boolean;
        deleted: boolean;
        sourceOccurredAt: string;
    }): Promise<void> {
        const {installationId, fullName, isPrivate, deleted, sourceOccurredAt} = parameters;
        const [owner, repo] = fullName.split('/', 2);
        await this.readModelProducers.repo.send({
            installationId,
            owner,
            repo,
            fullName,
            private: isPrivate,
            htmlUrl: `https://github.com/${fullName}`,
            deleted,
            sourceOccurredAt,
        });
    }

    private async handleIssueState(payload: GithubWebhookPayload): Promise<void> {
        const fullName = payload.repository?.full_name;
        const {issue} = payload;
        if (
            fullName === undefined || fullName === '' || issue?.number === undefined
            // GitHub의 issues 이벤트는 PR에 대해서도 발생하지 않지만, 방어적으로 pull_request
            // 필드가 있으면 건너뛴다(이 경우는 'pull_request' 이벤트가 담당한다).
            || issue.pull_request !== undefined
        ) {
            return;
        }

        const [owner, repo] = fullName.split('/', 2);
        await this.readModelProducers.issue.send({
            owner,
            repo,
            number: issue.number,
            title: issue.title ?? '',
            author: issue.user?.login ?? 'ghost',
            state: issue.state ?? 'open',
            htmlUrl: issue.html_url ?? '',
            labels: issue.labels?.map(label => label.name) ?? [],
            createdAt: issue.created_at ?? new Date().toISOString(),
            updatedAt: issue.updated_at ?? new Date().toISOString(),
            // 다른 repo로 옮겨진 이슈는 원래 repo 기준으로 삭제 처리한다.
            deleted: payload.action === 'deleted' || payload.action === 'transferred',
            sourceOccurredAt: issue.updated_at ?? new Date().toISOString(),
        });
    }

    private async handlePullRequestState(payload: GithubWebhookPayload): Promise<void> {
        const fullName = payload.repository?.full_name;
        const pr = payload.pull_request;
        if (fullName === undefined || fullName === '' || pr?.number === undefined) {
            return;
        }

        const [owner, repo] = fullName.split('/', 2);
        const action = payload.action ?? '';
        const isBaseChanged = action === 'edited' && payload.changes?.base !== undefined;
        const files = await this.resolvePullRequestFiles({
            owner, repo, number: pr.number, action, isBaseChanged,
        });

        await this.readModelProducers.pullRequest.send(
            this.buildPullRequestStateEvent({owner, repo, number: pr.number}, pr, files),
        );
    }

    /**
     파일 목록은 webhook payload에 없어 직접 조회해야 한다. 매번 조회하면 rate limit을 낭비하니
     opened/synchronize/reopened나 base 브랜치 변경, 캐시가 없을 때만 다시 조회하고, 그 결과를 Redis에
     캐시해 다른 액션에서도 함께 실어 보낸다 — 이 토픽은 key로 compaction되므로, 파일 없는 이벤트만 남으면
     과거에 조회한 파일 목록이 영구히 사라질 수 있기 때문이다.
     */
    private async resolvePullRequestFiles(parameters: {
        owner: string;
        repo: string;
        number: number;
        action: string;
        isBaseChanged: boolean;
    }): Promise<PullRequestFileState[]> {
        const {owner, repo, number, action, isBaseChanged} = parameters;
        const cacheKey = githubCacheKeys.pullRequestFiles(owner, repo, number);
        if (!isBaseChanged && !PR_FILE_REFRESH_ACTIONS.has(action)) {
            const cached = await this.redis.get(cacheKey);
            if (cached !== null) {
                return JSON.parse(cached) as PullRequestFileState[];
            }
        }

        const fileStates = toPullRequestFileStates(await this.pullRequestService.listPullRequestFiles(owner, repo, number));
        await this.redis.set(cacheKey, JSON.stringify(fileStates), 'EX', PULL_REQUEST_FILES_TTL_SECONDS);
        return fileStates;
    }

    private buildPullRequestStateEvent(
        {owner, repo, number}: {owner: string; repo: string; number: number},
        pr: NonNullable<GithubWebhookPayload['pull_request']>,
        files: PullRequestFileState[],
    ): PullRequestStateEvent {
        const now = new Date().toISOString();
        return {
            owner,
            repo,
            number,
            title: pr.title ?? '',
            body: pr.body ?? null,
            author: pr.user?.login ?? 'ghost',
            state: pr.state ?? 'open',
            draft: pr.draft ?? false,
            merged: pr.merged ?? false,
            htmlUrl: pr.html_url ?? '',
            labels: pr.labels?.map(label => label.name) ?? [],
            headRef: pr.head?.ref ?? '',
            baseRef: pr.base?.ref ?? '',
            createdAt: pr.created_at ?? now,
            updatedAt: pr.updated_at ?? now,
            sourceOccurredAt: pr.updated_at ?? now,
            files,
        };
    }

    private async handleLabelState(payload: GithubWebhookPayload): Promise<void> {
        const fullName = payload.repository?.full_name;
        const {label} = payload;
        if (fullName === undefined || fullName === '' || label?.name === undefined) {
            return;
        }

        const [owner, repo] = fullName.split('/', 2);
        const sourceOccurredAt = new Date().toISOString();
        // 라벨 projection은 이름이 키라, 이름이 바뀌면 이전 이름 행을 삭제 처리한다.
        const previousName = payload.action === 'edited' ? payload.changes?.name?.from : undefined;
        // GitHub 라벨 이름도 대소문자를 구분하지 않아, 대소문자만 바뀐 경우는 같은 projection 행이다.
        if (previousName !== undefined && previousName.toLowerCase() !== label.name.toLowerCase()) {
            await this.readModelProducers.label.send({
                owner,
                repo,
                name: previousName,
                color: label.color ?? '',
                deleted: true,
                sourceOccurredAt,
            });
        }

        await this.readModelProducers.label.send({
            owner,
            repo,
            name: label.name,
            color: label.color ?? '',
            deleted: payload.action === 'deleted',
            sourceOccurredAt,
        });
    }

    private async handleIssueCommentState(payload: GithubWebhookPayload): Promise<void> {
        const fullName = payload.repository?.full_name;
        const {comment} = payload;
        const parentNumber = payload.issue?.number;
        if (parentNumber === undefined || fullName === undefined || fullName === '' || comment?.id === undefined) {
            return;
        }

        const [owner, repo] = fullName.split('/', 2);
        const {author, body} = extractCommentAuthor(comment.body ?? '', comment.user?.login ?? 'ghost');
        await this.readModelProducers.issueComment.send({
            owner,
            repo,
            commentId: comment.id,
            parentType: payload.issue?.pull_request === undefined ? 'ISSUE' : 'PULL_REQUEST',
            parentNumber,
            author,
            body,
            htmlUrl: comment.html_url ?? '',
            createdAt: comment.created_at ?? new Date().toISOString(),
            updatedAt: comment.updated_at ?? new Date().toISOString(),
            deleted: payload.action === 'deleted',
            sourceOccurredAt: comment.updated_at ?? new Date().toISOString(),
        });
    }
}
