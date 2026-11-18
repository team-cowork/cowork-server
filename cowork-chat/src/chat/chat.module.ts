import {Module as nestModule} from '@nestjs/common';
import {MongooseModule} from '@nestjs/mongoose';
import {DmParticipantModule} from '../dm/dm-participant.module.js';
import {BlockModule} from '../block/block.module.js';
import {ObjectStorageModule} from '../storage/object-storage.module.js';
import {SearchModule} from '../search/search.module.js';
import {RedisRateLimiter} from '../common/util/redis-rate-limiter.js';
import {ThrottleGuard} from '../common/guard/throttle.guard.js';
import {MeetingNoteAttachmentController} from './meeting-note-attachment.controller.js';
import {MeetingNoteAttachmentDownloadService} from './service/meeting-note-attachment-download.service.js';
import {MeetingNoteAttachmentConsumer} from './kafka/meeting-note-attachment.consumer.js';
import {MeetingNoteAttachmentProjectionRepository} from './repository/meeting-note-attachment-projection.repository.js';
import {MeetingNoteAttachmentProjection, meetingNoteAttachmentProjectionSchema} from './schema/meeting-note-attachment-projection.schema.js';
import {WebhookRateLimiter} from './webhook/webhook-rate-limiter.js';
import {WebhookMessageRepository} from './webhook/webhook-message.repository.js';
import {WebhookController} from './webhook/webhook.controller.js';
import {WebhookService} from './webhook/webhook.service.js';
import {ChatGateway} from './chat.gateway.js';
import {ChatService} from './chat.service.js';
import {CHAT_SOCKET_SERVER} from './chat-socket-server.token.js';
import {AttachmentDownloadService} from './service/attachment-download.service.js';
import {AttachmentCleanupPoller} from './service/attachment-cleanup.poller.js';
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
	VoiceEventConsumer,
	ChannelEventConsumer,
	ProjectEventConsumer,
	ProjectPermissionEventConsumer,
	ProjectGithubRepoEventConsumer,
	TeamMemberEventConsumer,
	UserProfileEventConsumer,
	TeamRoleEventConsumer,
	ChannelRolePolicyEventConsumer,
	TextChannelSettingEventConsumer,
	TeamSettingEventConsumer,
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
	TagSpamService,
	ChatMessageLatencyMetricsService,
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
	TeamSettingProjection,
	teamSettingProjectionSchema,
	WebhookProjection,
	webhookProjectionSchema,
	ChatMessageQuarantineRecord,
	chatMessageQuarantineRecordSchema,
	MessageSearchTombstone,
	messageSearchTombstoneSchema,
	MessageSearchIndexState,
	messageSearchIndexStateSchema,
	AttachmentDeletion,
	attachmentDeletionSchema,
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
	TeamSettingProjectionRepository,
	WebhookProjectionRepository,
	ChatMessageQuarantineRepository,
	MessageSearchIndexRepository,
	MessageSearchTombstoneRepository,
	MessageSearchIndexStateRepository,
	AttachmentDeletionRepository,
} from './repository/index.js';
import {
	MessageSearchIndexService,
	MessageSearchDeletionService,
	MessageSearchOutboxPoller,
	MessageSearchRebuilder,
	MessageSearchAdminService,
} from './search/index.js';
import {MeetingNoteAttachmentRetention, meetingNoteAttachmentRetentionSchema} from './schema/meeting-note-attachment-retention.schema.js';
import {MeetingNoteAttachmentRetentionRepository} from './repository/meeting-note-attachment-retention.repository.js';
import {ThreadProjection, threadProjectionSchema} from './schema/thread-projection.schema.js';
import {ThreadProjectionRepository} from './repository/thread-projection.repository.js';
import {ThreadStateConsumer} from './kafka/thread-state.consumer.js';
import {ThreadParentCommandConsumer} from './kafka/thread-parent-command.consumer.js';

@nestModule({
	imports: [
		MongooseModule.forFeature([
			{name: MeetingNoteAttachmentRetention.name, schema: meetingNoteAttachmentRetentionSchema},
			{name: MeetingNoteAttachmentProjection.name, schema: meetingNoteAttachmentProjectionSchema},
			{name: ThreadProjection.name, schema: threadProjectionSchema},
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
			{name: TeamSettingProjection.name, schema: teamSettingProjectionSchema},
			{name: WebhookProjection.name, schema: webhookProjectionSchema},
			{name: ChatMessageQuarantineRecord.name, schema: chatMessageQuarantineRecordSchema},
			{name: MessageSearchTombstone.name, schema: messageSearchTombstoneSchema},
			{name: MessageSearchIndexState.name, schema: messageSearchIndexStateSchema},
			{name: AttachmentDeletion.name, schema: attachmentDeletionSchema},
		]),
		DmParticipantModule,
		BlockModule,
		ObjectStorageModule,
		SearchModule,
	],
	controllers: [
		MeetingNoteAttachmentController,
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
		MeetingNoteAttachmentRetentionRepository,
		MeetingNoteAttachmentProjectionRepository,
		MeetingNoteAttachmentConsumer,
		MeetingNoteAttachmentDownloadService,
		ThreadProjectionRepository,
		ThreadStateConsumer,
		ThreadParentCommandConsumer,
		WebhookService,
		WebhookMessageRepository,
		WebhookRateLimiter,
		ChatGateway,
		{provide: CHAT_SOCKET_SERVER, useExisting: ChatGateway},
		ChatService,
		AttachmentDownloadService,
		AttachmentDeletionRepository,
		AttachmentCleanupPoller,
		ChannelReadStateRepository,
		MessageRepository,
		DmParticipantRepository,
		ChatMessageProducer,
		ChatMessageConsumer,
		ChatMessageProcessor,
		ChatMessageScopeValidator,
		ChatMessageQuarantineRepository,
		ChatMessageQuarantineService,
		ChatMessageLatencyMetricsService,
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
		VoiceEventConsumer,
		ChannelEventConsumer,
		ProjectEventConsumer,
		ProjectPermissionEventConsumer,
		ProjectGithubRepoEventConsumer,
		TeamMemberEventConsumer,
		UserProfileEventConsumer,
		TeamRoleEventConsumer,
		ChannelRolePolicyEventConsumer,
		TextChannelSettingEventConsumer,
		TeamSettingEventConsumer,
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
		TeamSettingProjectionRepository,
		WebhookProjectionRepository,
		ChannelMessageReadAccessService,
		ChannelReadService,
		SlowmodeService,
		TagSpamService,
		UnifiedSearchResolver,
		RedisRateLimiter,
		ThrottleGuard,
	],
	exports: [RedisRateLimiter, ChatMessageProducer],
})
export class ChatModule {}
