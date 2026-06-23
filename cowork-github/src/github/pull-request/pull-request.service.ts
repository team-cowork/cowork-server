import { Injectable, Logger } from '@nestjs/common';
import { GITHUB_PR_WRITE_PERMISSIONS } from '../constants';
import { GithubClientError } from '../github.errors';
import {
  PullRequestApiClient,
  PullRequestDetail,
  PullRequestFile,
} from './client/pull-request-api.client';

export interface PullRequestSummaryResponse {
  number: number;
  title: string;
  author: string;
  state: string;
  draft: boolean;
  merged: boolean;
  htmlUrl: string;
  labels: string[];
  createdAt: string;
  updatedAt: string;
}

export interface PullRequestActionParams {
  owner: string;
  repo: string;
  prNumber: number;
  requesterGithubUsername: string;
}

export interface PullRequestDetailResponse {
  number: number;
  title: string;
  body: string | null;
  author: string;
  state: string;
  mergeable: boolean | null;
  mergeableState: string;
  reviewDecision: 'APPROVED' | 'CHANGES_REQUESTED' | null;
  headRef: string;
  baseRef: string;
  htmlUrl: string;
}

export interface MergePullRequestResult {
  alreadyMerged: boolean;
  prUrl: string;
  prNumber: number;
}

export interface ApprovePullRequestResult {
  prUrl: string;
  prNumber: number;
}

@Injectable()
export class PullRequestService {
  private readonly logger = new Logger(PullRequestService.name);

  constructor(private readonly apiClient: PullRequestApiClient) {}

  async getPullRequestDetail(
    owner: string,
    repo: string,
    prNumber: number,
  ): Promise<PullRequestDetailResponse> {
    const pr = await this.apiClient.getPullRequest(owner, repo, prNumber);
    const reviews = await this.apiClient.listPullRequestReviews(
      owner,
      repo,
      prNumber,
    );

    return {
      number: pr.number,
      title: pr.title,
      body: pr.body,
      author: pr.user.login,
      state: pr.state,
      mergeable: pr.mergeable,
      mergeableState: pr.mergeable_state,
      reviewDecision: this.deriveReviewDecision(reviews),
      headRef: pr.head.ref,
      baseRef: pr.base.ref,
      htmlUrl: pr.html_url,
    };
  }

  async listPullRequests(
    owner: string,
    repo: string,
    state: string,
  ): Promise<PullRequestSummaryResponse[]> {
    const items = await this.apiClient.listPullRequests(owner, repo, state);

    return items.map((item) => ({
      number: item.number,
      title: item.title,
      author: item.user.login,
      state: item.state,
      draft: item.draft,
      merged: item.merged_at != null,
      htmlUrl: item.html_url,
      labels: item.labels.map((label) => label.name),
      createdAt: item.created_at,
      updatedAt: item.updated_at,
    }));
  }

  async listPullRequestFiles(
    owner: string,
    repo: string,
    prNumber: number,
  ): Promise<PullRequestFile[]> {
    return this.apiClient.listPullRequestFiles(owner, repo, prNumber);
  }

  async mergePullRequest(
    params: PullRequestActionParams,
  ): Promise<MergePullRequestResult> {
    const { owner, repo, prNumber, requesterGithubUsername } = params;
    const pr = await this.apiClient.getPullRequest(owner, repo, prNumber);

    if (pr.merged) {
      this.logger.log('PR already merged, returning idempotent result', {
        owner,
        repo,
        prNumber,
      });
      return { alreadyMerged: true, prUrl: pr.html_url, prNumber };
    }

    await this.assertWritePermission(owner, repo, requesterGithubUsername);

    try {
      await this.apiClient.mergePullRequest(owner, repo, prNumber);
    } catch (error) {
      if (error instanceof GithubClientError && error.statusCode === 405) {
        throw new GithubClientError(
          `머지할 수 없는 상태입니다 (mergeable_state: ${pr.mergeable_state})`,
          409,
        );
      }
      throw error;
    }

    await this.deleteHeadBranchIfSameRepo(owner, repo, pr);

    this.logger.log('PR merged', { owner, repo, prNumber });
    return { alreadyMerged: false, prUrl: pr.html_url, prNumber };
  }

  async approvePullRequest(
    params: PullRequestActionParams,
  ): Promise<ApprovePullRequestResult> {
    const { owner, repo, prNumber, requesterGithubUsername } = params;
    const pr = await this.apiClient.getPullRequest(owner, repo, prNumber);

    this.assertNotSelfReview(pr.user.login, requesterGithubUsername);
    await this.assertWritePermission(owner, repo, requesterGithubUsername);

    await this.apiClient.approvePullRequest(owner, repo, prNumber);

    this.logger.log('PR approved', {
      owner,
      repo,
      prNumber,
      requesterGithubUsername,
    });
    return { prUrl: pr.html_url, prNumber };
  }

  private async assertWritePermission(
    owner: string,
    repo: string,
    username: string,
  ): Promise<void> {
    const { permission } = await this.apiClient.getCollaboratorPermission(
      owner,
      repo,
      username,
    );

    if (
      !GITHUB_PR_WRITE_PERMISSIONS.includes(
        permission as (typeof GITHUB_PR_WRITE_PERMISSIONS)[number],
      )
    ) {
      throw new GithubClientError(
        '이 저장소에 대한 쓰기 권한이 없습니다.',
        403,
      );
    }
  }

  private assertNotSelfReview(
    authorLogin: string,
    requesterGithubUsername: string,
  ): void {
    if (authorLogin.toLowerCase() === requesterGithubUsername.toLowerCase()) {
      throw new GithubClientError(
        '본인이 작성한 PR은 승인할 수 없습니다.',
        403,
      );
    }
  }

  private async deleteHeadBranchIfSameRepo(
    owner: string,
    repo: string,
    pr: PullRequestDetail,
  ): Promise<void> {
    const isSameRepo =
      !!pr.head.repo &&
      !!pr.base.repo &&
      pr.head.repo.full_name === pr.base.repo.full_name;
    if (!isSameRepo) return;

    try {
      await this.apiClient.deleteBranch(owner, repo, pr.head.ref);
    } catch (error) {
      this.logger.warn('Failed to delete head branch after merge', {
        owner,
        repo,
        branch: pr.head.ref,
        message: (error as Error).message,
      });
    }
  }

  private deriveReviewDecision(
    reviews: { user: { login: string }; state: string; submitted_at: string }[],
  ): 'APPROVED' | 'CHANGES_REQUESTED' | null {
    const latestByUser = new Map<string, string>();
    const sorted = [...reviews].sort(
      (a, b) =>
        new Date(a.submitted_at).getTime() - new Date(b.submitted_at).getTime(),
    );

    for (const review of sorted) {
      if (review.state === 'APPROVED' || review.state === 'CHANGES_REQUESTED') {
        latestByUser.set(review.user.login, review.state);
      } else if (review.state === 'DISMISSED') {
        latestByUser.delete(review.user.login);
      }
    }

    const states = [...latestByUser.values()];
    if (states.includes('CHANGES_REQUESTED')) return 'CHANGES_REQUESTED';
    if (states.includes('APPROVED')) return 'APPROVED';
    return null;
  }
}
