import {HttpService} from '@nestjs/axios';
import {Injectable as injectable} from '@nestjs/common';
import {firstValueFrom} from 'rxjs';
import {type Nullable} from '../../common/nullable.js';
import {githubField} from '../../github-field.js';
import {GithubAuthService} from '../../auth/github-auth.service.js';
import {
	GITHUB_API,
	githubAuthHeaders,
	GITHUB_PR_MERGE_METHOD,
} from '../../constants.js';
import {rethrowGithubError} from '../../github.errors.js';

export type PullRequestRef = {
	ref: string;
	repo: Nullable<{full_name: string}>;
};

export type PullRequestDetail = {
	number: number;
	title: string;
	body: Nullable<string>;
	state: string;
	merged: boolean;
	mergeable: Nullable<boolean>;
	mergeable_state: string;
	html_url: string;
	user: {login: string};
	head: PullRequestRef;
	base: PullRequestRef;
};

export type GithubPullRequestListItem = {
	number: number;
	title: string;
	state: string;
	draft: boolean;
	merged_at: Nullable<string>;
	html_url: string;
	user: Nullable<{login: string}>;
	labels: Array<{name: string}>;
	created_at: string;
	updated_at: string;
};

export type PullRequestFile = {
	filename: string;
	status: string;
	additions: number;
	deletions: number;
	patch?: string;
};

export type PullRequestReview = {
	user: {login: string};
	state: string;
	submitted_at: string;
};

export type CollaboratorPermission = {
	permission: string;
};

@injectable()
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
			const {data} = await firstValueFrom(
				this.httpService.get<PullRequestDetail>(
					`${GITHUB_API}/repos/${owner}/${repo}/pulls/${prNumber}`,
					{headers: githubAuthHeaders(token)},
				),
			);
			return data;
		} catch (error) {
			rethrowGithubError(error);
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
			const {data} = await firstValueFrom(
				this.httpService.get<GithubPullRequestListItem[]>(
					`${GITHUB_API}/repos/${owner}/${repo}/pulls`,
					{
						headers: githubAuthHeaders(token),
						params: {
							state,
							[githubField.perPage]: 100,
							sort: 'created',
							direction: 'desc',
						},
					},
				),
			);
			return data;
		} catch (error) {
			rethrowGithubError(error);
		}
	}

	async listPullRequestFiles(
		owner: string,
		repo: string,
		prNumber: number,
	): Promise<PullRequestFile[]> {
		const token = await this.authService.getInstallationToken(owner);
		try {
			const {data} = await firstValueFrom(
				this.httpService.get<PullRequestFile[]>(
					`${GITHUB_API}/repos/${owner}/${repo}/pulls/${prNumber}/files`,
					{params: {[githubField.perPage]: 100}, headers: githubAuthHeaders(token)},
				),
			);
			return data;
		} catch (error) {
			rethrowGithubError(error);
		}
	}

	async listPullRequestReviews(
		owner: string,
		repo: string,
		prNumber: number,
	): Promise<PullRequestReview[]> {
		const token = await this.authService.getInstallationToken(owner);
		try {
			const {data} = await firstValueFrom(
				this.httpService.get<PullRequestReview[]>(
					`${GITHUB_API}/repos/${owner}/${repo}/pulls/${prNumber}/reviews`,
					{params: {[githubField.perPage]: 100}, headers: githubAuthHeaders(token)},
				),
			);
			return data;
		} catch (error) {
			rethrowGithubError(error);
		}
	}

	async getCollaboratorPermission(
		owner: string,
		repo: string,
		username: string,
	): Promise<CollaboratorPermission> {
		const token = await this.authService.getInstallationToken(owner);
		try {
			const {data} = await firstValueFrom(
				this.httpService.get<CollaboratorPermission>(
					`${GITHUB_API}/repos/${owner}/${repo}/collaborators/${username}/permission`,
					{headers: githubAuthHeaders(token)},
				),
			);
			return data;
		} catch (error) {
			rethrowGithubError(error);
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
					{[githubField.mergeMethod]: GITHUB_PR_MERGE_METHOD},
					{headers: githubAuthHeaders(token)},
				),
			);
		} catch (error) {
			rethrowGithubError(error);
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
					{event: 'APPROVE'},
					{headers: githubAuthHeaders(token)},
				),
			);
		} catch (error) {
			rethrowGithubError(error);
		}
	}

	async closePullRequest(
		owner: string,
		repo: string,
		prNumber: number,
	): Promise<void> {
		const token = await this.authService.getInstallationToken(owner);
		try {
			await firstValueFrom(
				this.httpService.patch(
					`${GITHUB_API}/repos/${owner}/${repo}/pulls/${prNumber}`,
					{state: 'closed'},
					{headers: githubAuthHeaders(token)},
				),
			);
		} catch (error) {
			rethrowGithubError(error);
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
					{headers: githubAuthHeaders(token)},
				),
			);
		} catch (error) {
			rethrowGithubError(error);
		}
	}
}
