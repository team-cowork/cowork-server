import {HttpModule} from '@nestjs/axios';
import {Module as nestModule} from '@nestjs/common';
import {AppConfigService} from '../config/app-config.service.js';
import {GithubAuthModule} from './auth/github-auth.module.js';
import {GithubApiClient} from './client/github-api.client.js';
import {IssueService} from './issue/issue.service.js';
import {LabelService} from './issue/label.service.js';
import {GithubController} from './github.controller.js';
import {IssueResultProducer} from './kafka/issue-result.producer.js';
import {InstallationModule} from './installation/installation.module.js';
import {IssueHttpModule} from './issue-http/issue-http.module.js';
import {IssueWriteModule} from './issue-write/issue-write.module.js';
import {LabelHttpModule} from './label-http/label-http.module.js';
import {PullRequestModule} from './pull-request/pull-request.module.js';

@nestModule({
	imports: [
		HttpModule,
		GithubAuthModule,
		PullRequestModule,
		IssueHttpModule,
		LabelHttpModule,
		IssueWriteModule,
		InstallationModule,
	],
	providers: [
		AppConfigService,
		GithubApiClient,
		LabelService,
		IssueService,
		IssueResultProducer,
	],
	controllers: [GithubController],
})
export class GithubModule {}
