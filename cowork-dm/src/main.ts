import 'reflect-metadata';
import 'dotenv/config';
import { NestFactory } from '@nestjs/core';
import { NestExpressApplication } from '@nestjs/platform-express';
import { Logger, ValidationPipe } from '@nestjs/common';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
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
    const { DmModule } = await import('./dm.module');
    const app = await NestFactory.create<NestExpressApplication>(DmModule, { bufferLogs: true });
    debugStartup('Nest application created');
    app.useLogger(app.get(PinoLogger));
    app.setGlobalPrefix('dm', { exclude: ['health'] });
    app.useGlobalPipes(new ValidationPipe({ whitelist: true }));

    const config = new DocumentBuilder()
        .setTitle('Cowork DM API')
        .setDescription(
            '다이렉트 메시지 서비스 — Socket.io 기반 실시간 DM\n\n' +
            '## WebSocket (/dm namespace)\n' +
            '| 이벤트 | 방향 | 설명 |\n' +
            '|--------|------|------|\n' +
            '| `join` | C→S | DM 대화방 룸 참가 |\n' +
            '| `leave` | C→S | 대화방 룸 퇴장 |\n' +
            '| `typing:start` | C→S | 타이핑 시작 |\n' +
            '| `typing:stop` | C→S | 타이핑 종료 |\n' +
            '| `message:new` | S→C | 새 메시지 수신 |\n' +
            '| `message:updated` | S→C | 메시지 수정됨 |\n' +
            '| `message:deleted` | S→C | 메시지 삭제됨 |\n' +
            '| `reaction:updated` | S→C | 리액션 변경됨 |\n' +
            '| `read:updated` | S→C | 상대방 읽음 처리 |\n' +
            '| `typing` | S→C | 타이핑 상태 |\n\n' +
            '## 인증\n' +
            'REST API: Gateway에서 주입된 `X-User-Id`, `X-User-Role` 헤더 사용.\n' +
            'WebSocket: Socket.io handshake의 `auth.token`에 JWT Bearer 토큰 전달.',
        )
        .setVersion('1.0')
        .addApiKey(
            { type: 'apiKey', in: 'header', name: 'X-User-Id', description: 'Gateway에서 주입되는 인증 사용자 ID' },
            'X-User-Id',
        )
        .addSecurityRequirements('X-User-Id')
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

    new Logger('Bootstrap').log(`DM server running on port ${port}`);
    new Logger('Bootstrap').log(`Swagger UI: ${await app.getUrl()}/api`);
}

bootstrap().catch((err) => {
    console.error('Application failed to start', err);
    process.exit(1);
});
