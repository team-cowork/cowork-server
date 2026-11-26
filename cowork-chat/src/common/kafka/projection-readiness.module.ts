import {Global as globalModule, Module as nestModule} from '@nestjs/common';
import {MongooseModule} from '@nestjs/mongoose';
import {MeetingNoteAttachmentProjection, meetingNoteAttachmentProjectionSchema} from '../../chat/schema/meeting-note-attachment-projection.schema.js';
import {ThreadProjection, threadProjectionSchema} from '../../chat/schema/thread-projection.schema.js';
import {WebhookProjection, webhookProjectionSchema} from '../../chat/schema/webhook-projection.schema.js';
import {DmParticipant, dmParticipantSchema} from '../../chat/schema/dm-participant.schema.js';
import {ChannelProjection, channelProjectionSchema} from '../../chat/schema/channel-projection.schema.js';
import {
	ChannelAccessProjection,
	channelAccessProjectionSchema,
} from '../../chat/schema/channel-access-projection.schema.js';
import {ProjectProjection, projectProjectionSchema} from '../../chat/schema/project-projection.schema.js';
import {
	ProjectPermissionProjection,
	projectPermissionProjectionSchema,
} from '../../chat/schema/project-permission-projection.schema.js';
import {
	ProjectGithubRepoProjection,
	projectGithubRepoProjectionSchema,
} from '../../chat/schema/project-github-repo-projection.schema.js';
import {TeamMemberProjection, teamMemberProjectionSchema} from '../../chat/schema/team-member-projection.schema.js';
import {TeamAccessProjection, teamAccessProjectionSchema} from '../../chat/schema/team-access-projection.schema.js';
import {
	TextChannelSettingProjection,
	textChannelSettingProjectionSchema,
} from '../../chat/schema/text-channel-setting-projection.schema.js';
import {TeamSettingProjection, teamSettingProjectionSchema} from '../../chat/schema/team-setting-projection.schema.js';
import {UserProfileProjection, userProfileProjectionSchema} from '../../chat/schema/user-profile-projection.schema.js';
import {AccountPolicy, accountPolicySchema} from './account-policy.schema.js';
import {AccountPolicyService} from './account-policy.service.js';
import {AccountPolicyConsumer} from './account-policy.consumer.js';
import {ProjectionAdminController} from './projection-admin.controller.js';
import {ProjectionMetricsService} from './projection-metrics.service.js';
import {ProjectionDatasetRepository} from './projection-dataset.repository.js';
import {ProjectionDataset, projectionDatasetSchema} from './projection-dataset.schema.js';
import {ProjectionReadinessService} from './projection-readiness.service.js';
import {
	ProjectionQuarantineRecord,
	projectionQuarantineRecordSchema,
} from './projection-quarantine.schema.js';
import {ProjectionCheckpointRepository} from './projection-checkpoint.repository.js';
import {ProjectionCheckpoint, projectionCheckpointSchema} from './projection-checkpoint.schema.js';

@globalModule()
@nestModule({
	imports: [
		MongooseModule.forFeature([
			{name: AccountPolicy.name, schema: accountPolicySchema},
			{name: MeetingNoteAttachmentProjection.name, schema: meetingNoteAttachmentProjectionSchema},
			{name: ThreadProjection.name, schema: threadProjectionSchema},
			{name: ProjectionCheckpoint.name, schema: projectionCheckpointSchema},
			{name: ProjectionQuarantineRecord.name, schema: projectionQuarantineRecordSchema},
			{name: ProjectionDataset.name, schema: projectionDatasetSchema},
			{name: DmParticipant.name, schema: dmParticipantSchema},
			{name: ChannelProjection.name, schema: channelProjectionSchema},
			{name: ProjectProjection.name, schema: projectProjectionSchema},
			{name: ProjectPermissionProjection.name, schema: projectPermissionProjectionSchema},
			{name: ProjectGithubRepoProjection.name, schema: projectGithubRepoProjectionSchema},
			{name: TeamMemberProjection.name, schema: teamMemberProjectionSchema},
			{name: TeamAccessProjection.name, schema: teamAccessProjectionSchema},
			{name: ChannelAccessProjection.name, schema: channelAccessProjectionSchema},
			{name: TextChannelSettingProjection.name, schema: textChannelSettingProjectionSchema},
			{name: TeamSettingProjection.name, schema: teamSettingProjectionSchema},
			{name: WebhookProjection.name, schema: webhookProjectionSchema},
			{name: UserProfileProjection.name, schema: userProfileProjectionSchema},
		]),
	],
	controllers: [ProjectionAdminController],
	providers: [
		AccountPolicyConsumer,
		AccountPolicyService,
		ProjectionCheckpointRepository,
		ProjectionDatasetRepository,
		ProjectionMetricsService,
		ProjectionReadinessService,
	],
	exports: [ProjectionReadinessService, AccountPolicyService],
})
export class ProjectionReadinessModule {}
