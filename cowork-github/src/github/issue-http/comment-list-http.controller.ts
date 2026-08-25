import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpException,
  Param,
  ParseIntPipe,
  Post,
  UseGuards,
} from '@nestjs/common';
import { InternalApiKeyGuard } from '../common/guards/internal-api-key.guard';
import { GithubClientError } from '../github.errors';
import { CreateCommentRequestDto } from './dto/create-comment-request.dto';
import { IssueHttpService } from './issue-http.service';

@Controller('api/repos/:owner/:repo/issues/:number/comments')
@UseGuards(InternalApiKeyGuard)
export class CommentListHttpController {
  constructor(private readonly issueHttpService: IssueHttpService) {}

  @Get()
  async list(
    @Param('owner') owner: string,
    @Param('repo') repo: string,
    @Param('number', ParseIntPipe) issueNumber: number,
  ) {
    return this.handle(() =>
      this.issueHttpService.listComments(owner, repo, issueNumber),
    );
  }

  @Post()
  @HttpCode(201)
  async create(
    @Param('owner') owner: string,
    @Param('repo') repo: string,
    @Param('number', ParseIntPipe) issueNumber: number,
    @Body() request: CreateCommentRequestDto,
  ) {
    return this.handle(() =>
      this.issueHttpService.createComment(
        owner,
        repo,
        issueNumber,
        request.body,
        request.requesterGithubUsername,
      ),
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
