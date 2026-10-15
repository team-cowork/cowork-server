import {
  Controller,
  Get,
  HttpException,
  Param,
  UseGuards,
} from '@nestjs/common';
import { InternalApiKeyGuard } from '../common/guards/internal-api-key.guard';
import { GithubClientError } from '../github.errors';
import { LabelHttpService } from './label-http.service';

@Controller('api/repos/:owner/:repo/labels')
@UseGuards(InternalApiKeyGuard)
export class LabelHttpController {
  constructor(private readonly labelHttpService: LabelHttpService) {}

  @Get()
  async list(@Param('owner') owner: string, @Param('repo') repo: string) {
    return this.handle(() => this.labelHttpService.listLabels(owner, repo));
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
