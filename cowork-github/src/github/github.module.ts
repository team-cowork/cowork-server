import { HttpModule } from '@nestjs/axios';
import { Module } from '@nestjs/common';
import { AppConfigService } from '../config/app-config.service';
import { GithubAuthModule } from './auth/github-auth.module';
import { GithubApiClient } from './client/github-api.client';
import { IssueService } from './issue/issue.service';
import { LabelService } from './issue/label.service';
import { GithubController } from './github.controller';
import { IssueResultProducer } from './kafka/issue-result.producer';
import { PullRequestModule } from './pull-request/pull-request.module';

@Module({
  imports: [HttpModule, GithubAuthModule, PullRequestModule],
  providers: [
    AppConfigService,
    GithubApiClient,
    LabelService,
    IssueService,
    IssueResultProducer,
  ],
  controllers: [GithubController],
})
export class GithubModule {}
