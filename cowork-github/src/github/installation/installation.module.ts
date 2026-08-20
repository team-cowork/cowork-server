import { HttpModule } from '@nestjs/axios';
import { Module } from '@nestjs/common';
import { AppConfigService } from '../../config/app-config.service';
import { GithubAuthModule } from '../auth/github-auth.module';
import { InstallationApiClient } from './client/installation-api.client';
import { InstallationListHttpController } from './installation-list-http.controller';
import { InstallationService } from './installation.service';
import { RepoEventProducer } from './kafka/repo-event.producer';
import { TeamGithubProducer } from './kafka/team-github.producer';
import { GithubSetupController } from './webhook/github-setup.controller';
import { GithubWebhookSignatureGuard } from './webhook/github-webhook-signature.guard';
import { GithubWebhookController } from './webhook/github-webhook.controller';

@Module({
  imports: [HttpModule, GithubAuthModule],
  providers: [
    AppConfigService,
    InstallationApiClient,
    InstallationService,
    TeamGithubProducer,
    RepoEventProducer,
    GithubWebhookSignatureGuard,
  ],
  controllers: [
    InstallationListHttpController,
    GithubWebhookController,
    GithubSetupController,
  ],
})
export class InstallationModule {}
