import { Global, Module } from '@nestjs/common';
import { MongooseModule } from '@nestjs/mongoose';
import { ProjectionCheckpoint, ProjectionCheckpointSchema } from './projection-checkpoint.schema';
import { ProjectionCheckpointRepository } from './projection-checkpoint.repository';
import {
    ProjectionQuarantineRecord,
    ProjectionQuarantineRecordSchema,
} from './projection-quarantine.schema';
import { ProjectionReadinessService } from './projection-readiness.service';
import { ProjectionDataset, ProjectionDatasetSchema } from './projection-dataset.schema';
import { ProjectionDatasetRepository } from './projection-dataset.repository';
import { ProjectionMetricsService } from './projection-metrics.service';
import { ProjectionAdminController } from './projection-admin.controller';
import { ChannelMember, ChannelMemberSchema } from '../../chat/schema/channel-member.schema';
import { ChannelProjection, ChannelProjectionSchema } from '../../chat/schema/channel-projection.schema';
import { ProjectProjection, ProjectProjectionSchema } from '../../chat/schema/project-projection.schema';
import {
    ProjectMemberProjection,
    ProjectMemberProjectionSchema,
} from '../../chat/schema/project-member-projection.schema';
import {
    ProjectGithubRepoProjection,
    ProjectGithubRepoProjectionSchema,
} from '../../chat/schema/project-github-repo-projection.schema';
import { TeamMemberProjection, TeamMemberProjectionSchema } from '../../chat/schema/team-member-projection.schema';
import { UserProfileProjection, UserProfileProjectionSchema } from '../../chat/schema/user-profile-projection.schema';

@Global()
@Module({
    imports: [
        MongooseModule.forFeature([
            { name: ProjectionCheckpoint.name, schema: ProjectionCheckpointSchema },
            { name: ProjectionQuarantineRecord.name, schema: ProjectionQuarantineRecordSchema },
            { name: ProjectionDataset.name, schema: ProjectionDatasetSchema },
            { name: ChannelMember.name, schema: ChannelMemberSchema },
            { name: ChannelProjection.name, schema: ChannelProjectionSchema },
            { name: ProjectProjection.name, schema: ProjectProjectionSchema },
            { name: ProjectMemberProjection.name, schema: ProjectMemberProjectionSchema },
            { name: ProjectGithubRepoProjection.name, schema: ProjectGithubRepoProjectionSchema },
            { name: TeamMemberProjection.name, schema: TeamMemberProjectionSchema },
            { name: UserProfileProjection.name, schema: UserProfileProjectionSchema },
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
