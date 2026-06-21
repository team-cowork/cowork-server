import { HttpModule } from '@nestjs/axios';
import { Module } from '@nestjs/common';
import Redis from 'ioredis';
import { AppConfigService } from '../../config/app-config.service';
import { REDIS_CLIENT } from '../constants';
import { GithubAuthService } from './github-auth.service';

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
  ],
  exports: [GithubAuthService],
})
export class GithubAuthModule {}
