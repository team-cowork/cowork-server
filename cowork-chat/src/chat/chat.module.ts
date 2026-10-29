import process from 'node:process';
import {Module as nestModule} from '@nestjs/common';
import {ConfigModule, ConfigService} from '@nestjs/config';
import {JwtModule} from '@nestjs/jwt';
import {MongooseModule} from '@nestjs/mongoose';
import {DicoshotModule} from 'dicoshot-nest';
import {DmParticipantModule} from '../dm/dm-participant.module.js';
import {BlockModule} from '../block/block.module.js';
import {ObjectStorageModule} from '../storage/object-storage.module.js';
import {SearchModule} from '../search/search.module.js';
import {getOptionalConfig, getRequiredConfig} from '../common/config/config.util.js';
import {RedisRateLimiter} from '../common/util/redis-rate-limiter.js';
import {SocketIoRedisConnection} from '../common/adapter/redis-io.adapter.js';
import {ThrottleGuard} from '../common/guard/throttle.guard.js';
import {WebhookRateLimiter} from './webhook/webhook-rate-limiter.js';
import {WebhookMessageRepository} from './webhook/webhook-message.repository.js';
import {WebhookController} from './webhook/webhook.controller.js';
import {WebhookService} from './webhook/webhook.service.js';
import {ChatGateway} from './chat.gateway.js';
import {ChatService} from './chat.service.js';
import {AttachmentDownloadService} from './service/attachment-download.service.js';
import {ChatController} from './chat.controller.js';
import {DmController} from './dm.controller.js';
import {ProjectMessageController} from './project-message.controller.js';
import {TeamUnreadController} from './team-unread.controller.js';
import {TeamSearchController} from './team-search.controller.js';
import {ChatMessageQuarantineAdminController} from './chat-message-quarantine-admin.controller.js';
import {MessageSearchAdminController} from './message-search-admin.controller.js';
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
	TextChannelSettingEventConsumer,
	WebhookEventConsumer,
	ChatMessageScopeValidator,
	ChatMessageProcessor,
	ChatMessageQuarantinePoller,
} from './kafka/index.js';
import {
	ProjectClient,
	UnreadCounterService,
	ChannelClient,
	ChannelSearchClient,
	UserClient,
	ChannelMessageReadAccessService,
	ChannelReadService,
	SlowmodeService,
	ChatMessageQuarantineService,
} from './service/index.js';
import {UnifiedSearchResolver} from './unified-search.resolver.js';
import {
	Message,
	messageSchema,
	ChannelReadState,
	channelReadStateSchema,
	DmParticipant,
	dmParticipantSchema,
	ChannelProjection,
	channelProjectionSchema,
	ProjectPermissionProjection,
	projectPermissionProjectionSchema,
	ProjectProjection,
	projectProjectionSchema,
	ProjectGithubRepoProjection,
	projectGithubRepoProjectionSchema,
	TeamMemberProjection,
	teamMemberProjectionSchema,
	UserProfileProjection,
	userProfileProjectionSchema,
	TeamRoleProjection,
	teamRoleProjectionSchema,
	TeamRoleAssignmentProjection,
	teamRoleAssignmentProjectionSchema,
	TeamRoleMemberTombstone,
	teamRoleMemberTombstoneSchema,
	ChannelRolePolicyProjection,
	channelRolePolicyProjectionSchema,
	TextChannelSettingProjection,
	textChannelSettingProjectionSchema,
	WebhookProjection,
	webhookProjectionSchema,
	ChatMessageQuarantineRecord,
	chatMessageQuarantineRecordSchema,
	MessageSearchTombstone,
	messageSearchTombstoneSchema,
	MessageSearchIndexState,
	messageSearchIndexStateSchema,
} from './schema/index.js';
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
	TextChannelSettingProjectionRepository,
	WebhookProjectionRepository,
	ChatMessageQuarantineRepository,
	MessageSearchIndexRepository,
	MessageSearchTombstoneRepository,
	MessageSearchIndexStateRepository,
} from './repository/index.js';
import {
	MessageSearchIndexService,
	MessageSearchDeletionService,
	MessageSearchOutboxPoller,
	MessageSearchRebuilder,
	MessageSearchAdminService,
} from './search/index.js';

const IS_PRODUCTION = process.env.NODE_ENV === 'production';

@nestModule({
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
			{name: Message.name, schema: messageSchema},
			{name: ChannelReadState.name, schema: channelReadStateSchema},
			{name: DmParticipant.name, schema: dmParticipantSchema},
			{name: ChannelProjection.name, schema: channelProjectionSchema},
			{name: ProjectPermissionProjection.name, schema: projectPermissionProjectionSchema},
			{name: ProjectProjection.name, schema: projectProjectionSchema},
			{name: ProjectGithubRepoProjection.name, schema: projectGithubRepoProjectionSchema},
			{name: TeamMemberProjection.name, schema: teamMemberProjectionSchema},
			{name: UserProfileProjection.name, schema: userProfileProjectionSchema},
			{name: TeamRoleProjection.name, schema: teamRoleProjectionSchema},
			{name: TeamRoleAssignmentProjection.name, schema: teamRoleAssignmentProjectionSchema},
			{name: TeamRoleMemberTombstone.name, schema: teamRoleMemberTombstoneSchema},
			{name: ChannelRolePolicyProjection.name, schema: channelRolePolicyProjectionSchema},
			{name: TextChannelSettingProjection.name, schema: textChannelSettingProjectionSchema},
			{name: WebhookProjection.name, schema: webhookProjectionSchema},
			{name: ChatMessageQuarantineRecord.name, schema: chatMessageQuarantineRecordSchema},
			{name: MessageSearchTombstone.name, schema: messageSearchTombstoneSchema},
			{name: MessageSearchIndexState.name, schema: messageSearchIndexStateSchema},
		]),
		DicoshotModule.registerAsync({
			imports: [ConfigModule],
			inject: [ConfigService],
			useFactory: (...[configService]: unknown[]) => ({
				webhookUrl: getOptionalConfig(configService as ConfigService, 'DISCORD_CHAT_ERROR_WEBHOOK_URL'),
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
		WebhookController,
		ChatController,
		DmController,
		ProjectMessageController,
		TeamUnreadController,
		TeamSearchController,
		ChatMessageQuarantineAdminController,
		MessageSearchAdminController,
	],
	providers: [
		WebhookService,
		WebhookMessageRepository,
		WebhookRateLimiter,
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
		TextChannelSettingEventConsumer,
		WebhookEventConsumer,
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
		TextChannelSettingProjectionRepository,
		WebhookProjectionRepository,
		ChannelMessageReadAccessService,
		ChannelReadService,
		SlowmodeService,
		UnifiedSearchResolver,
		RedisRateLimiter,
		SocketIoRedisConnection,
		ThrottleGuard,
	],
	exports: [RedisRateLimiter, SocketIoRedisConnection, ChatMessageProducer],
})
export class ChatModule {}
