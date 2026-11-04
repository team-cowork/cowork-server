import {HttpModule} from '@nestjs/axios';
import {Module as nestModule} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service.js';
import {GithubAuthModule} from '../auth/github-auth.module.js';
import {LabelHttpApiClient} from './client/label-http-api.client.js';
import {LabelHttpService} from './label-http.service.js';

@nestModule({
	imports: [HttpModule, GithubAuthModule],
	providers: [
		AppConfigService,
		LabelHttpApiClient,
		LabelHttpService,
	],
	exports: [LabelHttpService],
})
export class LabelHttpModule {}
