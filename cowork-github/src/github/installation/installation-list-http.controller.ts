import {
  Controller,
  Get,
  HttpException,
  Param,
  UseGuards,
} from '@nestjs/common';
import { GithubClientError } from '../github.errors';
import { InternalApiKeyGuard } from '../pull-request/guards/internal-api-key.guard';
import { InstallationService } from './installation.service';

@Controller('api/orgs/:org/repos')
@UseGuards(InternalApiKeyGuard)
export class InstallationListHttpController {
  constructor(private readonly installationService: InstallationService) {}

  @Get()
  async list(@Param('org') org: string) {
    try {
      return await this.installationService.listOrgRepos(org);
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
