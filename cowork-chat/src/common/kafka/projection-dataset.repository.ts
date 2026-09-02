import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { randomUUID } from 'crypto';
import { Model } from 'mongoose';
import { ChannelMember } from '../../chat/schema/channel-member.schema';
import { ChannelProjection } from '../../chat/schema/channel-projection.schema';
import { ProjectGithubRepoProjection } from '../../chat/schema/project-github-repo-projection.schema';
import { ProjectMemberProjection } from '../../chat/schema/project-member-projection.schema';
import { ProjectProjection } from '../../chat/schema/project-projection.schema';
import { TeamMemberProjection } from '../../chat/schema/team-member-projection.schema';
import { UserProfileProjection } from '../../chat/schema/user-profile-projection.schema';
import {
    ProjectionDataset,
    ProjectionDatasetPartitionOffset,
    ProjectionDatasetStatus,
    ProjectionRunMode,
} from './projection-dataset.schema';

export type ProjectionStreamName =
    | 'channel'
    | 'project'
    | 'channelMember'
    | 'projectMember'
    | 'projectGithubRepo'
    | 'teamMember'
    | 'userProfile';

export interface ProjectionDatasetState {
    stream: ProjectionStreamName;
    groupId: string;
    topic: string;
    sourceGeneration: string;
    datasetGeneration: string;
    mode: ProjectionRunMode;
    status: ProjectionDatasetStatus;
    reason?: string;
    rebuildRequestedAt?: Date;
    activatedAt?: Date;
    activationDocumentCount: number;
    baseOffsets: ProjectionDatasetPartitionOffset[];
    targetOffsets: ProjectionDatasetPartitionOffset[];
}

interface ProjectionDatasetStream {
    name: string;
    groupId: string;
    topic: string;
    sourceGeneration: string;
}

@Injectable()
export class ProjectionDatasetRepository {
    private readonly projectionModels: Record<ProjectionStreamName, Model<unknown>>;

    constructor(
        @InjectModel(ProjectionDataset.name) private readonly datasets: Model<ProjectionDataset>,
        @InjectModel(ChannelProjection.name) channel: Model<ChannelProjection>,
        @InjectModel(ProjectProjection.name) project: Model<ProjectProjection>,
        @InjectModel(ChannelMember.name) channelMember: Model<ChannelMember>,
        @InjectModel(ProjectMemberProjection.name) projectMember: Model<ProjectMemberProjection>,
        @InjectModel(ProjectGithubRepoProjection.name) projectGithubRepo: Model<ProjectGithubRepoProjection>,
        @InjectModel(TeamMemberProjection.name) teamMember: Model<TeamMemberProjection>,
        @InjectModel(UserProfileProjection.name) userProfile: Model<UserProfileProjection>,
    ) {
        this.projectionModels = {
            channel,
            project,
            channelMember,
            projectMember,
            projectGithubRepo,
            teamMember,
            userProfile,
        } as Record<ProjectionStreamName, Model<unknown>>;
    }

    async find(stream: ProjectionStreamName): Promise<ProjectionDatasetState | undefined> {
        const dataset = await this.datasets.findOne({ stream }).lean<ProjectionDataset | null>();
        return dataset ? this.toState(dataset) : undefined;
    }

    async createBootstrap(
        stream: ProjectionDatasetStream,
        baseOffsets: ProjectionDatasetPartitionOffset[],
        targetOffsets: ProjectionDatasetPartitionOffset[],
    ): Promise<ProjectionDatasetState> {
        const datasetGeneration = randomUUID();
        const created = await this.datasets.findOneAndUpdate(
            { stream: stream.name },
            {
                $setOnInsert: {
                    stream: stream.name,
                    groupId: stream.groupId,
                    topic: stream.topic,
                    sourceGeneration: stream.sourceGeneration,
                    datasetGeneration,
                    mode: 'BOOTSTRAP',
                    status: 'INITIALIZING',
                    reason: null,
                    activationDocumentCount: 0,
                    baseOffsets,
                    targetOffsets,
                },
            },
            { upsert: true, new: true },
        ).lean<ProjectionDataset>();
        return this.toState(created);
    }

