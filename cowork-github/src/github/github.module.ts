import { HttpModule } from '@nestjs/axios';
import { Module } from '@nestjs/common';
import Redis from 'ioredis';
import { AppConfigService } from '../config/app-config.service';
import { GithubAuthService } from './auth/github-auth.service';
import { GithubApiClient } from './client/github-api.client';
import { REDIS_CLIENT } from './constants';
import { IssueService } from './issue/issue.service';
import { LabelService } from './issue/label.service';
import { GithubController } from './github.controller';
import { IssueResultProducer } from './kafka/issue-result.producer';

@Module({
  imports: [HttpModule],
  providers: [
    AppConfigService,
    {
      provide: REDIS_CLIENT,
      useFactory: (config: AppConfigService) =>
        new Redis({
          host: config.redisHost,
          port: config.redisPort,
          lazyConnect: true,
        }),
      inject: [AppConfigService],
    },
    GithubAuthService,
    GithubApiClient,
    LabelService,
    IssueService,
    IssueResultProducer,
  ],
  controllers: [GithubController],
})
export class GithubModule {}
