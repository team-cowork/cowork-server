import {randomUUID} from 'node:crypto';
import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model} from 'mongoose';
import {WebhookProjection} from '../../chat/schema/webhook-projection.schema';
import {DmParticipant} from '../../chat/schema/dm-participant.schema';
import {ChannelProjection} from '../../chat/schema/channel-projection.schema';
import {ChannelRolePolicyProjection} from '../../chat/schema/channel-role-policy-projection.schema';
import {ProjectGithubRepoProjection} from '../../chat/schema/project-github-repo-projection.schema';
import {ProjectPermissionProjection} from '../../chat/schema/project-permission-projection.schema';
import {ProjectProjection} from '../../chat/schema/project-projection.schema';
import {TeamMemberProjection} from '../../chat/schema/team-member-projection.schema';
import {TeamRoleAssignmentProjection} from '../../chat/schema/team-role-assignment-projection.schema';
import {TeamRoleMemberTombstone} from '../../chat/schema/team-role-member-tombstone.schema';
import {TeamRoleProjection} from '../../chat/schema/team-role-projection.schema';
import {TextChannelSettingProjection} from '../../chat/schema/text-channel-setting-projection.schema';
import {UserProfileProjection} from '../../chat/schema/user-profile-projection.schema';
import {
    ProjectionDataset,
    ProjectionDatasetPartitionOffset,
    ProjectionDatasetStatus,
    ProjectionRunMode,
} from './projection-dataset.schema';

export type ProjectionStreamName =
    | 'channel'
    | 'project'
    | 'dmParticipant'
    | 'projectPermission'
    | 'projectGithubRepo'
    | 'teamMember'
    | 'teamRole'
    | 'channelRolePolicy'
    | 'textChannelSetting'
    | 'webhook'
    | 'userProfile';

export type ProjectionDatasetState = {
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
    lastObservedDocumentCount: number | null;
    baseOffsets: ProjectionDatasetPartitionOffset[];
    targetOffsets: ProjectionDatasetPartitionOffset[];
};

type ProjectionDatasetStream = {
    name: string;
    groupId: string;
    topic: string;
    sourceGeneration: string;
};

@Injectable()
export class ProjectionDatasetRepository {
    private readonly projectionModels: Record<ProjectionStreamName, Array<Model<unknown>>>;

    constructor(
        @InjectModel(ProjectionDataset.name) private readonly datasets: Model<ProjectionDataset>,
        @InjectModel(ChannelProjection.name) channel: Model<ChannelProjection>,
        @InjectModel(ProjectProjection.name) project: Model<ProjectProjection>,
        @InjectModel(DmParticipant.name) dmParticipant: Model<DmParticipant>,
        @InjectModel(ProjectPermissionProjection.name) projectPermission: Model<ProjectPermissionProjection>,
        @InjectModel(ProjectGithubRepoProjection.name) projectGithubRepo: Model<ProjectGithubRepoProjection>,
        @InjectModel(TeamMemberProjection.name) teamMember: Model<TeamMemberProjection>,
        @InjectModel(UserProfileProjection.name) userProfile: Model<UserProfileProjection>,
        @InjectModel(TeamRoleProjection.name) teamRole: Model<TeamRoleProjection>,
        @InjectModel(TeamRoleAssignmentProjection.name) teamRoleAssignment: Model<TeamRoleAssignmentProjection>,
        @InjectModel(TeamRoleMemberTombstone.name) teamRoleMemberTombstone: Model<TeamRoleMemberTombstone>,
        @InjectModel(ChannelRolePolicyProjection.name) channelRolePolicy: Model<ChannelRolePolicyProjection>,
        @InjectModel(TextChannelSettingProjection.name) textChannelSetting: Model<TextChannelSettingProjection>,
        @InjectModel(WebhookProjection.name) webhook: Model<WebhookProjection>,
    ) {
        this.projectionModels = {
            channel: [channel],
            project: [project],
            dmParticipant: [dmParticipant],
            projectPermission: [projectPermission],
            projectGithubRepo: [projectGithubRepo],
            teamMember: [teamMember],
            userProfile: [userProfile],
            teamRole: [teamRole, teamRoleAssignment, teamRoleMemberTombstone],
            channelRolePolicy: [channelRolePolicy],
            textChannelSetting: [textChannelSetting],
            webhook: [webhook],
        } as Record<ProjectionStreamName, Array<Model<unknown>>>;
    }

    async find(stream: ProjectionStreamName): Promise<ProjectionDatasetState | undefined> {
        const dataset = await this.datasets.findOne({stream}).lean<ProjectionDataset | null>();
        return dataset ? this.toState(dataset) : undefined;
    }

    async createBootstrap(
        stream: ProjectionDatasetStream,
        baseOffsets: ProjectionDatasetPartitionOffset[],
        targetOffsets: ProjectionDatasetPartitionOffset[],
    ): Promise<ProjectionDatasetState> {
        const datasetGeneration = randomUUID();
        const created = await this.datasets.findOneAndUpdate(
            {stream: stream.name},
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
            {upsert: true, new: true},
        ).lean<ProjectionDataset>();
        return this.toState(created);
    }

    async markRebuildRequired(
        stream: ProjectionDatasetStream,
        reason: string,
        expectedDataset?: Pick<ProjectionDatasetState, 'datasetGeneration' | 'status'>,
    ): Promise<ProjectionDatasetState> {
        const dataset = await this.datasets.findOneAndUpdate(
            {
                stream: stream.name,
                ...(expectedDataset !== undefined && {
                    datasetGeneration: expectedDataset.datasetGeneration,
                    status: expectedDataset.status,
                }),
            },
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
            {upsert: expectedDataset === undefined, new: true},
        ).lean<ProjectionDataset | null>();
        if (!dataset) {
            const current = await this.find(stream.name as ProjectionStreamName);
            if (!current) {
                throw new Error(`Projection dataset disappeared before marking rebuild required: ${stream.name}`);
            }

            return current;
        }

        return this.toState(dataset);
    }

