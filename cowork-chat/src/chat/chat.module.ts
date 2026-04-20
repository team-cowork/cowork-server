import { Module } from '@nestjs/common';
import { MongooseModule } from '@nestjs/mongoose';
import { PrometheusModule } from '@willsoto/nestjs-prometheus';
import { ChatGateway } from './chat.gateway';
import { ChatService } from './chat.service';
import { Message, MessageSchema } from './schema/message.schema';

@Module({
    imports: [
        PrometheusModule.register({
            defaultMetrics: { enabled: true },
            path: '/metrics',
        }),
        MongooseModule.forRoot(process.env.MONGODB_URI ?? 'mongodb://localhost:27017/cowork'),
        MongooseModule.forFeature([{ name: Message.name, schema: MessageSchema }]),
    ],
    providers: [ChatGateway, ChatService],
})
export class ChatModule {}
