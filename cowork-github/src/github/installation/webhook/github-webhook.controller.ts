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
import {githubCacheKeys} from '../../github.cache';
import type {PullRequestFile} from '../../pull-request/client/pull-request-api.client';
import {PullRequestService} from '../../pull-request/pull-request.service';
import type {PullRequestStateEvent} from '../kafka/event/pull-request-state';
import {GithubReadModelEventProducers} from '../kafka/github-read-model-event-producers';
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
                    this.handleRepoEvent(eventName, payload),
                    this.handleIssueState(payload).catch((error: unknown) => {
                        this.logReadModelFailure(eventName, error);
                    }),
                ]);
                break;
            }

            case 'pull_request': {
                await Promise.all([
                    this.handleRepoEvent(eventName, payload),
                    this.handlePullRequestState(payload).catch((error: unknown) => {
                        this.logReadModelFailure(eventName, error);
                    }),
                ]);
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

    // Read-model state 발행이 실패해도 기존 알림(github.repo.event)과 webhook 응답은 유지한다 —
    // 빠진 state는 다음 webhook이나 재백필로 다시 채워진다.
    private logReadModelFailure(eventName: string, error: unknown): void {
        this.logger.error(
            `Failed to publish GitHub read-model state [event=${eventName}]`,
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
        const files = PR_FILE_REFRESH_ACTIONS.has(action)
            ? await this.pullRequestService.listPullRequestFiles(owner, repo, pr.number)
            : undefined;

        await this.readModelProducers.pullRequest.send(
            this.buildPullRequestStateEvent({owner, repo, number: pr.number}, pr, files),
        );
    }

    private buildPullRequestStateEvent(
        {owner, repo, number}: {owner: string; repo: string; number: number},
        pr: NonNullable<GithubWebhookPayload['pull_request']>,
        files: PullRequestFile[] | undefined,
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
            ...(files !== undefined && {
                files: files.map(file => ({
                    filename: file.filename,
                    status: file.status,
                    additions: file.additions,
                    deletions: file.deletions,
                    patch: file.patch ?? null,
                })),
            }),
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
        if (previousName !== undefined && previousName !== label.name) {
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
