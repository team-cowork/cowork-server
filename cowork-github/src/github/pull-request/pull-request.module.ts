import {HttpModule} from '@nestjs/axios';
import {Module as nestModule} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service.js';
import {GithubAuthModule} from '../auth/github-auth.module.js';
import {InternalApiKeyGuard} from '../common/guards/internal-api-key.guard.js';
import {PullRequestApiClient} from './client/pull-request-api.client.js';
import {PullRequestResultProducer} from './kafka/pull-request-result.producer.js';
import {PullRequestController} from './pull-request.controller.js';
import {PullRequestHttpController} from './pull-request-http.controller.js';
import {PullRequestService} from './pull-request.service.js';

@nestModule({
	imports: [HttpModule, GithubAuthModule],
	providers: [
		AppConfigService,
		PullRequestApiClient,
		PullRequestService,
		PullRequestResultProducer,
		InternalApiKeyGuard,
	],
	controllers: [
		PullRequestController,
		PullRequestHttpController,
	],
	exports: [PullRequestService],
})
export class PullRequestModule {}
