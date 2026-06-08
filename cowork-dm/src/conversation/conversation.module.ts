import { Module } from '@nestjs/common';
import { MongooseModule } from '@nestjs/mongoose';
import { DmConversation, DmConversationSchema } from './schema/dm-conversation.schema';
import { ConversationRepository } from './conversation.repository';
import { ConversationService } from './conversation.service';
import { ConversationController } from './conversation.controller';

@Module({
    imports: [
        MongooseModule.forFeature([
            { name: DmConversation.name, schema: DmConversationSchema },
        ]),
    ],
    controllers: [ConversationController],
    providers: [ConversationRepository, ConversationService],
    exports: [ConversationRepository, ConversationService],
})
export class ConversationModule {}
