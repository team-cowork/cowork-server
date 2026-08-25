import { HttpService } from '@nestjs/axios';
import { Injectable } from '@nestjs/common';
import { AxiosError } from 'axios';
import { firstValueFrom } from 'rxjs';
import { GithubAuthService } from '../../auth/github-auth.service';
import { GITHUB_API, GITHUB_HEADERS } from '../../constants';
import { GithubClientError } from '../../github.errors';

export interface IssueDetail {
  number: number;
  title: string;
  state: string;
  html_url: string;
  user: { login: string } | null;
  labels: { name: string }[];
  created_at: string;
  updated_at: string;
}

export interface GithubComment {
  id: number;
  body: string;
  html_url: string;
  user: { login: string } | null;
  created_at: string;
  updated_at: string;
}

@Injectable()
export class IssueHttpApiClient {
  constructor(
    private readonly httpService: HttpService,
    private readonly authService: GithubAuthService,
  ) {}

  async getIssue(
    owner: string,
    repo: string,
    issueNumber: number,
  ): Promise<IssueDetail> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      const { data } = await firstValueFrom(
        this.httpService.get<IssueDetail>(
          `${GITHUB_API}/repos/${owner}/${repo}/issues/${issueNumber}`,
          { headers: this.authHeaders(token) },
        ),
      );
      return data;
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async listComments(
    owner: string,
    repo: string,
    issueNumber: number,
  ): Promise<GithubComment[]> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      const { data } = await firstValueFrom(
        this.httpService.get<GithubComment[]>(
          `${GITHUB_API}/repos/${owner}/${repo}/issues/${issueNumber}/comments`,
          { params: { per_page: 100 }, headers: this.authHeaders(token) },
        ),
      );
      return data;
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async createComment(
    owner: string,
    repo: string,
    issueNumber: number,
    body: string,
  ): Promise<GithubComment> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      const { data } = await firstValueFrom(
        this.httpService.post<GithubComment>(
          `${GITHUB_API}/repos/${owner}/${repo}/issues/${issueNumber}/comments`,
          { body },
          { headers: this.authHeaders(token) },
        ),
      );
      return data;
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async getComment(
    owner: string,
    repo: string,
    commentId: number,
  ): Promise<GithubComment> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      const { data } = await firstValueFrom(
        this.httpService.get<GithubComment>(
          `${GITHUB_API}/repos/${owner}/${repo}/issues/comments/${commentId}`,
          { headers: this.authHeaders(token) },
        ),
      );
      return data;
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async updateComment(
    owner: string,
    repo: string,
    commentId: number,
    body: string,
  ): Promise<GithubComment> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      const { data } = await firstValueFrom(
        this.httpService.patch<GithubComment>(
          `${GITHUB_API}/repos/${owner}/${repo}/issues/comments/${commentId}`,
          { body },
          { headers: this.authHeaders(token) },
        ),
      );
      return data;
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async deleteComment(
    owner: string,
    repo: string,
    commentId: number,
  ): Promise<void> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      await firstValueFrom(
        this.httpService.delete(
          `${GITHUB_API}/repos/${owner}/${repo}/issues/comments/${commentId}`,
          { headers: this.authHeaders(token) },
        ),
      );
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  private authHeaders(token: string) {
    return { ...GITHUB_HEADERS, Authorization: `Bearer ${token}` };
  }

  private handleGithubError(error: unknown): never {
    if (
      error instanceof AxiosError &&
      error.response &&
      error.response.status < 500 &&
      error.response.status !== 429
    ) {
      throw new GithubClientError(
        (error.response.data as { message?: string })?.message ?? error.message,
        error.response.status,
      );
    }
    throw error;
  }
}
