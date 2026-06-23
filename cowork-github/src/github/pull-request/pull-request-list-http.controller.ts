import {
  Controller,
  Get,
  HttpException,
  Param,
  Query,
  UseGuards,
} from '@nestjs/common';
import { GithubClientError } from '../github.errors';
import { InternalApiKeyGuard } from './guards/internal-api-key.guard';
import { PullRequestService } from './pull-request.service';

@Controller('api/repos/:owner/:repo/pulls')
@UseGuards(InternalApiKeyGuard)
export class PullRequestListHttpController {
  constructor(private readonly pullRequestService: PullRequestService) {}

  @Get()
  async list(
    @Param('owner') owner: string,
    @Param('repo') repo: string,
    @Query('state') state = 'open',
  ) {
    return this.handle(() =>
      this.pullRequestService.listPullRequests(owner, repo, state),
    );
  }

  private async handle<T>(fn: () => Promise<T>): Promise<T> {
    try {
      return await fn();
    } catch (error) {
      if (error instanceof GithubClientError) {
        throw new HttpException(error.message, error.statusCode);
      }
      throw new HttpException(
        'GitHub 서버와 통신 중 오류가 발생했습니다.',
        502,
      );
    }
  }
}
