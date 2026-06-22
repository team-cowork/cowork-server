import {
  Body,
  Controller,
  Get,
  HttpException,
  Param,
  ParseIntPipe,
  Post,
  UseGuards,
} from '@nestjs/common';
import { GithubClientError } from '../github.errors';
import { PullRequestActionRequestDto } from './dto/pull-request-action-request.dto';
import { InternalApiKeyGuard } from './guards/internal-api-key.guard';
import { PullRequestService } from './pull-request.service';

@Controller('api/repos/:owner/:repo/pulls/:number')
@UseGuards(InternalApiKeyGuard)
export class PullRequestHttpController {
  constructor(private readonly pullRequestService: PullRequestService) {}

  @Get()
  async getDetail(
    @Param('owner') owner: string,
    @Param('repo') repo: string,
    @Param('number', ParseIntPipe) prNumber: number,
  ) {
    return this.handle(() =>
      this.pullRequestService.getPullRequestDetail(owner, repo, prNumber),
    );
  }

  @Get('files')
  async getFiles(
    @Param('owner') owner: string,
    @Param('repo') repo: string,
    @Param('number', ParseIntPipe) prNumber: number,
  ) {
    return this.handle(() =>
      this.pullRequestService.listPullRequestFiles(owner, repo, prNumber),
    );
  }

  @Post('merge')
  async merge(
    @Param('owner') owner: string,
    @Param('repo') repo: string,
    @Param('number', ParseIntPipe) prNumber: number,
    @Body() body: PullRequestActionRequestDto,
  ) {
    return this.handle(() =>
      this.pullRequestService.mergePullRequest({
        owner,
        repo,
        prNumber,
        requesterGithubUsername: body.requesterGithubUsername,
      }),
    );
  }

  @Post('approve')
  async approve(
    @Param('owner') owner: string,
    @Param('repo') repo: string,
    @Param('number', ParseIntPipe) prNumber: number,
    @Body() body: PullRequestActionRequestDto,
  ) {
    return this.handle(() =>
      this.pullRequestService.approvePullRequest({
        owner,
        repo,
        prNumber,
        requesterGithubUsername: body.requesterGithubUsername,
      }),
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
