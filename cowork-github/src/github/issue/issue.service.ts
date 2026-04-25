import { Injectable, Logger } from '@nestjs/common';
import { AppConfigService } from '../../config/app-config.service';
import { GithubApiClient } from '../client/github-api.client';
import { GithubAuthService } from '../auth/github-auth.service';
import { CreateIssueDto } from '../dto/create-issue.dto';
import { GithubClientError } from '../github.errors';

@Injectable()
export class IssueService {
  private readonly logger = new Logger(IssueService.name);

  constructor(
    private readonly config: AppConfigService,
    private readonly authService: GithubAuthService,
    private readonly apiClient: GithubApiClient,
  ) {}

  async createIssue(dto: CreateIssueDto): Promise<void> {
    const token = await this.authService.getInstallationToken(dto.owner);
    const maxRetries = this.config.githubIssueMaxRetries;

    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        const result = await this.apiClient.createIssue(token, dto);
        this.logger.log(`Issue created: ${result.html_url}`);
        return;
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
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
}
