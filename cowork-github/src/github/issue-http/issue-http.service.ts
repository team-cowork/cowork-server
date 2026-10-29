import {Injectable} from '@nestjs/common';
import {
    appendAuthorMarker,
    extractCommentAuthor,
} from '../common/comment-author-marker';
import {GithubClientError} from '../github.errors';
import {
    GithubComment,
    IssueDetail,
    IssueHttpApiClient,
} from './client/issue-http-api.client';

export const ALLOWED_ISSUE_STATES = ['open', 'closed', 'all'] as const;

export type IssueDetailResponse = {
    number: number;
    title: string;
    author: string;
    state: string;
    htmlUrl: string;
    labels: string[];
    createdAt: string;
    updatedAt: string;
};

export type CommentResponse = {
    id: number;
    author: string;
    body: string;
    htmlUrl: string;
    createdAt: string;
    updatedAt: string;
};

export type CreateCommentParameters = {
    owner: string;
    repo: string;
    issueNumber: number;
    body: string;
    requesterGithubUsername: string;
    operationId?: string;
};

export type FindCommentByOperationParameters = {
    owner: string;
    repo: string;
    issueNumber: number;
    operationId: string;
    since: string;
};

@Injectable()
export class IssueHttpService {
    constructor(private readonly apiClient: IssueHttpApiClient) {}

    async listIssues(
        owner: string,
        repo: string,
        state: string,
    ): Promise<IssueDetailResponse[]> {
        this.assertValidState(state);

        const issues = await this.apiClient.listIssues(owner, repo, state);

        // GitHub의 이슈 목록 API는 PR도 함께 반환하므로, pull_request 필드가 있는 항목은 제외한다.
        return issues
            .filter(issue => issue.pull_request == null)
            .map(issue => this.toIssueDetailResponse(issue));
    }

    async listComments(
        owner: string,
        repo: string,
        issueNumber: number,
    ): Promise<CommentResponse[]> {
        const comments = await this.apiClient.listComments(
            owner,
            repo,
            issueNumber,
        );
        return comments.map(comment => this.toCommentResponse(comment));
    }

    async createComment({
        owner,
        repo,
        issueNumber,
        body,
        requesterGithubUsername,
        operationId,
    }: CreateCommentParameters): Promise<CommentResponse> {
        const markedBody = appendAuthorMarker(body, requesterGithubUsername, operationId);
        const created = await this.apiClient.createComment(
            owner,
            repo,
            issueNumber,
            markedBody,
        );
        return this.toCommentResponse(created);
    }

    // GitHub since 이후 수정된 댓글 한 페이지만 본다. 한 페이지가 가득 찼는데 없으면 없다고 단정할 수 없어 'unknown'이다.
    async findCommentByOperation({
        owner,
        repo,
        issueNumber,
        operationId,
        since,
    }: FindCommentByOperationParameters): Promise<CommentResponse | 'absent' | 'unknown'> {
        const comments = await this.apiClient.listComments(owner, repo, issueNumber, since);
        const found = comments.find(comment => extractCommentAuthor(comment.body, '').operationId === operationId);
        if (found !== undefined) {
            return this.toCommentResponse(found);
        }

        return comments.length < 100 ? 'absent' : 'unknown';
    }

    async updateComment(
        owner: string,
        repo: string,
        commentId: number,
        body: string,
    ): Promise<CommentResponse> {
        const existing = await this.apiClient.getComment(owner, repo, commentId);
        const {author, operationId} = extractCommentAuthor(existing.body, existing.user?.login ?? 'ghost');
        const markedBody = appendAuthorMarker(body, author, operationId);
        const updated = await this.apiClient.updateComment(
            owner,
            repo,
            commentId,
            markedBody,
        );
        return this.toCommentResponse(updated);
    }

    async deleteComment(
        owner: string,
        repo: string,
        commentId: number,
    ): Promise<void> {
        await this.apiClient.deleteComment(owner, repo, commentId);
    }

    private toIssueDetailResponse(issue: IssueDetail): IssueDetailResponse {
        return {
            number: issue.number,
            title: issue.title,
            author: issue.user?.login ?? 'ghost',
            state: issue.state,
            htmlUrl: issue.html_url,
            labels: issue.labels?.map(label => label.name) ?? [],
            createdAt: issue.created_at,
            updatedAt: issue.updated_at,
        };
    }

    private assertValidState(state: string): void {
        if (
            !ALLOWED_ISSUE_STATES.includes(
                state as (typeof ALLOWED_ISSUE_STATES)[number],
            )
        ) {
            throw new GithubClientError(
                'state 쿼리 파라미터는 open, closed, all 중 하나여야 합니다.',
                400,
            );
        }
    }

    private toCommentResponse(comment: GithubComment): CommentResponse {
        const {author, body} = extractCommentAuthor(comment.body, comment.user?.login ?? 'ghost');
        return {
            id: comment.id,
            author,
            body,
            htmlUrl: comment.html_url,
            createdAt: comment.created_at,
            updatedAt: comment.updated_at,
        };
    }
}
