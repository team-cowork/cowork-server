import {HttpModule} from '@nestjs/axios';
import {Module as nestModule} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service.js';
import {GithubAuthModule} from '../auth/github-auth.module.js';
import {InternalApiKeyGuard} from '../common/guards/internal-api-key.guard.js';
import {PullRequestApiClient} from './client/pull-request-api.client.js';
import {PullRequestHttpController} from './pull-request-http.controller.js';
import {PullRequestService} from './pull-request.service.js';

@nestModule({
	imports: [HttpModule, GithubAuthModule],
	providers: [
		AppConfigService,
		PullRequestApiClient,
		PullRequestService,
		InternalApiKeyGuard,
	],
	controllers: [PullRequestHttpController],
	exports: [PullRequestService],
})
export class PullRequestModule {}
