import 'reflect-metadata';
import 'dotenv/config';
import { NestFactory } from '@nestjs/core';
import { NestExpressApplication } from '@nestjs/platform-express';
import { Logger, ValidationPipe } from '@nestjs/common';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import { join } from 'path';
import { ChatModule } from './chat/chat.module';

async function bootstrap() {
    const app = await NestFactory.create<NestExpressApplication>(ChatModule);
    app.setGlobalPrefix('chat');
    app.useGlobalPipes(new ValidationPipe({ whitelist: true }));
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
            '## 인증\n' +
            'Gateway에서 주입된 `X-User-Id`, `X-User-Role` 헤더 사용.\n' +
            'WebSocket은 handshake 헤더로 인증.',
        )
        .setVersion('1.0')
        .build();

    const document = SwaggerModule.createDocument(app, config);
    SwaggerModule.setup('api', app, document, { jsonDocumentUrl: 'api-json' });

    const port = process.env.PORT ?? 3000;
    await app.listen(port);
    new Logger('Bootstrap').log(`Chat server running on port ${port}`);
    new Logger('Bootstrap').log(`Swagger UI: http://localhost:${port}/api`);
}

bootstrap().catch((err) => {
    new Logger('Bootstrap').error('Application failed to start', err);
});
