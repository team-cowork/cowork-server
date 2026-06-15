import 'reflect-metadata';
import 'dotenv/config';
import { NestFactory } from '@nestjs/core';
import { NestExpressApplication } from '@nestjs/platform-express';
import { Logger, ValidationPipe } from '@nestjs/common';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import { join } from 'path';
import { Logger as PinoLogger } from 'nestjs-pino';
import { EurekaClient } from './eureka/eureka-client';
import { requireEnv } from './common/config/config.util';
import { loadConfigServerEnv } from './common/config/config-server';

function debugStartup(message: string) {
    if (process.env.DEBUG_STARTUP === 'true') {
        console.log(`[startup] ${message}`);
    }
}

async function bootstrap() {
    debugStartup('loading config server properties');
    await loadConfigServerEnv();
    debugStartup('config server properties loaded');

    debugStartup('creating Nest application');
    const { ChatModule } = await import('./chat/chat.module');
    const app = await NestFactory.create<NestExpressApplication>(ChatModule, { bufferLogs: true });
    debugStartup('Nest application created');
    app.useLogger(app.get(PinoLogger));
    app.setGlobalPrefix('chat', { exclude: ['health'] });
    app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
    app.useStaticAssets(join(__dirname, '..', 'public'));

    const config = new DocumentBuilder()
        .setTitle('Cowork Chat API')
        .setDescription(
            '채팅 서비스 — Socket.io 기반 실시간 채팅\n\n' +
            '## WebSocket (/chat namespace)\n' +
            '| 이벤트 | 방향 | 설명 |\n' +
            '|--------|------|------|\n' +
            '| `join` | C→S | 채널 room 참가 |\n' +
            '| `leave` | C→S | 채널 room 퇴장 |\n' +
            '| `message` | C→S | 메시지 전송 (Kafka 비동기) |\n' +
            '| `message` | S→C | 새 메시지 수신 |\n' +
            '| `message:edited` | S→C | 메시지 수정됨 |\n' +
            '| `message:deleted` | S→C | 메시지 삭제됨 |\n\n' +
            '## 메시지 검색\n' +
            '`GET /projects/:projectId/messages/search` — Elasticsearch 기반 프로젝트 채팅 검색.\n' +
            '프로젝트 멤버이고 채널 접근 권한이 있는 메시지만 반환됩니다.\n\n' +
            '## 파일 목록\n' +
            '`GET /channels/:channelId/files` — `FILE_SHARE` 채널 전용 파일 목록 조회.\n' +
            '응답은 파일 단위로 평탄화되며 업로더 표시명과 업로드 시각을 포함합니다.\n\n' +
            '## 인증\n' +
            'REST API: Gateway에서 주입된 `X-User-Id`, `X-User-Role` 헤더 사용.\n' +
            'WebSocket: Socket.io handshake의 `auth.token`에 JWT Bearer 토큰 전달.\n' +
            '```js\n' +
            'io(url, { auth: { token: "<JWT>" } })\n' +
            '```\n' +
            '인증 실패 시 서버는 `exception` 이벤트를 emit한 후 연결을 끊습니다.',
        )
        .setVersion('1.0')
        .build();

    const document = SwaggerModule.createDocument(app, config);
    SwaggerModule.setup('api', app, document, { jsonDocumentUrl: 'api-json' });

    const port = Number(requireEnv('PORT'));
    debugStartup(`listening on ${port}`);
    await app.listen(port);
    debugStartup('HTTP server listening');

    const eureka = EurekaClient.fromEnv(port);
    debugStartup('registering with Eureka');
    await eureka.register().catch((err: unknown) => {
        new Logger('Eureka').warn(`registration failed: ${err instanceof Error ? err.message : String(err)}`);
    });
    debugStartup('Eureka registration attempted');

    const shutdown = async () => {
        await eureka.deregister().catch((err: unknown) => {
            new Logger('Eureka').warn(`deregister failed: ${err instanceof Error ? err.message : String(err)}`);
        });
        await app.close();
        process.exit(0);
    };
    process.once('SIGINT', shutdown);
    process.once('SIGTERM', shutdown);

    new Logger('Bootstrap').log(`Chat server running on port ${port}`);
    new Logger('Bootstrap').log(`Swagger UI: ${await app.getUrl()}/api`);
}

bootstrap().catch((err) => {
    console.error('Application failed to start', err);
    process.exit(1);
});
