import { Module } from '@nestjs/common';
import { ServeStaticModule } from '@nestjs/serve-static';
import { join } from 'path';
import { ChatGateway } from './chat.gateway';
import { ChatService } from './chat.service';

@Module({
    imports: [
        ServeStaticModule.forRoot({
            rootPath: join(__dirname, '..', 'public'),
            serveRoot: '/',
        }),
    ],
    providers: [ChatGateway, ChatService],
})
export class ChatModule {}
