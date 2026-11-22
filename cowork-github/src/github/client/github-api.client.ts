import {HttpService} from '@nestjs/axios';
import {Injectable as injectable} from '@nestjs/common';
import {firstValueFrom} from 'rxjs';
import {githubField} from '../github-field.js';
import {GithubAuthService} from '../auth/github-auth.service.js';
import {GITHUB_API, githubAuthHeaders} from '../constants.js';
import {CreateIssueDto} from '../dto/create-issue.dto.js';
import {requestGithub} from '../github-rate-limit.js';

export type CreatedIssue = {
	number: number;
	html_url: string;
};

export type SearchedIssue = {
	number: number;
	html_url: string;
	title: string;
	labels: Array<{name: string}>;
};

export type GithubLabel = {
	name: string;
};

export type CreateLabelPayload = {
	name: string;
	color: string;
	description?: string;
};

@injectable()
export class GithubApiClient {
	constructor(
		private readonly httpService: HttpService,
		private readonly authService: GithubAuthService,
	) {}

	async createIssue(dto: CreateIssueDto): Promise<CreatedIssue> {
		const {data} = await requestGithub(async () => {
			const token = await this.authService.getInstallationToken(dto.owner);
			return firstValueFrom(
				this.httpService.post<CreatedIssue>(
					`${GITHUB_API}/repos/${dto.owner}/${dto.repo}/issues`,
					{
						title: dto.title,
						body: dto.body,
						labels: dto.labels,
						assignees: dto.assignees,
					},
					{headers: githubAuthHeaders(token)},
				),
			);
		});
		return {number: data.number, [githubField.htmlUrl]: data.html_url};
	}

	async searchOpenIssuesByTitle(dto: CreateIssueDto): Promise<SearchedIssue[]> {
		const escapedTitle = dto.title
			.replaceAll('\\', '\\\\')
			.replaceAll('"', String.raw`\"`);
		const query = `repo:${dto.owner}/${dto.repo} is:issue is:open in:title "${escapedTitle}"`;
		const {data} = await requestGithub(async () => {
			const token = await this.authService.getInstallationToken(dto.owner);
			return firstValueFrom(
				this.httpService.get<{items: SearchedIssue[]}>(
					`${GITHUB_API}/search/issues`,
					{
						params: {q: query},
						headers: githubAuthHeaders(token),
					},
				),
			);
		});
		return data.items.map(item => ({
			number: item.number,
			[githubField.htmlUrl]: item.html_url,
			title: item.title,
			labels: (item.labels ?? []).map(l => ({name: l.name})),
		}));
	}

	async addLabelsToIssue(
		dto: CreateIssueDto,
		issueNumber: number,
		labels: string[],
	): Promise<void> {
		await requestGithub(async () => {
			const token = await this.authService.getInstallationToken(dto.owner);
			return firstValueFrom(
				this.httpService.post(
					`${GITHUB_API}/repos/${dto.owner}/${dto.repo}/issues/${issueNumber}/labels`,
					{labels},
					{headers: githubAuthHeaders(token)},
				),
			);
		});
	}

	async listLabels(dto: CreateIssueDto): Promise<string[]> {
		const {data} = await requestGithub(async () => {
			const token = await this.authService.getInstallationToken(dto.owner);
			return firstValueFrom(
				this.httpService.get<GithubLabel[]>(
					`${GITHUB_API}/repos/${dto.owner}/${dto.repo}/labels`,
					{params: {[githubField.perPage]: 100}, headers: githubAuthHeaders(token)},
				),
			);
		});
		return data.map(label => label.name);
	}

	async createLabel(
		dto: CreateIssueDto,
		label: CreateLabelPayload,
	): Promise<void> {
		await requestGithub(async () => {
			const token = await this.authService.getInstallationToken(dto.owner);
			return firstValueFrom(
				this.httpService.post(
					`${GITHUB_API}/repos/${dto.owner}/${dto.repo}/labels`,
					label,
					{headers: githubAuthHeaders(token)},
				),
			);
		});
	}
}
