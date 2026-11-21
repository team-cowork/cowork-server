import {HttpService} from '@nestjs/axios';
import {Injectable as injectable} from '@nestjs/common';
import {firstValueFrom} from 'rxjs';
import {githubField} from '../../github-field.js';
import {GithubAuthService} from '../../auth/github-auth.service.js';
import {GITHUB_API, githubAuthHeaders} from '../../constants.js';
import {rethrowGithubError} from '../../github.errors.js';

export type GithubRepoSummary = {
	name: string;
	fullName: string;
	private: boolean;
	htmlUrl: string;
};

type GithubRepo = {
	name: string;
	full_name: string;
	private: boolean;
	html_url: string;
};

type ListInstallationRepositoriesResponse = {
	total_count: number;
	repositories: GithubRepo[];
};

@injectable()
export class InstallationApiClient {
	constructor(
		private readonly httpService: HttpService,
		private readonly authService: GithubAuthService,
	) {}

	async listRepositories(owner: string): Promise<GithubRepoSummary[]> {
		const token = await this.authService.getInstallationToken(owner);
		try {
			const {data} = await firstValueFrom(
				this.httpService.get<ListInstallationRepositoriesResponse>(
					`${GITHUB_API}/installation/repositories`,
					{
						params: {[githubField.perPage]: 100},
						headers: githubAuthHeaders(token),
					},
				),
			);
			return data.repositories.map(repo => ({
				name: repo.name,
				fullName: repo.full_name,
				private: repo.private,
				htmlUrl: repo.html_url,
			}));
		} catch (error) {
			rethrowGithubError(error);
		}
	}
}
