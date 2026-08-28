import 'reflect-metadata';
import 'dotenv/config';
import { NestFactory } from '@nestjs/core';
import { NestExpressApplication } from '@nestjs/platform-express';
import { Logger, ValidationPipe } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import { join } from 'path';
import { NextFunction, Request, Response } from 'express';
import { Logger as PinoLogger } from 'nestjs-pino';
import { EurekaClient } from './eureka/eureka-client';
import { requireEnv } from './common/config/config.util';
import { loadConfigServerEnv } from './common/config/config-server';
import { GlobalExceptionFilter } from './common/filter/global-exception.filter';
import { RedisIoAdapter } from './common/adapter/redis-io.adapter';
import { ProjectionReadinessService } from './common/kafka/projection-readiness.service';

function debugStartup(message: string) {
    if (process.env.DEBUG_STARTUP === 'true') {
        new Logger('Startup').log(message);
    }
}

async function bootstrap() {
    debugStartup('loading config server properties');
    await loadConfigServerEnv();
    debugStartup('config server properties loaded');

    debugStartup('creating Nest application');
    const { AppModule } = await import('./app.module');
    const app = await NestFactory.create<NestExpressApplication>(AppModule, { bufferLogs: true });
    debugStartup('Nest application created');
    app.useLogger(app.get(PinoLogger));
    app.setGlobalPrefix('chat', { exclude: ['health', 'health/ready'] });
    app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
    app.useGlobalFilters(new GlobalExceptionFilter());
    app.useStaticAssets(join(__dirname, '..', 'public'));

    debugStartup('connecting Socket.IO Redis adapter');
    const redisIoAdapter = new RedisIoAdapter(app);
    await redisIoAdapter.connectToRedis(app.get(ConfigService));
    app.useWebSocketAdapter(redisIoAdapter);
    debugStartup('Socket.IO Redis adapter connected');

    const config = new DocumentBuilder()
        .setTitle('Cowork Chat API')
        .setDescription(
            '채팅 서비스 — Socket.io 기반 실시간 채팅\n\n' +
            '## WebSocket (/chat namespace)\n' +
            '| 이벤트 | 방향 | 설명 |\n' +
            '|--------|------|------|\n' +
            '| `join` | C→S | 채널 room 참가 |\n' +
            '| `leave` | C→S | 채널 room 퇴장 |\n' +
            '| `join:team` / `leave:team` | C→S | 팀 room 참가/퇴장 |\n' +
            '| `typing:start` / `typing:stop` | C→S | 타이핑 상태 변경 |\n' +
            '| `message` | S→C | 새 메시지 수신 |\n' +
            '| `message:*` | S→C | 수정·삭제·고정·반응 변경 |\n' +
            '| `member:*` / `channel:*` / `project:*` | S→C | 멤버십·채널·프로젝트 변경 |\n\n' +
            '메시지 작성은 REST 요청 후 `chat.message` Kafka 이벤트로 처리합니다. 전체 이벤트 명세: `/api/chat/asyncapi.json`\n\n' +
            '## 메시지 검색\n' +
            '`GET /api/chat/chat/projects/:projectId/messages/search` — Elasticsearch 기반 프로젝트 채팅 검색.\n' +
            '프로젝트 멤버이고 채널 접근 권한이 있는 메시지만 반환됩니다.\n\n' +
            '## 파일 목록\n' +
            '`GET /api/chat/chat/channels/:channelId/files` — `FILE_SHARE` 채널 전용 파일 목록 조회.\n' +
            '응답은 파일 단위로 평탄화되며 업로더 표시명과 업로드 시각을 포함합니다.\n\n' +
            '## 통합 검색 (GraphQL)\n' +
            '`POST /api/chat/graphql` — 메시지(Elasticsearch)와 Kafka 동기화 채널 projection을 단일 요청으로 병렬 검색.\n\n' +
            '```graphql\n' +
            'query {\n' +
            '  unifiedSearch(teamId: 1, q: "배포") {\n' +
            '    messages { messageId channelId content highlight createdAt }\n' +
            '    messageNextCursor\n' +
            '    channels { id name type isPrivate }\n' +
            '  }\n' +
            '}\n' +
            '```\n\n' +
            '선택 인자: `channelId`, `authorId`, `type`, `hasFile`, `before`, `limit`\n\n' +
            'GraphQL Playground(로컬 환경): `/api/chat/graphql` (GET)\n\n' +
            '## 인증\n' +
            'REST API: Gateway에서 주입된 `X-User-Id`, `X-User-Role` 헤더 사용.\n' +
            'WebSocket: Gateway가 `/ws/chat`에서 JWT를 검증하고 같은 사용자 헤더를 주입합니다.\n' +
            'Gateway 헤더가 없는 로컬 개발 연결에만 `auth.token` fallback을 사용합니다.\n' +
            '```js\n' +
            'io(url, { auth: { token: "<JWT>" } })\n' +
            '```\n' +
            '인증 실패 시 서버는 `exception` 이벤트를 emit한 후 연결을 끊습니다.',
        )
        .setVersion('20260820.0')
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
    SwaggerModule.setup('api', app, document, { jsonDocumentUrl: 'api-json' });

    const projectionReadiness = app.get(ProjectionReadinessService);
    app.use((req: Request, res: Response, next: NextFunction) => {
        const operationalPath = req.path === '/health'
            || req.path === '/health/ready'
            || req.path === '/metrics'
            || req.path === '/api'
            || req.path === '/api-json'
            || req.path === '/asyncapi.json';
        if (!operationalPath && !projectionReadiness.isReady()) {
            res.status(503).json({ statusCode: 503, message: 'Kafka projections are synchronizing' });
            return;
        }
        next();
    });

    const port = Number(requireEnv('PORT'));
    debugStartup(`listening on ${port}`);
    await app.listen(port);
    debugStartup('HTTP server listening');

    const eureka = EurekaClient.fromEnv(port);
    let shuttingDown = false;
    let eurekaRegistered = false;
    let eurekaRegistration: Promise<void> | undefined;

    void projectionReadiness.whenReady().then(() => {
        if (shuttingDown) return;
        debugStartup('projection replay complete; registering with Eureka');
        eurekaRegistration = (async () => {
            while (!shuttingDown && !eurekaRegistered) {
                try {
                    await eureka.register();
                    eurekaRegistered = true;
                    debugStartup('Eureka registration completed');
                    return;
                } catch (err: unknown) {
                    new Logger('Eureka').warn(
                        `registration failed; retrying: ${err instanceof Error ? err.message : String(err)}`,
                    );
                    await new Promise((resolve) => setTimeout(resolve, 1_000));
                }
            }
        })()
            .then(() => {
                if (shuttingDown) debugStartup('Eureka registration stopped during shutdown');
            });
        return eurekaRegistration;
    });

    projectionReadiness.onFatalInvariantViolation((reason) => {
        if (shuttingDown) return;
        shuttingDown = true;
        new Logger('ProjectionReadiness').error(`fatal projection invariant violation: ${reason}`);
        void (async () => {
            await eurekaRegistration;
            if (eurekaRegistered) {
                await eureka.deregister().catch(() => undefined);
            }
            await app.close();
            process.exit(1);
        })();
    });

    const shutdown = async () => {
        shuttingDown = true;
        await eurekaRegistration;
        if (eurekaRegistered) {
            await eureka.deregister().catch((err: unknown) => {
                new Logger('Eureka').warn(`deregister failed: ${err instanceof Error ? err.message : String(err)}`);
            });
        }
        await app.close();
        process.exit(0);
    };
    process.once('SIGINT', () => void shutdown());
    process.once('SIGTERM', () => void shutdown());

    new Logger('Bootstrap').log(`Chat server running on port ${port}`);
    new Logger('Bootstrap').log(`Swagger UI: ${await app.getUrl()}/api`);
}

bootstrap().catch((err: unknown) => {
    new Logger('Bootstrap').error('Application failed to start', err instanceof Error ? err.stack : String(err));
    process.exit(1);
});
