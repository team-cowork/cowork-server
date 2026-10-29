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
import {GlobalExceptionFilter} from './common/filter/global-exception.filter.js';
import {RedisIoAdapter, SocketIoRedisConnection} from './common/adapter/redis-io.adapter.js';
import {
	PROJECTION_STREAMS,
	type ProjectionName,
	ProjectionReadinessService,
} from './common/kafka/projection-readiness.service.js';

const CHANNEL_PERMISSION_STREAMS: ProjectionName[] = ['channel', 'teamMember', 'teamRole', 'channelRolePolicy', 'dmParticipant'];

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
	new Gauge({
		name: 'cowork_nodejs_heap_size_limit_bytes',
		help: 'V8 heap size limit in bytes.',
	}).set(getHeapStatistics().heap_size_limit);
	// Consul metrics metadata, AuthGuard, projection 미들웨어가 접두사 없는 /metrics를 사용한다.
	app.setGlobalPrefix('chat', {exclude: ['health', 'health/ready', 'metrics']});
	app.useGlobalPipes(new ValidationPipe({whitelist: true, transform: true}));
	app.useGlobalFilters(new GlobalExceptionFilter());
	app.useStaticAssets(join(import.meta.dirname, '..', 'public'));

	const socketIoRedis = app.get(SocketIoRedisConnection);
	app.useWebSocketAdapter(new RedisIoAdapter(app, socketIoRedis));

	const config = new DocumentBuilder()
		.setTitle('cowork Chat API')
		.setDescription(
			'채팅 서비스 — Socket.io 기반 실시간 채팅\n\n'
			+ '## WebSocket (/chat namespace)\n'
			+ '| 이벤트 | 방향 | 설명 |\n'
			+ '|--------|------|------|\n'
			+ '| `join` | C→S | 채널 room 참가 |\n'
			+ '| `leave` | C→S | 채널 room 퇴장 |\n'
			+ '| `join:team` / `leave:team` | C→S | 팀 room 참가/퇴장 |\n'
			+ '| `typing:start` / `typing:stop` | C→S | 타이핑 상태 변경 |\n'
			+ '| `message` | S→C | 새 메시지 수신 |\n'
			+ '| `message:*` | S→C | 수정·삭제·고정·반응 변경 |\n'
			+ '| `member:*` / `channel:*` / `project:*` | S→C | 멤버십·채널·프로젝트 변경 |\n\n'
			+ '메시지 작성은 REST 요청 후 `chat.message.v2` Kafka 이벤트로 처리합니다. 전체 이벤트 명세: `/api/chat/asyncapi.json`\n\n'
			+ '## 메시지 검색\n'
			+ '`GET /api/chat/chat/projects/:projectId/messages/search` — Elasticsearch 기반 프로젝트 채팅 검색.\n'
			+ '프로젝트 멤버이고 채널 접근 권한이 있는 메시지만 반환됩니다.\n\n'
			+ '## 파일 목록\n'
			+ '`GET /api/chat/chat/channels/:channelId/files` — `FILE_SHARE` 채널 전용 파일 목록 조회.\n'
			+ '응답은 파일 단위로 평탄화되며 업로더 표시명과 업로드 시각을 포함합니다.\n\n'
			+ '## 통합 검색 (GraphQL)\n'
			+ '`POST /api/chat/graphql` — 메시지(Elasticsearch)와 Kafka 동기화 채널 projection을 단일 요청으로 병렬 검색.\n\n'
			+ '```graphql\n'
			+ 'query {\n'
			+ '  unifiedSearch(teamId: 1, q: "배포") {\n'
			+ '    messages { messageId channelId content highlight createdAt }\n'
			+ '    messageNextCursor\n'
			+ '    channels { id name type isPrivate }\n'
			+ '  }\n'
			+ '}\n'
			+ '```\n\n'
			+ '선택 인자: `channelId`, `authorId`, `type`, `hasFile`, `before`, `limit`\n\n'
			+ 'GraphQL Playground(로컬 환경): `/api/chat/graphql` (GET)\n\n'
			+ '## 인증\n'
			+ 'REST API: Gateway에서 주입된 `X-User-Id`, `X-User-Role` 헤더 사용.\n'
			+ 'WebSocket: Gateway가 `/ws/chat`에서 JWT를 검증하고 같은 사용자 헤더를 주입합니다.\n'
			+ 'Gateway 헤더가 없는 로컬 개발 연결에만 `auth.token` fallback을 사용합니다.\n'
			+ '```js\n'
			+ 'io(url, { auth: { token: "<JWT>" } })\n'
			+ '```\n'
			+ '인증 실패 시 `connect_error`가 전달됩니다.',
		)
		.setVersion('20261029.0')
		.addServer('/api/chat', 'Gateway')
		.addBearerAuth(
			{
				type: 'http',
				scheme: 'bearer',
				bearerFormat: 'JWT',
				description: 'Gateway에서 검증할 Cowork JWT',
			},
			'bearer',
		)
		.addSecurityRequirements('bearer')
		.build();

	const document = SwaggerModule.createDocument(app, config);
	for (const path of ['/health', '/health/ready']) {
		const operation = document.paths[path]?.get;
		if (operation) {
			operation.security = [];
		}
	}

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
