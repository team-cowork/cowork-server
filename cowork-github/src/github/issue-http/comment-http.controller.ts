import {
  Controller,
  Get,
  HttpException,
  Param,
  ParseIntPipe,
  UseGuards,
} from '@nestjs/common';
import { InternalApiKeyGuard } from '../common/guards/internal-api-key.guard';
import { GithubClientError } from '../github.errors';
import { IssueHttpService } from './issue-http.service';

@Controller('api/repos/:owner/:repo/issues/comments/:commentId')
@UseGuards(InternalApiKeyGuard)
export class CommentHttpController {
  constructor(private readonly issueHttpService: IssueHttpService) {}

  @Get()
  async getDetail(
    @Param('owner') owner: string,
    @Param('repo') repo: string,
    @Param('commentId', ParseIntPipe) commentId: number,
  ) {
    return this.handle(() =>
      this.issueHttpService.getComment(owner, repo, commentId),
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
