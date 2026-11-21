import {HttpModule} from '@nestjs/axios';
import {Module as nestModule} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service.js';
import {KafkaProducerModule} from '../../kafka/kafka-producer.module.js';
import {GithubAuthModule} from '../auth/github-auth.module.js';
import {GithubGraphqlClient} from '../client/github-graphql.client.js';
import {ProjectDraftHistory} from './item-write/project-draft-history.js';
import {ProjectItemEditorService} from './item-write/project-item-editor.service.js';
import {ProjectItemWriteCommandController} from './item-write/project-item-write-command.controller.js';
import {ProjectItemWriteResultProducer} from './item-write/project-item-write-result.producer.js';
import {ProjectBoardStateProducer} from './kafka/project-board-state.producer.js';
import {ProjectBoardApiService} from './project-board-api.service.js';
import {ProjectBoardSyncService} from './project-board-sync.service.js';
import {ProjectBoardWebhookHandler} from './project-board-webhook.handler.js';

@nestModule({
	imports: [HttpModule, GithubAuthModule, KafkaProducerModule],
	providers: [
		AppConfigService,
		GithubGraphqlClient,
		ProjectBoardApiService,
		ProjectBoardStateProducer,
		ProjectBoardSyncService,
		ProjectBoardWebhookHandler,
		ProjectItemWriteResultProducer,
		ProjectDraftHistory,
		ProjectItemEditorService,
	],
	controllers: [ProjectItemWriteCommandController],
	exports: [ProjectBoardSyncService, ProjectBoardWebhookHandler],
})
export class ProjectBoardModule {}
