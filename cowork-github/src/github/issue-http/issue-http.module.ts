import {HttpModule} from '@nestjs/axios';
import {Module as nestModule} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service.js';
import {GithubAuthModule} from '../auth/github-auth.module.js';
import {IssueHttpApiClient} from './client/issue-http-api.client.js';
import {IssueHttpService} from './issue-http.service.js';

@nestModule({
	imports: [HttpModule, GithubAuthModule],
	providers: [
		AppConfigService,
		IssueHttpApiClient,
		IssueHttpService,
	],
	exports: [IssueHttpService],
})
export class IssueHttpModule {}
