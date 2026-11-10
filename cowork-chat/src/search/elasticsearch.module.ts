import {Module as nestModule} from '@nestjs/common';
import {ConfigModule, ConfigService} from '@nestjs/config';
import {
	Client,
	HttpConnection,
	errors,
	type ConnectionRequestOptions,
	type ConnectionRequestOptionsAsStream,
	type ConnectionRequestParams,
	type ConnectionRequestResponse,
	type ConnectionRequestResponseAsStream,
} from '@elastic/elasticsearch';
import {getOptionalConfig, getRequiredConfig} from '../common/config/config.util.js';
import {ElasticsearchService} from './elasticsearch.service.js';
import {ELASTICSEARCH_CLIENT} from './elasticsearch.constants.js';

/**
Elasticsearch client 옵션 이름이다.
*/
const clientOption = {
	connection: 'Connection',
} as const;

/**
요청에 timeout이 없을 때 쓰는 전체 시간 상한이다. client 기본값에는 timeout이 없다.
*/
const DEFAULT_REQUEST_DEADLINE_MS = 30_000;

/**
요청 전체 시간에 상한을 두는 HttpConnection이다.

HttpConnection은 응답 헤더를 받으면 timeout 리스너를 제거한다. Bun 런타임에서는 본문 도중 멈춘 요청이
끝나지 않아 outbox poller가 멈추므로, 헤더 이후 구간까지 포함해 TimeoutError로 끝낸다.

shortcut: 헤더 이후에 멈춘 요청은 abort가 닿지 않아 socket이 agent에 남는다. 이 멈춤이 반복되면 Bun용 연결 구현을 따로 둔다.
*/
class DeadlineHttpConnection extends HttpConnection {
	override async request(parameters: ConnectionRequestParams, options: ConnectionRequestOptions): Promise<ConnectionRequestResponse>;
	override async request(parameters: ConnectionRequestParams, options: ConnectionRequestOptionsAsStream): Promise<ConnectionRequestResponseAsStream>;
	override async request(
		parameters: ConnectionRequestParams,
		options: ConnectionRequestOptions | ConnectionRequestOptionsAsStream,
	): Promise<ConnectionRequestResponse | ConnectionRequestResponseAsStream> {
		const timeoutMs = options.timeout ?? DEFAULT_REQUEST_DEADLINE_MS;
		const controller = new AbortController();
		const signal = options.signal === undefined ? controller.signal : AbortSignal.any([options.signal, controller.signal]);
		let timer: ReturnType<typeof setTimeout> | undefined;
		const deadline = new Promise<never>((_resolve, reject) => {
			timer = setTimeout(() => {
				controller.abort();
				reject(new errors.TimeoutError(`Request timed out after ${timeoutMs}ms`));
			}, timeoutMs);
		});

		try {
			return await Promise.race([super.request(parameters, {...options, signal}), deadline]);
		} finally {
			clearTimeout(timer);
		}
	}
}

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
				return new Client({node: url.href, [clientOption.connection]: DeadlineHttpConnection});
			},
		},
		ElasticsearchService,
	],
	exports: [ElasticsearchService],
})
export class ElasticsearchModule {}
