import {Injectable, Logger} from '@nestjs/common';
import {IssueHttpService} from '../issue-http/issue-http.service';
import {LabelHttpService} from '../label-http/label-http.service';
import {PullRequestService} from '../pull-request/pull-request.service';
import {InstallationApiClient} from './client/installation-api.client';
import {BackfillCompletedProducer} from './kafka/backfill-completed.producer';
import {IssueCommentStateEvent} from './kafka/event/issue-comment-state';
import {GithubReadModelEventProducers} from './kafka/github-read-model-event-producers';

/**
 Installation이 새로 연결되거나 repo가 추가될 때, webhook이 닿기 전 기존 repo/PR/이슈/댓글/라벨
 전체를 한 번 Kafka로 publish한다. webhook은 이 시점 이후의 변경분만 전달하므로, 이 전체 스냅샷이
 cowork-project 쪽 projection의 초기 상태가 된다.

 MVP: 각 목록 조회는 `per_page=100` 단일 페이지만 사용한다(기존 HTTP 라우트와 동일한 제약).
 100개를 초과하는 repo/PR/이슈가 있는 installation은 초과분이 backfill되지 않으며, 이는 webhook
 기반 페이지네이션 지원과 함께 후속 과제로 남긴다.
 */
@Injectable()
export class GithubBackfillService {
    private readonly logger = new Logger(GithubBackfillService.name);

    constructor(
        private readonly installationApiClient: InstallationApiClient,
        private readonly issueHttpService: IssueHttpService,
        private readonly pullRequestService: PullRequestService,
        private readonly labelHttpService: LabelHttpService,
        private readonly producers: GithubReadModelEventProducers,
        private readonly backfillCompletedProducer: BackfillCompletedProducer,
    ) {}

    async runFullSync(installationId: number, orgLogin: string): Promise<void> {
        const occurredAt = new Date().toISOString();
        const repos = await this.installationApiClient.listRepositories(orgLogin);
        this.logger.log(
            `Starting GitHub backfill [installationId=${installationId}, org=${orgLogin}, repos=${repos.length}]`,
        );

        for (const repo of repos) {
            const [owner, repoName] = repo.fullName.split('/', 2);
            // eslint-disable-next-line no-await-in-loop -- 각 repo는 GitHub API rate limit을 공유하므로 순차 처리한다.
            await this.syncRepo({
                installationId, owner, repo: repoName, isPrivate: repo.private, occurredAt,
            });
        }

        await this.backfillCompletedProducer.send({
            installationId,
            orgLogin,
            completedAt: new Date().toISOString(),
        });
        this.logger.log(
            `Finished GitHub backfill [installationId=${installationId}, org=${orgLogin}]`,
        );
    }

    private async syncRepo(parameters: {
        installationId: number;
        owner: string;
        repo: string;
        isPrivate: boolean;
        occurredAt: string;
    }): Promise<void> {
        const {installationId, owner, repo, isPrivate, occurredAt} = parameters;
        await this.producers.repo.send({
            installationId,
            owner,
            repo,
            fullName: `${owner}/${repo}`,
            private: isPrivate,
            htmlUrl: `https://github.com/${owner}/${repo}`,
            deleted: false,
            sourceOccurredAt: occurredAt,
        });

        await this.syncLabels(owner, repo, occurredAt);
        await this.syncIssues(owner, repo, occurredAt);
        await this.syncPullRequests(owner, repo, occurredAt);
    }

    private async syncLabels(owner: string, repo: string, occurredAt: string): Promise<void> {
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

    private async syncIssues(owner: string, repo: string, occurredAt: string): Promise<void> {
        const issues = await this.issueHttpService.listIssues(owner, repo, 'all');
        await Promise.all(issues.map(async issue => {
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
                sourceOccurredAt: occurredAt,
            });
            await this.syncComments({
                owner, repo, parentNumber: issue.number, parentType: 'ISSUE', occurredAt,
            });
        }));
    }

    private async syncPullRequests(owner: string, repo: string, occurredAt: string): Promise<void> {
        const summaries = await this.pullRequestService.listPullRequests(owner, repo, 'all');
        await Promise.all(summaries.map(async summary => {
            const detail = await this.pullRequestService.getPullRequestDetail(owner, repo, summary.number);
            const files = await this.pullRequestService.listPullRequestFiles(owner, repo, summary.number);
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
                sourceOccurredAt: occurredAt,
                files: files.map(file => ({
                    filename: file.filename,
                    status: file.status,
                    additions: file.additions,
                    deletions: file.deletions,
                    patch: file.patch ?? null,
                })),
            });
            await this.syncComments({
                owner, repo, parentNumber: summary.number, parentType: 'PULL_REQUEST', occurredAt,
            });
        }));
    }

    private async syncComments(parameters: {
        owner: string;
        repo: string;
        parentNumber: number;
        parentType: IssueCommentStateEvent['parentType'];
        occurredAt: string;
    }): Promise<void> {
        const {owner, repo, parentNumber, parentType, occurredAt} = parameters;
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
            sourceOccurredAt: occurredAt,
        })));
    }
}
