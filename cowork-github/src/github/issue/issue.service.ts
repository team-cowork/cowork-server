import { Injectable, Logger } from '@nestjs/common';
import { AppConfigService } from '../../config/app-config.service';
import { GithubApiClient } from '../client/github-api.client';
import { CreateIssueDto } from '../dto/create-issue.dto';
import { GithubClientError } from '../github.errors';
import { LabelService } from './label.service';

@Injectable()
export class IssueService {
  private readonly logger = new Logger(IssueService.name);

  constructor(
    private readonly config: AppConfigService,
    private readonly apiClient: GithubApiClient,
    private readonly labelService: LabelService,
  ) {}

  async createIssue(
    dto: CreateIssueDto,
  ): Promise<{ issueUrl: string; issueNumber: number }> {
    const maxRetries = this.config.githubIssueMaxRetries;

    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        const labels = this.labelService.resolveLabels(dto);
        await this.labelService.ensureLabelsExist(dto, labels);

        const existingIssues =
          await this.apiClient.searchOpenIssuesByTitle(dto);
        const existingIssue = existingIssues.find(
          (issue) => issue.title === dto.title,
        );

        if (existingIssue) {
          const newLabels = labels.filter(
            (label) =>
              !existingIssue.labels.some(
                (existingLabel) =>
                  existingLabel.name.toLowerCase() === label.toLowerCase(),
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
        return { issueUrl: result.html_url, issueNumber: result.number };
      } catch (error) {
        if (error instanceof GithubClientError) throw error;

        this.logger.error('GitHub API error', {
          owner: dto.owner,
          repo: dto.repo,
          title: dto.title,
          attempt,
          message: (error as Error).message,
        });

        if (attempt === maxRetries) throw error;
        await this.sleep(Math.pow(2, attempt - 1) * 1000);
      }
    }
    throw new Error('createIssue: max retries exceeded without result');
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
}
