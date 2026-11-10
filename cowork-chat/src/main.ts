import process from 'node:process';
import {join} from 'node:path';
import {getHeapStatistics} from 'node:v8';
// @nestjs/core가 reflect-metadata를 먼저 로드하므로, 데코레이터가 있는 내부 모듈보다 앞에 둔다.
import {NestFactory} from '@nestjs/core';
import {type NestExpressApplication} from '@nestjs/platform-express';
import {Logger, ValidationPipe} from '@nestjs/common';
import {DocumentBuilder, SwaggerModule} from '@nestjs/swagger';
import {type NextFunction, type Request, type Response} from 'express';
import {Logger as PinoLogger} from 'nestjs-pino';
import {Gauge} from 'prom-client';
import {createChatApiDocument} from './common/config/swagger.js';
import {GlobalExceptionFilter} from './common/filter/global-exception.filter.js';
import {RedisIoAdapter, SocketIoRedisConnection} from './common/adapter/redis-io.adapter.js';
import {
	CHANNEL_PERMISSION_STREAMS,
	PROJECTION_STREAMS,
	type ProjectionName,
	ProjectionReadinessService,
} from './common/kafka/projection-readiness.service.js';

const ALL_PROJECTION_STREAMS = Object.keys(PROJECTION_STREAMS) as ProjectionName[];

function requiredProjectionStreams(path: string): ProjectionName[] {
	if (path.startsWith('/chat/webhooks/')) {
		return ['webhook', 'channel', 'teamMember', 'teamRole', 'channelRolePolicy'];
	}

	if (path.startsWith('/chat/block') || path.startsWith('/chat/admin/')) {
		return [];
	}

	if (path.startsWith('/chat/dms')) {
		return ['channel', 'dmParticipant', 'userProfile'];
	}

	if (path.startsWith('/chat/projects/')) {
		return [...CHANNEL_PERMISSION_STREAMS, 'project', 'projectPermission', 'userProfile'];
	}

	if (path.startsWith('/chat/teams/')) {
		return CHANNEL_PERMISSION_STREAMS;
	}

	if (path.startsWith('/chat/search')) {
		return [...CHANNEL_PERMISSION_STREAMS, 'userProfile'];
	}

	if (path === '/graphql') {
		return [...CHANNEL_PERMISSION_STREAMS, 'userProfile'];
	}

	if (path.includes('/github/issues')) {
		return [...CHANNEL_PERMISSION_STREAMS, 'project', 'projectPermission', 'projectGithubRepo', 'userProfile'];
	}

	return path.startsWith('/chat/channels/') ? [...CHANNEL_PERMISSION_STREAMS, 'userProfile'] : ALL_PROJECTION_STREAMS;
}

function debugStartup(message: string) {
	if (process.env.DEBUG_STARTUP === 'true') {
		new Logger('Startup').log(message);
	}
}

async function bootstrap() {
	debugStartup('creating Nest application');
	const appModule = await import('./app.module.js');
	const app = await NestFactory.create<NestExpressApplication>(appModule.AppModule, {bufferLogs: true});
	debugStartup('Nest application created');
	app.useLogger(app.get(PinoLogger));
	// 힙 알림은 V8 최대치와 비교한다. nodejs_heap_size_total_bytes는 현재 확보된 힙일 뿐이다.
	// bun(JavaScriptCore)에서는 이 값이 고정 한도가 아니라 힙이 커지면 같이 늘어나므로, 알림 분모로
	// 쓰면 오탐·미탐이 생긴다. bun에서는 coworkChatHighMemory(RSS 기준) 알림이 대신한다.
	if (process.versions.bun === undefined) {
		new Gauge({
			name: 'cowork_nodejs_heap_size_limit_bytes',
			help: 'V8 heap size limit in bytes.',
		}).set(getHeapStatistics().heap_size_limit);
	} else {
		// Nodejs_heap_size_total_bytes는 bun에서 의미 없는 값을 반환한다. bun:jsc의 heapStats()가
		// 돌려주는 JavaScriptCore 힙 크기를 대신 내보낸다.
		const {heapStats} = await import('bun:jsc');
		const bunHeapSizeGauge = new Gauge({
			name: 'cowork_bun_heap_size_bytes',
			help: 'JavaScriptCore heap size in bytes.',
			collect() {
				this.set(heapStats().heapSize);
			},
		});
	}

	// Consul metrics metadata, AuthGuard, projection 미들웨어가 접두사 없는 /metrics를 사용한다.
	app.setGlobalPrefix('chat', {exclude: ['health', 'health/ready', 'metrics']});
	app.useGlobalPipes(new ValidationPipe({whitelist: true, transform: true}));
	app.useGlobalFilters(new GlobalExceptionFilter());
	app.useStaticAssets(join(import.meta.dirname, '..', 'public'));

	const socketIoRedis = app.get(SocketIoRedisConnection);
	app.useWebSocketAdapter(new RedisIoAdapter(app, socketIoRedis));

	const document = createChatApiDocument(app, new DocumentBuilder().setVersion('20261110.1'));

	SwaggerModule.setup('api', app, document, {jsonDocumentUrl: 'api-json'});

	const projectionReadiness = app.get(ProjectionReadinessService);
	app.use((request: Request, response: Response, next: NextFunction) => {
		const isOperationalPath = request.path === '/health'
			|| request.path === '/health/ready'
			|| request.path === '/metrics'
			|| request.path === '/api'
			|| request.path === '/api-json'
			|| request.path === '/asyncapi.json'
			|| request.path.startsWith('/chat/admin/');
		const requiredStreams = isOperationalPath ? [] : requiredProjectionStreams(request.path);
		if (!projectionReadiness.areReady(requiredStreams)) {
			const unavailableStreams = requiredStreams.filter(stream => !projectionReadiness.areReady([stream]));
			response.status(503).json({
				statusCode: 503,
				message: 'Required Kafka projections are unavailable',
				unavailableStreams,
			});
			return;
		}

		next();
	});

	const port = Number(process.env.PORT ?? 8087);
	if (!Number.isSafeInteger(port) || port < 1 || port > 65_535) {
		throw new Error('PORT must be an integer between 1 and 65535');
	}

	debugStartup(`listening on ${port}`);
	await app.listen(port);
	debugStartup('HTTP server listening');

	let isShuttingDown = false;
	const shouldStartShutdown = (): boolean => {
		if (isShuttingDown) {
			return false;
		}

		isShuttingDown = true;
		return true;
	};

	projectionReadiness.onFatalInvariantViolation(reason => {
		if (!shouldStartShutdown()) {
			return;
		}

		new Logger('ProjectionReadiness').error(`fatal projection invariant violation: ${reason}`);
		void (async () => {
			await app.close();
			// 처리되지 않은 rejection으로 남겨 남은 연결과 관계없이 종료 코드 1로 프로세스를 끝낸다.
			throw new Error(`fatal projection invariant violation: ${reason}`);
		})();
	});

	for (const signal of ['SIGINT', 'SIGTERM'] as const) {
		process.once(signal, () => {
			if (!shouldStartShutdown()) {
				return;
			}

			void (async () => {
				await app.close();
				process.exit(0);
			})();
		});
	}

	new Logger('Bootstrap').log(`Chat server running on port ${port}`);
	new Logger('Bootstrap').log(`Swagger UI: ${await app.getUrl()}/api`);
}

try {
	await bootstrap();
} catch (error) {
	new Logger('Bootstrap').error('Application failed to start', error instanceof Error ? error.stack : String(error));
	// 다시 던져 이미 열린 연결이 있어도 종료 코드 1로 프로세스를 끝낸다.
	throw error;
}