    async requestRebuild(
        stream: ProjectionDatasetStream,
        reason: string,
        baseOffsets: ProjectionDatasetPartitionOffset[],
        targetOffsets: ProjectionDatasetPartitionOffset[],
        // eslint-disable-next-line no-warning-comments -- Tracked compatibility cleanup with a deployment-dependent deadline.
        // TODO(#596): 2026-11-01 05:00 KST 이후 구형 stamp 보정·rollback 종료 확인 후 호환 재구축 전용 CAS 인자·분기 제거.
        // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
        expectedDatasetGeneration?: string,
    ): Promise<ProjectionDatasetState> {
        const datasetGeneration = randomUUID();
        const dataset = await this.datasets.findOneAndUpdate(
            {
                stream: stream.name,
                ...(expectedDatasetGeneration !== undefined && {
                    datasetGeneration: expectedDatasetGeneration,
                    status: 'ACTIVE',
                }),
            },
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
                $setOnInsert: {activationDocumentCount: 0},
                $unset: {activatedAt: ''},
            },
            {upsert: expectedDatasetGeneration === undefined, new: true},
        ).lean<ProjectionDataset | null>();
        if (!dataset) {
            const current = await this.find(stream.name as ProjectionStreamName);
            if (!current) {
                throw new Error(`Projection dataset disappeared before requesting rebuild: ${stream.name}`);
            }

            return current;
        }

        return this.toState(dataset);
    }

    // eslint-disable-next-line no-warning-comments -- Tracked compatibility cleanup with a deployment-dependent deadline.
    // TODO(#596): 2026-11-01 05:00 KST 이후 구형 Kafka·Mongo stamp 보정·rollback 종료 확인 후 이 누락 조회 제거.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    async hasLegacyProjectPermission(): Promise<boolean> {
        return (await this.projectionModels.projectPermission[0].exists({
            sourceMembershipVersion: {$exists: false},
        })) !== null;
    }

    async tryBeginReset(stream: ProjectionStreamName, datasetGeneration: string): Promise<boolean> {
        const result = await this.datasets.updateOne(
            {stream, datasetGeneration, status: 'REBUILD_REQUESTED'},
            {$set: {status: 'RESETTING'}},
        );
        return result.matchedCount === 1;
    }

    async markRebuilding(stream: ProjectionStreamName, datasetGeneration: string): Promise<void> {
        const result = await this.datasets.updateOne(
            {stream, datasetGeneration, status: 'RESETTING'},
            {$set: {status: 'REBUILDING'}},
        );
        if (result.matchedCount !== 1) {
            throw new Error(`Projection rebuild reset was superseded: ${stream}`);
        }
    }

    async markActive(stream: ProjectionStreamName, datasetGeneration: string): Promise<void> {
        const activationDocumentCount = await this.countProjection(stream);
        const result = await this.datasets.updateOne(
            {stream, datasetGeneration, status: {$in: ['INITIALIZING', 'REBUILDING']}},
            {
                $set: {
                    status: 'ACTIVE',
                    mode: 'INCREMENTAL',
                    reason: null,
                    activationDocumentCount,
                    lastObservedDocumentCount: activationDocumentCount,
                    activatedAt: new Date(),
                },
            },
        );
        if (result.matchedCount !== 1) {
            throw new Error(`Projection dataset activation was superseded: ${stream}`);
        }
    }

    async countProjection(stream: ProjectionStreamName): Promise<number> {
        const counts = await Promise.all(this.projectionModels[stream].map(model => model.countDocuments({})));
        return counts.reduce((total, count) => total + count, 0);
    }

    /**
    주기 점검용 저비용 추정치. 대용량 컬렉션에서 매초 정확한 countDocuments를 도는 부담을 피한다.
    */
    async estimateProjectionCount(stream: ProjectionStreamName): Promise<number> {
        const counts = await Promise.all(
            this.projectionModels[stream].map(async model => model.estimatedDocumentCount()),
        );
        return counts.reduce((total, count) => total + count, 0);
    }

    /**
    진행 중인 rebuild로 datasetGeneration이 바뀐 뒤에는 옛 관측치를 덮어쓰지 않는다.
    */
    async recordObservedDocumentCount(
        stream: ProjectionStreamName,
        datasetGeneration: string,
        count: number,
    ): Promise<void> {
        await this.datasets.updateOne(
            {stream, datasetGeneration},
            {$set: {lastObservedDocumentCount: count}},
        );
    }

    /**
    Projection-only collection은 비우고, dmParticipant는 chat 소유 필드를 보존한 tombstone으로 만든다.
    */
    async resetProjection(stream: ProjectionStreamName): Promise<void> {
        const models = this.projectionModels[stream];
        if (stream !== 'dmParticipant') {
            await Promise.all(models.map(async model => model.deleteMany({})));
            return;
        }

        await models[0].updateMany({}, {
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
            ...(dataset.reason != null && {reason: dataset.reason}),
            ...(dataset.rebuildRequestedAt && {rebuildRequestedAt: dataset.rebuildRequestedAt}),
            ...(dataset.activatedAt && {activatedAt: dataset.activatedAt}),
            activationDocumentCount: dataset.activationDocumentCount ?? 0,
            lastObservedDocumentCount: dataset.lastObservedDocumentCount ?? null,
            baseOffsets: dataset.baseOffsets ?? [],
            targetOffsets: dataset.targetOffsets ?? [],
        };
    }
}
