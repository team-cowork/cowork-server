import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { JwtModule } from '@nestjs/jwt';
import { MongooseModule } from '@nestjs/mongoose';
import { DicoshotModule } from 'dicoshot-nest';
import { ChatGateway } from './chat.gateway';
import { ChatService } from './chat.service';
import { ChatController } from './chat.controller';
import { DmController } from './dm.controller';
import { ProjectMessageController } from './project-message.controller';
import { TeamUnreadController } from './team-unread.controller';
import { TeamSearchController } from './team-search.controller';
import { ChatMessageProducer } from './kafka/chat-message.producer';
import { ChatMessageConsumer } from './kafka/chat-message.consumer';
import { NotificationTriggerProducer } from './kafka/notification-trigger.producer';
import { NotificationOutboxPoller } from './kafka/notification-outbox.poller';
import { GithubIssueProducer } from './kafka/github-issue.producer';
import { GithubIssueResultConsumer } from './kafka/github-issue-result.consumer';
import { ChannelEventConsumer } from './kafka/channel-event.consumer';
import { ProjectEventConsumer } from './kafka/project-event.consumer';
import { ProjectClient } from './service/project.client';
import { ChannelClient } from './service/channel.client';
import { ChannelSearchClient } from './service/channel-search.client';
import { UnifiedSearchResolver } from './unified-search.resolver';
import { UserClient } from './service/user.client';
import { Message, MessageSchema } from './schema/message.schema';
import { ChannelMember, ChannelMemberSchema } from './schema/channel-member.schema';
import { MessageRepository } from './repository/message.repository';
import { ChannelMemberRepository } from './repository/channel-member.repository';
import { MembershipModule } from '../membership/membership.module';
import { BlockModule } from '../block/block.module';
import { MinioModule } from '../storage/minio.module';
import { SearchModule } from '../search/search.module';
import { getOptionalConfig, getRequiredConfig } from '../common/config/config.util';
import { RedisRateLimiter } from '../common/util/redis-rate-limiter';

const IS_PRODUCTION = process.env.NODE_ENV === 'production';

@Module({
    imports: [
        JwtModule.registerAsync({
            imports: [ConfigModule],
            inject: [ConfigService],
            useFactory: (configService: ConfigService) => ({
                secret: getRequiredConfig(configService, 'JWT_SECRET'),
                signOptions: { algorithm: 'HS256' },
            }),
        }),
        MongooseModule.forFeature([
            { name: Message.name, schema: MessageSchema },
            { name: ChannelMember.name, schema: ChannelMemberSchema },
        ]),
        DicoshotModule.registerAsync({
            imports: [ConfigModule],
            inject: [ConfigService],
            useFactory: (...[configService]: unknown[]) => ({
                webhookUrl: getOptionalConfig(configService as ConfigService, 'DISCORD_WEBHOOK_URL'),
                applicationName: 'cowork-chat',
                locale: 'ko',
            }),
            filter: IS_PRODUCTION ? { environment: 'production' } : false,
            interceptor: IS_PRODUCTION,
        }),
        MembershipModule,
        BlockModule,
        MinioModule,
        SearchModule,
    ],
    controllers: [ChatController, DmController, ProjectMessageController, TeamUnreadController, TeamSearchController],
    providers: [
        ChatGateway,
        ChatService,
        MessageRepository,
        ChannelMemberRepository,
        ChatMessageProducer,
        ChatMessageConsumer,
        NotificationTriggerProducer,
        NotificationOutboxPoller,
        GithubIssueProducer,
        GithubIssueResultConsumer,
        ChannelEventConsumer,
        ProjectEventConsumer,
        ProjectClient,
        ChannelClient,
        ChannelSearchClient,
        UserClient,
        UnifiedSearchResolver,
        RedisRateLimiter,
    ],
})
export class ChatModule {}
