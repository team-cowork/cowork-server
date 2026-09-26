import { HttpModule } from '@nestjs/axios';
import { Module } from '@nestjs/common';
import { AppConfigService } from '../../config/app-config.service';
import { GithubAuthModule } from '../auth/github-auth.module';
import { InternalApiKeyGuard } from '../common/guards/internal-api-key.guard';
import { LabelHttpApiClient } from './client/label-http-api.client';
import { LabelHttpController } from './label-http.controller';
import { LabelHttpService } from './label-http.service';

@Module({
  imports: [HttpModule, GithubAuthModule],
  providers: [
    AppConfigService,
    LabelHttpApiClient,
    LabelHttpService,
    InternalApiKeyGuard,
  ],
  controllers: [LabelHttpController],
  exports: [LabelHttpService],
})
export class LabelHttpModule {}
