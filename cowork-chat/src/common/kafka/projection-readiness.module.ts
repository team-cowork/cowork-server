import {Global, Module} from '@nestjs/common';
import {MongooseModule} from '@nestjs/mongoose';
import {DmParticipant, DmParticipantSchema} from '../../chat/schema/dm-participant.schema';
import {ChannelProjection, ChannelProjectionSchema} from '../../chat/schema/channel-projection.schema';
import {
    ChannelRolePolicyProjection,
    ChannelRolePolicyProjectionSchema,
} from '../../chat/schema/channel-role-policy-projection.schema';
import {ProjectProjection, ProjectProjectionSchema} from '../../chat/schema/project-projection.schema';
import {
    ProjectPermissionProjection,
    ProjectPermissionProjectionSchema,
} from '../../chat/schema/project-permission-projection.schema';
import {
    ProjectGithubRepoProjection,
    ProjectGithubRepoProjectionSchema,
} from '../../chat/schema/project-github-repo-projection.schema';
import {TeamMemberProjection, TeamMemberProjectionSchema} from '../../chat/schema/team-member-projection.schema';
import {
    TeamRoleAssignmentProjection,
    TeamRoleAssignmentProjectionSchema,
} from '../../chat/schema/team-role-assignment-projection.schema';
import {
    TeamRoleMemberTombstone,
    TeamRoleMemberTombstoneSchema,
} from '../../chat/schema/team-role-member-tombstone.schema';
import {TeamRoleProjection, TeamRoleProjectionSchema} from '../../chat/schema/team-role-projection.schema';
import {
    TextChannelSettingProjection,
    TextChannelSettingProjectionSchema,
} from '../../chat/schema/text-channel-setting-projection.schema';
import {UserProfileProjection, UserProfileProjectionSchema} from '../../chat/schema/user-profile-projection.schema';
import {ProjectionAdminController} from './projection-admin.controller';
import {ProjectionMetricsService} from './projection-metrics.service';
import {ProjectionDatasetRepository} from './projection-dataset.repository';
import {ProjectionDataset, ProjectionDatasetSchema} from './projection-dataset.schema';
import {ProjectionReadinessService} from './projection-readiness.service';
import {
    ProjectionQuarantineRecord,
    ProjectionQuarantineRecordSchema,
} from './projection-quarantine.schema';
import {ProjectionCheckpointRepository} from './projection-checkpoint.repository';
import {ProjectionCheckpoint, ProjectionCheckpointSchema} from './projection-checkpoint.schema';

@Global()
@Module({
    imports: [
        MongooseModule.forFeature([
            {name: ProjectionCheckpoint.name, schema: ProjectionCheckpointSchema},
            {name: ProjectionQuarantineRecord.name, schema: ProjectionQuarantineRecordSchema},
            {name: ProjectionDataset.name, schema: ProjectionDatasetSchema},
            {name: DmParticipant.name, schema: DmParticipantSchema},
            {name: ChannelProjection.name, schema: ChannelProjectionSchema},
            {name: ProjectProjection.name, schema: ProjectProjectionSchema},
            {name: ProjectPermissionProjection.name, schema: ProjectPermissionProjectionSchema},
            {name: ProjectGithubRepoProjection.name, schema: ProjectGithubRepoProjectionSchema},
            {name: TeamMemberProjection.name, schema: TeamMemberProjectionSchema},
            {name: TeamRoleProjection.name, schema: TeamRoleProjectionSchema},
            {name: TeamRoleAssignmentProjection.name, schema: TeamRoleAssignmentProjectionSchema},
            {name: TeamRoleMemberTombstone.name, schema: TeamRoleMemberTombstoneSchema},
            {name: ChannelRolePolicyProjection.name, schema: ChannelRolePolicyProjectionSchema},
            {name: TextChannelSettingProjection.name, schema: TextChannelSettingProjectionSchema},
            {name: UserProfileProjection.name, schema: UserProfileProjectionSchema},
        ]),
    ],
    controllers: [ProjectionAdminController],
    providers: [
        ProjectionCheckpointRepository,
        ProjectionDatasetRepository,
        ProjectionMetricsService,
        ProjectionReadinessService,
    ],
    exports: [ProjectionReadinessService],
})
export class ProjectionReadinessModule {}
