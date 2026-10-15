import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {ProjectGithubRepoProjection} from '../schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    isProjectionUpdateApplied,
    projectionTimestampFields,
    projectionSourceVersion,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

export type ProjectGithubRepoProjectionView = {
    repoId: number;
    projectId: number;
    teamId: number;
    githubRepoUrl: string;
    owner: string;
    repo: string;
    webhookChannelId: number | null;
};

export type VersionedProjectGithubRepoProjectionView = {
    occurredAt: Date;
    sourceVersion: mongo.Long;
} & ProjectGithubRepoProjectionView;

@Injectable()
export class ProjectGithubRepoProjectionRepository {
    constructor(
        @InjectModel(ProjectGithubRepoProjection.name)
        private readonly model: Model<ProjectGithubRepoProjection>,
    ) {}

    async findAllByProjectId(projectId: number): Promise<ProjectGithubRepoProjectionView[]> {
        return this.model.find({projectId, deleted: {$ne: true}})
            .sort({repoId: 1})
            .lean<ProjectGithubRepoProjectionView[]>();
    }

    async findWebhookTargets(owner: string, repo: string): Promise<ProjectGithubRepoProjectionView[]> {
        return this.model.find({
            owner: owner.toLowerCase(),
            repo: repo.toLowerCase(),
            webhookChannelId: {$ne: null},
            deleted: {$ne: true},
        }).lean<ProjectGithubRepoProjectionView[]>();
    }

    async upsert(event: VersionedProjectGithubRepoProjectionView): Promise<boolean> {
        const applyCondition = activeProjectionCondition(event.sourceVersion);
        const result = await this.model.updateOne(
            {repoId: event.repoId},
            [{
                $set: {
                    repoId: event.repoId,
                    projectId: {
                        $cond: [applyCondition, event.projectId, {$ifNull: ['$projectId', event.projectId]}],
                    },
                    teamId: {
                        $cond: [applyCondition, event.teamId, {$ifNull: ['$teamId', event.teamId]}],
                    },
                    githubRepoUrl: {
                        $cond: [applyCondition, {$literal: event.githubRepoUrl}, {$ifNull: ['$githubRepoUrl', '']}],
                    },
                    owner: {
                        $cond: [applyCondition, {$literal: event.owner}, {$ifNull: ['$owner', '']}],
                    },
                    repo: {
                        $cond: [applyCondition, {$literal: event.repo}, {$ifNull: ['$repo', '']}],
                    },
                    webhookChannelId: {
                        $cond: [
                            applyCondition,
                            {$literal: event.webhookChannelId},
                            {$ifNull: ['$webhookChannelId', null]},
                        ],
                    },
                    deleted: {$cond: [applyCondition, false, {$ifNull: ['$deleted', false]}]},
                    sourceOccurredAt: {
                        $cond: [
                            applyCondition,
                            event.occurredAt,
                            {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]},
                        ],
                    },
                    sourceVersion: {
                        $cond: [applyCondition, event.sourceVersion, projectionSourceVersion],
                    },
                    ...projectionTimestampFields(applyCondition, event.occurredAt),
                },
            }],
            {upsert: true, timestamps: false, updatePipeline: true},
        );
        return isProjectionUpdateApplied(result);
    }

    async remove(repoId: number, occurredAt: Date, sourceVersion: mongo.Long): Promise<boolean> {
        const applyCondition = deletedProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            {repoId},
            [{
                $set: {
                    repoId,
                    projectId: {$ifNull: ['$projectId', 0]},
                    teamId: {$ifNull: ['$teamId', 0]},
                    githubRepoUrl: {$ifNull: ['$githubRepoUrl', '']},
                    owner: {$ifNull: ['$owner', '']},
                    repo: {$ifNull: ['$repo', '']},
                    webhookChannelId: {$ifNull: ['$webhookChannelId', null]},
                    deleted: {$cond: [applyCondition, true, {$ifNull: ['$deleted', false]}]},
                    sourceOccurredAt: {
                        $cond: [applyCondition, occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}],
                    },
                    sourceVersion: {$cond: [applyCondition, sourceVersion, projectionSourceVersion]},
                    ...projectionTimestampFields(applyCondition, occurredAt),
                },
            }],
            {upsert: true, timestamps: false, updatePipeline: true},
        );
        return isProjectionUpdateApplied(result);
    }
}
