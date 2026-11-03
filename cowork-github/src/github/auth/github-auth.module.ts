import {HttpModule} from '@nestjs/axios';
import {Module as nestModule} from '@nestjs/common';
import {Redis} from 'ioredis';
import {AppConfigService} from '../../config/app-config.service.js';
import {REDIS_CLIENT} from '../constants.js';
import {GithubAuthService} from './github-auth.service.js';

@nestModule({
	imports: [HttpModule],
	providers: [
		AppConfigService,
		{
			provide: REDIS_CLIENT,
			useFactory: (config: AppConfigService) =>
				new Redis({
					host: config.redisHost,
					port: config.redisPort,
					lazyConnect: true,
				}),
			inject: [AppConfigService],
		},
		GithubAuthService,
	],
	exports: [GithubAuthService, REDIS_CLIENT],
})
export class GithubAuthModule {}
