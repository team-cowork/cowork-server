import {HttpModule} from '@nestjs/axios';
import {Module as nestModule} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service.js';
import {GithubAuthModule} from '../auth/github-auth.module.js';
import {GithubGraphqlClient} from '../client/github-graphql.client.js';
import {ProjectBoardStateProducer} from './kafka/project-board-state.producer.js';
import {ProjectBoardApiService} from './project-board-api.service.js';
import {ProjectBoardSyncService} from './project-board-sync.service.js';
import {ProjectBoardWebhookHandler} from './project-board-webhook.handler.js';

@nestModule({
	imports: [HttpModule, GithubAuthModule],
	providers: [
		AppConfigService,
		GithubGraphqlClient,
		ProjectBoardApiService,
		ProjectBoardStateProducer,
		ProjectBoardSyncService,
		ProjectBoardWebhookHandler,
	],
	exports: [ProjectBoardSyncService, ProjectBoardWebhookHandler],
})
export class ProjectBoardModule {}
