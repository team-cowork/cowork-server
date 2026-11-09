import {Module as nestModule} from '@nestjs/common';
import {ConfigModule, ConfigService} from '@nestjs/config';
import {Client, HttpConnection} from '@elastic/elasticsearch';
import {getOptionalConfig, getRequiredConfig} from '../common/config/config.util.js';
import {ElasticsearchService} from './elasticsearch.service.js';
import {ELASTICSEARCH_CLIENT} from './elasticsearch.constants.js';

/**
Elasticsearch client 옵션 이름이다.
*/
const clientOption = {
	connection: 'Connection',
} as const;

@nestModule({
	imports: [ConfigModule],
	providers: [
		{
			provide: ELASTICSEARCH_CLIENT,
			inject: [ConfigService],
			useFactory(configService: ConfigService) {
				const url = new URL(getRequiredConfig(configService, 'ELASTICSEARCH_URL'));
				const endpoint = getOptionalConfig(configService, 'ELASTICSEARCH_ENDPOINT');
				if (endpoint !== undefined) {
					const origin = new URL(endpoint);
					if (!['http:', 'https:'].includes(origin.protocol) || origin.username !== '' || origin.password !== ''
						|| origin.pathname !== '/' || origin.search !== '' || origin.hash !== '') {
						throw new Error('ELASTICSEARCH_ENDPOINT must be an HTTP(S) origin without credentials');
					}

					url.protocol = origin.protocol;
					url.hostname = origin.hostname;
					url.port = origin.port;
				}

				// 기본 UndiciConnection은 Bun 런타임에서 응답 헤더를 읽지 못해 모든 요청이 실패한다.
				return new Client({node: url.href, [clientOption.connection]: HttpConnection});
			},
		},
		ElasticsearchService,
	],
	exports: [ElasticsearchService],
})
export class ElasticsearchModule {}
