import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { NestExpressApplication } from '@nestjs/platform-express';
import { Logger, ValidationPipe } from '@nestjs/common';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import { join } from 'path';
import { ChatModule } from './chat/chat.module';

async function bootstrap() {
    const app = await NestFactory.create<NestExpressApplication>(ChatModule);
    app.useGlobalPipes(new ValidationPipe());
    app.useStaticAssets(join(__dirname, '..', 'public'));

    const config = new DocumentBuilder()
        .setTitle('Cowork Chat API')
        .setDescription(
            '채팅 서비스 — Socket.io 기반 실시간 채팅\n\n' +
            '> **이 서비스는 HTTP REST API가 없으며 모든 통신은 Socket.io WebSocket으로 이루어집니다.**\n\n' +
            '## WebSocket 연결\n' +
            '- **엔드포인트**: `ws://{host}:{port}`\n' +
            '- **프로토콜**: Socket.io v4\n\n' +
            '## 이벤트 목록\n\n' +
            '### Client → Server\n' +
            '| 이벤트 | 페이로드 | 설명 |\n' +
            '|--------|---------|------|\n' +
            '| `message` | `MessagePayload` | 채널에 메시지 전송 |\n\n' +
            '### Server → Client\n' +
            '| 이벤트 | 페이로드 | 설명 |\n' +
            '|--------|---------|------|\n' +
            '| `message` | `MessagePayload` | 채널 멤버에게 메시지 브로드캐스트 |\n\n' +
            '## MessagePayload 스키마\n' +
            '```json\n' +
            '{ "channelId": "string", "content": "string" }\n' +
            '```\n\n' +
            '전체 AsyncAPI 스펙은 `/asyncapi.json` 에서 확인하세요.',
        )
        .setVersion('1.0')
        .addBearerAuth()
        .build();

    const document = SwaggerModule.createDocument(app, config);
    SwaggerModule.setup('api', app, document, {
        jsonDocumentUrl: 'api-json',
    });

    const port = process.env.PORT || 3000;
    await app.listen(port);
    new Logger('Bootstrap').log(`Chat server running on port ${port}`);
    new Logger('Bootstrap').log(`Swagger UI: http://localhost:${port}/api`);
}
bootstrap().catch((err) => {
    new Logger('Bootstrap').error('Application failed to start', err);
});