    async markRebuildRequired(
        stream: ProjectionDatasetStream,
        reason: string,
    ): Promise<ProjectionDatasetState> {
        const dataset = await this.datasets.findOneAndUpdate(
            { stream: stream.name },
            {
                $set: {
                    groupId: stream.groupId,
                    topic: stream.topic,
                    status: 'REBUILD_REQUIRED',
                    reason,
                },
                $setOnInsert: {
                    sourceGeneration: stream.sourceGeneration,
                    datasetGeneration: randomUUID(),
                    mode: 'REBUILD',
                    activationDocumentCount: 0,
                    baseOffsets: [],
                    targetOffsets: [],
                },
            },
            { upsert: true, new: true },
        ).lean<ProjectionDataset>();
        return this.toState(dataset);
    }

    async requestRebuild(
        stream: ProjectionDatasetStream,
        reason: string,
        baseOffsets: ProjectionDatasetPartitionOffset[],
        targetOffsets: ProjectionDatasetPartitionOffset[],
    ): Promise<ProjectionDatasetState> {
        const datasetGeneration = randomUUID();
        const dataset = await this.datasets.findOneAndUpdate(
            { stream: stream.name },
            {
                $set: {
                    groupId: stream.groupId,
                    topic: stream.topic,
                    sourceGeneration: stream.sourceGeneration,
                    datasetGeneration,
                    mode: 'REBUILD',
                    status: 'REBUILD_REQUESTED',
                    reason,
                    rebuildRequestedAt: new Date(),
                    baseOffsets,
                    targetOffsets,
                },
                $setOnInsert: { activationDocumentCount: 0 },
                $unset: { activatedAt: '' },
            },
            { upsert: true, new: true },
        ).lean<ProjectionDataset>();
        return this.toState(dataset);
    }

    async tryBeginReset(stream: ProjectionStreamName, datasetGeneration: string): Promise<boolean> {
        const result = await this.datasets.updateOne(
            { stream, datasetGeneration, status: 'REBUILD_REQUESTED' },
            { $set: { status: 'RESETTING' } },
        );
        return result.matchedCount === 1;
    }

    async markRebuilding(stream: ProjectionStreamName, datasetGeneration: string): Promise<void> {
        const result = await this.datasets.updateOne(
            { stream, datasetGeneration, status: 'RESETTING' },
            { $set: { status: 'REBUILDING' } },
        );
        if (result.matchedCount !== 1) throw new Error(`Projection rebuild reset was superseded: ${stream}`);
    }

    async markActive(stream: ProjectionStreamName, datasetGeneration: string): Promise<void> {
        const activationDocumentCount = await this.countProjection(stream);
        const result = await this.datasets.updateOne(
            { stream, datasetGeneration, status: { $in: ['INITIALIZING', 'REBUILDING'] } },
            {
                $set: {
                    status: 'ACTIVE',
                    mode: 'INCREMENTAL',
                    reason: null,
                    activationDocumentCount,
                    activatedAt: new Date(),
                },
            },
        );
        if (result.matchedCount !== 1) throw new Error(`Projection dataset activation was superseded: ${stream}`);
    }

    async countProjection(stream: ProjectionStreamName): Promise<number> {
        return this.projectionModels[stream].countDocuments({});
    }

    /** projection-only collection은 비우고, channelMember는 chat 소유 필드를 보존한 tombstone으로 만든다. */
    async resetProjection(stream: ProjectionStreamName): Promise<void> {
        const model = this.projectionModels[stream];
        if (stream !== 'channelMember') {
            await model.deleteMany({});
            return;
        }
        await model.updateMany({}, {
            $set: {
                deleted: true,
                sourceOccurredAt: new Date(0),
                sourceVersion: 0n,
            },
        });
    }

    private toState(dataset: ProjectionDataset): ProjectionDatasetState {
        return {
            stream: dataset.stream as ProjectionStreamName,
            groupId: dataset.groupId,
            topic: dataset.topic,
            sourceGeneration: dataset.sourceGeneration,
            datasetGeneration: dataset.datasetGeneration,
            mode: dataset.mode,
            status: dataset.status,
            ...(dataset.reason ? { reason: dataset.reason } : {}),
            ...(dataset.rebuildRequestedAt ? { rebuildRequestedAt: dataset.rebuildRequestedAt } : {}),
            ...(dataset.activatedAt ? { activatedAt: dataset.activatedAt } : {}),
            activationDocumentCount: dataset.activationDocumentCount ?? 0,
            baseOffsets: dataset.baseOffsets ?? [],
            targetOffsets: dataset.targetOffsets ?? [],
        };
    }
}
