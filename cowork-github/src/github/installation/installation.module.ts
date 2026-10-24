import {HttpModule} from '@nestjs/axios';
import {Module} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service';
import {GithubAuthModule} from '../auth/github-auth.module';
import {IssueHttpModule} from '../issue-http/issue-http.module';
import {LabelHttpModule} from '../label-http/label-http.module';
import {PullRequestModule} from '../pull-request/pull-request.module';
import {InstallationApiClient} from './client/installation-api.client';
import {GithubBackfillService} from './github-backfill.service';
import {GithubSyncCommandController} from './github-sync-command.controller';
import {GithubReadModelEventProducers} from './kafka/github-read-model-event-producers';
import {IssueCommentStateProducer} from './kafka/issue-comment-state.producer';
import {IssueStateProducer} from './kafka/issue-state.producer';
import {LabelStateProducer} from './kafka/label-state.producer';
import {PullRequestStateProducer} from './kafka/pull-request-state.producer';
import {RepoEventProducer} from './kafka/repo-event.producer';
import {RepoStateProducer} from './kafka/repo-state.producer';
import {TeamGithubProducer} from './kafka/team-github.producer';
import {GithubSetupController} from './webhook/github-setup.controller';
import {GithubWebhookSignatureGuard} from './webhook/github-webhook-signature.guard';
import {GithubWebhookController} from './webhook/github-webhook.controller';

@Module({
    imports: [
        HttpModule,
        GithubAuthModule,
        IssueHttpModule,
        PullRequestModule,
        LabelHttpModule,
    ],
    providers: [
        AppConfigService,
        InstallationApiClient,
        TeamGithubProducer,
        RepoEventProducer,
        RepoStateProducer,
        IssueStateProducer,
        PullRequestStateProducer,
        LabelStateProducer,
        IssueCommentStateProducer,
        GithubReadModelEventProducers,
        GithubBackfillService,
        GithubWebhookSignatureGuard,
    ],
    controllers: [
        GithubWebhookController,
        GithubSetupController,
        GithubSyncCommandController,
    ],
})
export class InstallationModule {}
