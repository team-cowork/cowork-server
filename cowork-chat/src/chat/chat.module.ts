import {Module} from '@nestjs/common';
import {ConfigModule, ConfigService} from '@nestjs/config';
import {JwtModule} from '@nestjs/jwt';
import {MongooseModule} from '@nestjs/mongoose';
import {DicoshotModule} from 'dicoshot-nest';
import {DmParticipantModule} from '../dm/dm-participant.module';
import {BlockModule} from '../block/block.module';
import {ObjectStorageModule} from '../storage/object-storage.module';
import {SearchModule} from '../search/search.module';
import {getOptionalConfig, getRequiredConfig} from '../common/config/config.util';
import {RedisRateLimiter} from '../common/util/redis-rate-limiter';
import {SocketIoRedisConnection} from '../common/adapter/redis-io.adapter';
import {ThrottleGuard} from '../common/guard/throttle.guard';
import {ChatGateway} from './chat.gateway';
import {ChatService} from './chat.service';
import {AttachmentDownloadService} from './service/attachment-download.service';
import {ChatController} from './chat.controller';
import {DmController} from './dm.controller';
import {ProjectMessageController} from './project-message.controller';
import {TeamUnreadController} from './team-unread.controller';
import {TeamSearchController} from './team-search.controller';
import {ChatMessageQuarantineAdminController} from './chat-message-quarantine-admin.controller';
import {MessageSearchAdminController} from './message-search-admin.controller';
import {
    ChatMessageProducer,
    ChatMessageConsumer,
    NotificationTriggerProducer,
    NotificationOutboxPoller,
    GithubIssueResultConsumer,
    ChatGithubIssueCommandProducer,
    ChatGithubIssueResultConsumer,
    GithubRepoEventConsumer,
    ChannelEventConsumer,
    ProjectEventConsumer,
    ProjectPermissionEventConsumer,
    ProjectGithubRepoEventConsumer,
    TeamMemberEventConsumer,
    UserProfileEventConsumer,
    TeamRoleEventConsumer,
    ChannelRolePolicyEventConsumer,
    ChatMessageScopeValidator,
    ChatMessageProcessor,
    ChatMessageQuarantinePoller,
} from './kafka';
import {
    ProjectClient,
    UnreadCounterService,
    ChannelClient,
    ChannelSearchClient,
    UserClient,
    ChannelMessageReadAccessService,
    ChatMessageQuarantineService,
} from './service';
import {UnifiedSearchResolver} from './unified-search.resolver';
import {
    Message,
    MessageSchema,
    ChannelReadState,
    ChannelReadStateSchema,
    DmParticipant,
    DmParticipantSchema,
    ChannelProjection,
    ChannelProjectionSchema,
    ProjectPermissionProjection,
    ProjectPermissionProjectionSchema,
    ProjectProjection,
    ProjectProjectionSchema,
    ProjectGithubRepoProjection,
    ProjectGithubRepoProjectionSchema,
    TeamMemberProjection,
    TeamMemberProjectionSchema,
    UserProfileProjection,
    UserProfileProjectionSchema,
    TeamRoleProjection,
    TeamRoleProjectionSchema,
    TeamRoleAssignmentProjection,
    TeamRoleAssignmentProjectionSchema,
    TeamRoleMemberTombstone,
    TeamRoleMemberTombstoneSchema,
    ChannelRolePolicyProjection,
    ChannelRolePolicyProjectionSchema,
    ChatMessageQuarantineRecord,
    ChatMessageQuarantineRecordSchema,
    MessageSearchTombstone,
    MessageSearchTombstoneSchema,
    MessageSearchIndexState,
    MessageSearchIndexStateSchema,
} from './schema';
import {
    ChannelReadStateRepository,
    MessageRepository,
    DmParticipantRepository,
    ChannelProjectionRepository,
    ProjectPermissionProjectionRepository,
    ProjectProjectionRepository,
    ProjectGithubRepoProjectionRepository,
    TeamMemberProjectionRepository,
    UserProfileProjectionRepository,
    TeamRoleProjectionRepository,
    ChannelRolePolicyProjectionRepository,
    ChatMessageQuarantineRepository,
    MessageSearchIndexRepository,
    MessageSearchTombstoneRepository,
    MessageSearchIndexStateRepository,
} from './repository';
import {
    MessageSearchIndexService,
    MessageSearchDeletionService,
    MessageSearchOutboxPoller,
    MessageSearchRebuilder,
    MessageSearchAdminService,
} from './search';

const IS_PRODUCTION = process.env.NODE_ENV === 'production';

