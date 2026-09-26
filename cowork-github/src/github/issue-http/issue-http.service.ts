import { Injectable } from '@nestjs/common';
import {
  GithubComment,
  IssueHttpApiClient,
} from './client/issue-http-api.client';

export interface IssueDetailResponse {
  number: number;
  title: string;
  author: string;
  state: string;
  htmlUrl: string;
  labels: string[];
  createdAt: string;
  updatedAt: string;
}

export interface CommentResponse {
  id: number;
  author: string;
  body: string;
  htmlUrl: string;
  createdAt: string;
  updatedAt: string;
}

// GitHub App 설치 토큰으로 댓글을 작성하면 GitHub상 작성자는 항상 이 앱(bot) 계정으로 보인다.
// 실제 작성자를 보존하기 위해 본문 끝에 렌더링되지 않는 HTML 주석으로 마커를 남기고,
// 응답을 만들 때 이 마커를 읽어 author를 실제 작성자로 덮어쓴다. 수정 시에는 새 마커를
// 붙이지 않고 기존 댓글의 마커(원작성자)를 그대로 유지한다.
const AUTHOR_MARKER_PATTERN = /\n*<!--\s*cowork:author=(\S+)\s*-->\s*$/;

@Injectable()
export class IssueHttpService {
  constructor(private readonly apiClient: IssueHttpApiClient) {}

  async getIssueDetail(
    owner: string,
    repo: string,
    issueNumber: number,
  ): Promise<IssueDetailResponse> {
    const issue = await this.apiClient.getIssue(owner, repo, issueNumber);
    return {
      number: issue.number,
      title: issue.title,
      author: issue.user?.login ?? 'ghost',
      state: issue.state,
      htmlUrl: issue.html_url,
      labels: issue.labels?.map((label) => label.name) ?? [],
      createdAt: issue.created_at,
      updatedAt: issue.updated_at,
    };
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
    return comments.map((comment) => this.toCommentResponse(comment));
  }

  async createComment(
    owner: string,
    repo: string,
    issueNumber: number,
    body: string,
    requesterGithubUsername: string,
  ): Promise<CommentResponse> {
    const markedBody = this.appendAuthorMarker(body, requesterGithubUsername);
    const created = await this.apiClient.createComment(
      owner,
      repo,
      issueNumber,
      markedBody,
    );
    return this.toCommentResponse(created);
  }

  async getComment(
    owner: string,
    repo: string,
    commentId: number,
  ): Promise<CommentResponse> {
    const comment = await this.apiClient.getComment(owner, repo, commentId);
    return this.toCommentResponse(comment);
  }

  async updateComment(
    owner: string,
    repo: string,
    commentId: number,
    body: string,
  ): Promise<CommentResponse> {
    const existing = await this.apiClient.getComment(owner, repo, commentId);
    const { author } = this.extractAuthor(existing);
    const markedBody = this.appendAuthorMarker(body, author);
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

  private toCommentResponse(comment: GithubComment): CommentResponse {
    const { author, body } = this.extractAuthor(comment);
    return {
      id: comment.id,
      author,
      body,
      htmlUrl: comment.html_url,
      createdAt: comment.created_at,
      updatedAt: comment.updated_at,
    };
  }

  private extractAuthor(comment: GithubComment): {
    author: string;
    body: string;
  } {
    const match = comment.body.match(AUTHOR_MARKER_PATTERN);
    if (!match) {
      return { author: comment.user?.login ?? 'ghost', body: comment.body };
    }
    return { author: match[1], body: comment.body.slice(0, match.index ?? 0) };
  }

  private appendAuthorMarker(body: string, username: string): string {
    const stripped = body.replace(AUTHOR_MARKER_PATTERN, '');
    return `${stripped}\n\n<!-- cowork:author=${username} -->`;
  }
}
