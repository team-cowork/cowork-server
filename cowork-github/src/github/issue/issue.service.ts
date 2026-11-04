import {Injectable as injectable, Logger} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service.js';
import {GithubApiClient} from '../client/github-api.client.js';
import {CreateIssueDto} from '../dto/create-issue.dto.js';
import {GithubClientError} from '../github.errors.js';
import {LabelService} from './label.service.js';

@injectable()
export class IssueService {
	private readonly logger = new Logger(IssueService.name);

	constructor(
		private readonly config: AppConfigService,
		private readonly apiClient: GithubApiClient,
		private readonly labelService: LabelService,
	) {}

	private async createIssueOnce(
		dto: CreateIssueDto,
	): Promise<{issueUrl: string; issueNumber: number}> {
		const labels = this.labelService.resolveLabels(dto);
		await this.labelService.ensureLabelsExist(dto, labels);

		const existingIssues =
			await this.apiClient.searchOpenIssuesByTitle(dto);
		const existingIssue = existingIssues.find(
			issue => issue.title === dto.title,
		);

		if (existingIssue) {
			const newLabels = labels.filter(
				label =>
					existingIssue.labels.every(
						existingLabel =>
							existingLabel.name.toLowerCase() !== label.toLowerCase(),
					),
			);

			if (newLabels.length > 0) {
				await this.apiClient.addLabelsToIssue(
					dto,
					existingIssue.number,
					newLabels,
				);
				this.logger.log('Labels added to existing issue', {
					owner: dto.owner,
					repo: dto.repo,
					issueNumber: existingIssue.number,
					labels: newLabels,
				});
			}

			this.logger.log('Existing issue found', {
				owner: dto.owner,
				repo: dto.repo,
				issueNumber: existingIssue.number,
				issueUrl: existingIssue.html_url,
			});
			return {
				issueUrl: existingIssue.html_url,
				issueNumber: existingIssue.number,
			};
		}

		const result = await this.apiClient.createIssue({
			...dto,
			labels,
		});
		this.logger.log('Issue created', {
			owner: dto.owner,
			repo: dto.repo,
			issueNumber: result.number,
			issueUrl: result.html_url,
			labels,
		});
		return {issueUrl: result.html_url, issueNumber: result.number};
	}

	private async sleep(ms: number): Promise<void> {
		return new Promise(resolve => {
			setTimeout(resolve, ms);
		});
	}

	// Retry-with-backoff: each attempt waits for the previous one to fail.
	private async createIssueWithRetry(
		dto: CreateIssueDto,
		attempt: number,
		maxRetries: number,
	): Promise<{issueUrl: string; issueNumber: number}> {
		try {
			return await this.createIssueOnce(dto);
		} catch (error) {
			if (error instanceof GithubClientError) {
				throw error;
			}

			this.logger.error('GitHub API error', {
				owner: dto.owner,
				repo: dto.repo,
				title: dto.title,
				attempt,
				message: (error as Error).message,
			});

			if (attempt >= maxRetries) {
				throw error;
			}

			await this.sleep((2 ** (attempt - 1)) * 1000);
			return this.createIssueWithRetry(dto, attempt + 1, maxRetries);
		}
	}

	async createIssue(
		dto: CreateIssueDto,
	): Promise<{issueUrl: string; issueNumber: number}> {
		return this.createIssueWithRetry(dto, 1, this.config.githubIssueMaxRetries);
	}
}
