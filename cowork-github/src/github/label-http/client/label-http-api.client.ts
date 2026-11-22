import {HttpService} from '@nestjs/axios';
import {Injectable as injectable} from '@nestjs/common';
import {firstValueFrom} from 'rxjs';
import {githubField} from '../../github-field.js';
import {GithubAuthService} from '../../auth/github-auth.service.js';
import {GITHUB_API, githubAuthHeaders} from '../../constants.js';
import {requestGithub} from '../../github-rate-limit.js';
import {rethrowGithubError} from '../../github.errors.js';

export type GithubRepoLabel = {
	name: string;
	color: string;
};

@injectable()
export class LabelHttpApiClient {
	constructor(
		private readonly httpService: HttpService,
		private readonly authService: GithubAuthService,
	) {}

	// MVP: per_page=100 단일 페이지만 조회. 100개 초과 라벨 페이지네이션은 후속 과제.
	async listLabels(owner: string, repo: string): Promise<GithubRepoLabel[]> {
		const token = await this.authService.getInstallationToken(owner);
		try {
			const {data} = await firstValueFrom(
				this.httpService.get<GithubRepoLabel[]>(
					`${GITHUB_API}/repos/${owner}/${repo}/labels`,
					{params: {[githubField.perPage]: 100}, headers: githubAuthHeaders(token)},
				),
			);
			return data;
		} catch (error) {
			rethrowGithubError(error);
		}
	}

	// GitHub의 "Set labels for an issue" 엔드포인트. 기존 라벨 전체를 요청된 목록으로
	// 대체(replace)한다. listLabels(POST /labels 추가)와 달리 additive가 아니다.
	async replaceLabels(
		owner: string,
		repo: string,
		issueNumber: number,
		labels: string[],
	): Promise<GithubRepoLabel[]> {
		const {data} = await requestGithub(async () => {
			const token = await this.authService.getInstallationToken(owner);
			return firstValueFrom(
				this.httpService.put<GithubRepoLabel[]>(
					`${GITHUB_API}/repos/${owner}/${repo}/issues/${issueNumber}/labels`,
					{labels},
					{headers: githubAuthHeaders(token)},
				),
			);
		});
		return data;
	}
}
