import {HttpModule} from '@nestjs/axios';
import {Module as nestModule} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service.js';
import {GithubAuthModule} from '../auth/github-auth.module.js';
import {IssueHttpModule} from '../issue-http/issue-http.module.js';
import {LabelHttpModule} from '../label-http/label-http.module.js';
import {PullRequestModule} from '../pull-request/pull-request.module.js';
import {InstallationApiClient} from './client/installation-api.client.js';
import {GithubBackfillService} from './github-backfill.service.js';
import {GithubSyncCommandController} from './github-sync-command.controller.js';
import {GithubReadModelEventProducers} from './kafka/github-read-model-event-producers.js';
import {IssueCommentStateProducer} from './kafka/issue-comment-state.producer.js';
import {IssueStateProducer} from './kafka/issue-state.producer.js';
import {LabelStateProducer} from './kafka/label-state.producer.js';
import {PullRequestStateProducer} from './kafka/pull-request-state.producer.js';
import {RepoEventProducer} from './kafka/repo-event.producer.js';
import {RepoStateProducer} from './kafka/repo-state.producer.js';
import {TeamGithubProducer} from './kafka/team-github.producer.js';
import {GithubSetupController} from './webhook/github-setup.controller.js';
import {GithubWebhookSignatureGuard} from './webhook/github-webhook-signature.guard.js';
import {GithubWebhookController} from './webhook/github-webhook.controller.js';

@nestModule({
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