@Module({
    imports: [
        JwtModule.registerAsync({
            imports: [ConfigModule],
            inject: [ConfigService],
            useFactory: (configService: ConfigService) => ({
                secret: getRequiredConfig(configService, 'JWT_SECRET'),
                signOptions: {algorithm: 'HS256'},
            }),
        }),
        MongooseModule.forFeature([
            {name: Message.name, schema: MessageSchema},
            {name: ChannelReadState.name, schema: ChannelReadStateSchema},
            {name: DmParticipant.name, schema: DmParticipantSchema},
            {name: ChannelProjection.name, schema: ChannelProjectionSchema},
            {name: ProjectPermissionProjection.name, schema: ProjectPermissionProjectionSchema},
            {name: ProjectProjection.name, schema: ProjectProjectionSchema},
            {name: ProjectGithubRepoProjection.name, schema: ProjectGithubRepoProjectionSchema},
            {name: TeamMemberProjection.name, schema: TeamMemberProjectionSchema},
            {name: UserProfileProjection.name, schema: UserProfileProjectionSchema},
            {name: TeamRoleProjection.name, schema: TeamRoleProjectionSchema},
            {name: TeamRoleAssignmentProjection.name, schema: TeamRoleAssignmentProjectionSchema},
            {name: TeamRoleMemberTombstone.name, schema: TeamRoleMemberTombstoneSchema},
            {name: ChannelRolePolicyProjection.name, schema: ChannelRolePolicyProjectionSchema},
            {name: ChatMessageQuarantineRecord.name, schema: ChatMessageQuarantineRecordSchema},
            {name: MessageSearchTombstone.name, schema: MessageSearchTombstoneSchema},
            {name: MessageSearchIndexState.name, schema: MessageSearchIndexStateSchema},
        ]),
        DicoshotModule.registerAsync({
            imports: [ConfigModule],
            inject: [ConfigService],
            useFactory: (...[configService]: unknown[]) => ({
                webhookUrl: getOptionalConfig(configService as ConfigService, 'DISCORD_WEBHOOK_URL'),
                applicationName: 'cowork-chat',
                locale: 'ko',
            }),
            filter: IS_PRODUCTION ? {environment: 'production'} : false,
            interceptor: IS_PRODUCTION,
        }),
        DmParticipantModule,
        BlockModule,
        ObjectStorageModule,
        SearchModule,
    ],
    controllers: [
        ChatController,
        DmController,
        ProjectMessageController,
        TeamUnreadController,
        TeamSearchController,
        ChatMessageQuarantineAdminController,
        MessageSearchAdminController,
    ],
    providers: [
        ChatGateway,
        ChatService,
        AttachmentDownloadService,
        ChannelReadStateRepository,
        MessageRepository,
        DmParticipantRepository,
        ChatMessageProducer,
        ChatMessageConsumer,
        ChatMessageProcessor,
        ChatMessageScopeValidator,
        ChatMessageQuarantineRepository,
        ChatMessageQuarantineService,
        ChatMessageQuarantinePoller,
        MessageSearchIndexRepository,
        MessageSearchTombstoneRepository,
        MessageSearchIndexStateRepository,
        MessageSearchIndexService,
        MessageSearchDeletionService,
        MessageSearchOutboxPoller,
        MessageSearchRebuilder,
        MessageSearchAdminService,
        NotificationTriggerProducer,
        NotificationOutboxPoller,
        GithubIssueResultConsumer,
        ChatGithubIssueCommandProducer,
        ChatGithubIssueResultConsumer,
        GithubRepoEventConsumer,
        ChannelEventConsumer,
        ProjectEventConsumer,
        ProjectPermissionEventConsumer,
        ProjectGithubRepoEventConsumer,
        TeamMemberEventConsumer,
        UserProfileEventConsumer,
        TeamRoleEventConsumer,
        ChannelRolePolicyEventConsumer,
        ProjectClient,
        UnreadCounterService,
        ChannelClient,
        ChannelSearchClient,
        UserClient,
        ChannelProjectionRepository,
        ProjectPermissionProjectionRepository,
        ProjectProjectionRepository,
        ProjectGithubRepoProjectionRepository,
        TeamMemberProjectionRepository,
        UserProfileProjectionRepository,
        TeamRoleProjectionRepository,
        ChannelRolePolicyProjectionRepository,
        ChannelMessageReadAccessService,
        UnifiedSearchResolver,
        RedisRateLimiter,
        SocketIoRedisConnection,
        ThrottleGuard,
    ],
    exports: [RedisRateLimiter, SocketIoRedisConnection, ChatMessageProducer],
})
export class ChatModule {}
