import { HttpModule } from '@nestjs/axios';
import { Module } from '@nestjs/common';
import { AppConfigService } from '../../config/app-config.service';
import { GithubAuthModule } from '../auth/github-auth.module';
import { InternalApiKeyGuard } from '../common/guards/internal-api-key.guard';
import { IssueHttpApiClient } from './client/issue-http-api.client';
import { CommentHttpController } from './comment-http.controller';
import { CommentListHttpController } from './comment-list-http.controller';
import { IssueHttpController } from './issue-http.controller';
import { IssueHttpService } from './issue-http.service';

@Module({
  imports: [HttpModule, GithubAuthModule],
  providers: [
    AppConfigService,
    IssueHttpApiClient,
    IssueHttpService,
    InternalApiKeyGuard,
  ],
  controllers: [
    IssueHttpController,
    CommentListHttpController,
    CommentHttpController,
  ],
})
export class IssueHttpModule {}
