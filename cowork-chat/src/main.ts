import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { Logger } from '@nestjs/common';
import { ChatModule } from './chat.module';

async function bootstrap() {
    const app = await NestFactory.create(ChatModule);
    const port = process.env.PORT || 3000;
    await app.listen(port);
    new Logger('Bootstrap').log(`Chat server running on port ${port}`);
}
bootstrap().catch((err) => {
    new Logger('Bootstrap').error('Application failed to start', err);
});
