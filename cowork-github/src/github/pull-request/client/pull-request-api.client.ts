import { HttpService } from '@nestjs/axios';
import { Injectable } from '@nestjs/common';
import { AxiosError } from 'axios';
import { firstValueFrom } from 'rxjs';
import { GithubAuthService } from '../../auth/github-auth.service';
import {
  GITHUB_API,
  GITHUB_HEADERS,
  GITHUB_PR_MERGE_METHOD,
} from '../../constants';
import { GithubClientError } from '../../github.errors';

export interface PullRequestRef {
  ref: string;
  repo: { full_name: string } | null;
}

export interface PullRequestDetail {
  number: number;
  title: string;
  body: string | null;
  state: string;
  merged: boolean;
  mergeable: boolean | null;
  mergeable_state: string;
  html_url: string;
  user: { login: string };
  head: PullRequestRef;
  base: PullRequestRef;
}

export interface GithubPullRequestListItem {
  number: number;
  title: string;
  state: string;
  draft: boolean;
  merged_at: string | null;
  html_url: string;
  user: { login: string };
  labels: { name: string }[];
  created_at: string;
  updated_at: string;
}

export interface PullRequestFile {
  filename: string;
  status: string;
  additions: number;
  deletions: number;
  patch?: string;
}

export interface PullRequestReview {
  user: { login: string };
  state: string;
  submitted_at: string;
}

export interface CollaboratorPermission {
  permission: string;
}

@Injectable()
export class PullRequestApiClient {
  constructor(
    private readonly httpService: HttpService,
    private readonly authService: GithubAuthService,
  ) {}

  async getPullRequest(
    owner: string,
    repo: string,
    prNumber: number,
  ): Promise<PullRequestDetail> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      const { data } = await firstValueFrom(
        this.httpService.get<PullRequestDetail>(
          `${GITHUB_API}/repos/${owner}/${repo}/pulls/${prNumber}`,
          { headers: this.authHeaders(token) },
        ),
      );
      return data;
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  // MVP: per_page=100 단일 페이지만 조회. 100개 초과 PR 페이지네이션은 후속 과제.
  async listPullRequests(
    owner: string,
    repo: string,
    state: string,
  ): Promise<GithubPullRequestListItem[]> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      const { data } = await firstValueFrom(
        this.httpService.get<GithubPullRequestListItem[]>(
          `${GITHUB_API}/repos/${owner}/${repo}/pulls`,
          {
            headers: this.authHeaders(token),
            params: {
              state,
              per_page: 100,
              sort: 'created',
              direction: 'desc',
            },
          },
        ),
      );
      return data;
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async listPullRequestFiles(
    owner: string,
    repo: string,
    prNumber: number,
  ): Promise<PullRequestFile[]> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      const { data } = await firstValueFrom(
        this.httpService.get<PullRequestFile[]>(
          `${GITHUB_API}/repos/${owner}/${repo}/pulls/${prNumber}/files`,
          { params: { per_page: 100 }, headers: this.authHeaders(token) },
        ),
      );
      return data;
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async listPullRequestReviews(
    owner: string,
    repo: string,
    prNumber: number,
  ): Promise<PullRequestReview[]> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      const { data } = await firstValueFrom(
        this.httpService.get<PullRequestReview[]>(
          `${GITHUB_API}/repos/${owner}/${repo}/pulls/${prNumber}/reviews`,
          { params: { per_page: 100 }, headers: this.authHeaders(token) },
        ),
      );
      return data;
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async getCollaboratorPermission(
    owner: string,
    repo: string,
    username: string,
  ): Promise<CollaboratorPermission> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      const { data } = await firstValueFrom(
        this.httpService.get<CollaboratorPermission>(
          `${GITHUB_API}/repos/${owner}/${repo}/collaborators/${username}/permission`,
          { headers: this.authHeaders(token) },
        ),
      );
      return data;
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async mergePullRequest(
    owner: string,
    repo: string,
    prNumber: number,
  ): Promise<void> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      await firstValueFrom(
        this.httpService.put(
          `${GITHUB_API}/repos/${owner}/${repo}/pulls/${prNumber}/merge`,
          { merge_method: GITHUB_PR_MERGE_METHOD },
          { headers: this.authHeaders(token) },
        ),
      );
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async approvePullRequest(
    owner: string,
    repo: string,
    prNumber: number,
  ): Promise<void> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      await firstValueFrom(
        this.httpService.post(
          `${GITHUB_API}/repos/${owner}/${repo}/pulls/${prNumber}/reviews`,
          { event: 'APPROVE' },
          { headers: this.authHeaders(token) },
        ),
      );
    } catch (error) {
      this.handleGithubError(error);
    }
  }

  async deleteBranch(
    owner: string,
    repo: string,
    branch: string,
  ): Promise<void> {
    const token = await this.authService.getInstallationToken(owner);
    try {
      await firstValueFrom(
        this.httpService.delete(
          `${GITHUB_API}/repos/${owner}/${repo}/git/refs/heads/${branch}`,
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
