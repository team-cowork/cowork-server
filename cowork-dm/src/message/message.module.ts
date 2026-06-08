import { forwardRef, Module } from '@nestjs/common';
import { MongooseModule } from '@nestjs/mongoose';
import { DmMessage, DmMessageSchema } from './schema/dm-message.schema';
import { MessageRepository } from './message.repository';
import { MessageService } from './message.service';
import { MessageController } from './message.controller';
import { ConversationModule } from '../conversation/conversation.module';
import { BlockModule } from '../block/block.module';
import { GatewayModule } from '../gateway/gateway.module';
import { MinioModule } from '../storage/minio.module';
import { NotificationProducer } from '../kafka/notification.producer';
import { NotificationOutboxPoller } from '../kafka/notification-outbox.poller';

@Module({
    imports: [
        MongooseModule.forFeature([
            { name: DmMessage.name, schema: DmMessageSchema },
        ]),
        ConversationModule,
        BlockModule,
        forwardRef(() => GatewayModule),
        MinioModule,
    ],
    controllers: [MessageController],
    providers: [
        MessageRepository,
        MessageService,
        NotificationProducer,
        NotificationOutboxPoller,
    ],
    exports: [MessageRepository, MessageService],
})
export class MessageModule {}
