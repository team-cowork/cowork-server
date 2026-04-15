import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { ChatModule } from './chat.module';

async function bootstrap() {
    const app = await NestFactory.create(ChatModule);
    await app.listen(3000);
    console.log('🚀 Chat server running on port 3000');
}
bootstrap();
