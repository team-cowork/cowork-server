import { Module } from '@nestjs/common';
import { MongooseModule } from '@nestjs/mongoose';
import { ChatGateway } from './chat.gateway';
import { ChatService } from './chat.service';
import { ChatController } from './chat.controller';
import { ChatMessageProducer } from './kafka/chat-message.producer';
import { ChatMessageConsumer } from './kafka/chat-message.consumer';
import { Message, MessageSchema } from './schema/message.schema';
import { ChannelMember, ChannelMemberSchema } from './schema/channel-member.schema';
import { MembershipModule } from '../membership/membership.module';

@Module({
    imports: [
        MongooseModule.forRoot(process.env.MONGODB_URI ?? 'mongodb://localhost:27017/cowork'),
        MongooseModule.forFeature([
            { name: Message.name, schema: MessageSchema },
            { name: ChannelMember.name, schema: ChannelMemberSchema },
        ]),
        MembershipModule,
    ],
    controllers: [ChatController],
    providers: [ChatGateway, ChatService, ChatMessageProducer, ChatMessageConsumer],
})
export class ChatModule {}
