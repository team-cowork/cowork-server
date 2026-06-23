import { HttpModule } from '@nestjs/axios';
import { Module } from '@nestjs/common';
import { AppConfigService } from '../../config/app-config.service';
import { GithubAuthModule } from '../auth/github-auth.module';
import { PullRequestApiClient } from './client/pull-request-api.client';
import { InternalApiKeyGuard } from './guards/internal-api-key.guard';
import { PullRequestResultProducer } from './kafka/pull-request-result.producer';
import { PullRequestController } from './pull-request.controller';
import { PullRequestHttpController } from './pull-request-http.controller';
import { PullRequestListHttpController } from './pull-request-list-http.controller';
import { PullRequestService } from './pull-request.service';

@Module({
  imports: [HttpModule, GithubAuthModule],
  providers: [
    AppConfigService,
    PullRequestApiClient,
    PullRequestService,
    PullRequestResultProducer,
    InternalApiKeyGuard,
  ],
  controllers: [
    PullRequestController,
    PullRequestHttpController,
    PullRequestListHttpController,
  ],
})
export class PullRequestModule {}
