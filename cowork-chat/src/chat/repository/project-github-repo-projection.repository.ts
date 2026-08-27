import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, mongo } from 'mongoose';
import { ProjectGithubRepoProjection } from '../schema/project-github-repo-projection.schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    projectionUpdateApplied,
    projectionTimestampFields,
    projectionSourceVersion,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

export interface ProjectGithubRepoProjectionView {
    repoId: number;
    projectId: number;
    teamId: number;
    githubRepoUrl: string;
    owner: string;
    repo: string;
    webhookChannelId: number | null;
}

export interface VersionedProjectGithubRepoProjectionView extends ProjectGithubRepoProjectionView {
    occurredAt: Date;
    sourceVersion: mongo.Long;
}

@Injectable()
export class ProjectGithubRepoProjectionRepository {
    constructor(
        @InjectModel(ProjectGithubRepoProjection.name)
        private readonly model: Model<ProjectGithubRepoProjection>,
    ) {}

    async findAllByProjectId(projectId: number): Promise<ProjectGithubRepoProjectionView[]> {
        return this.model.find({ projectId, deleted: { $ne: true } })
            .sort({ repoId: 1 })
            .lean<ProjectGithubRepoProjectionView[]>();
    }

    async findWebhookTargets(owner: string, repo: string): Promise<ProjectGithubRepoProjectionView[]> {
        return this.model.find({
            owner: owner.toLowerCase(),
            repo: repo.toLowerCase(),
            webhookChannelId: { $ne: null },
            deleted: { $ne: true },
        }).lean<ProjectGithubRepoProjectionView[]>();
    }

    async upsert(event: VersionedProjectGithubRepoProjectionView): Promise<boolean> {
        const shouldApply = activeProjectionCondition(event.sourceVersion);
        const result = await this.model.updateOne(
            { repoId: event.repoId },
            [{
                $set: {
                    repoId: event.repoId,
                    projectId: {
                        $cond: [shouldApply, event.projectId, { $ifNull: ['$projectId', event.projectId] }],
                    },
                    teamId: {
                        $cond: [shouldApply, event.teamId, { $ifNull: ['$teamId', event.teamId] }],
                    },
                    githubRepoUrl: {
                        $cond: [shouldApply, { $literal: event.githubRepoUrl }, { $ifNull: ['$githubRepoUrl', ''] }],
                    },
                    owner: {
                        $cond: [shouldApply, { $literal: event.owner }, { $ifNull: ['$owner', ''] }],
                    },
                    repo: {
                        $cond: [shouldApply, { $literal: event.repo }, { $ifNull: ['$repo', ''] }],
                    },
                    webhookChannelId: {
                        $cond: [
                            shouldApply,
                            { $literal: event.webhookChannelId },
                            { $ifNull: ['$webhookChannelId', null] },
                        ],
                    },
                    deleted: { $cond: [shouldApply, false, { $ifNull: ['$deleted', false] }] },
                    sourceOccurredAt: {
                        $cond: [
                            shouldApply,
                            event.occurredAt,
                            { $ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH] },
                        ],
                    },
                    sourceVersion: {
                        $cond: [shouldApply, event.sourceVersion, projectionSourceVersion],
                    },
                    ...projectionTimestampFields(shouldApply, event.occurredAt),
                },
            }],
            { upsert: true, timestamps: false },
        );
        return projectionUpdateApplied(result);
    }

    async remove(repoId: number, occurredAt: Date, sourceVersion: mongo.Long): Promise<boolean> {
        const shouldApply = deletedProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            { repoId },
            [{
                $set: {
                    repoId,
                    projectId: { $ifNull: ['$projectId', 0] },
                    teamId: { $ifNull: ['$teamId', 0] },
                    githubRepoUrl: { $ifNull: ['$githubRepoUrl', ''] },
                    owner: { $ifNull: ['$owner', ''] },
                    repo: { $ifNull: ['$repo', ''] },
                    webhookChannelId: { $ifNull: ['$webhookChannelId', null] },
                    deleted: { $cond: [shouldApply, true, { $ifNull: ['$deleted', false] }] },
                    sourceOccurredAt: {
                        $cond: [shouldApply, occurredAt, { $ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH] }],
                    },
                    sourceVersion: { $cond: [shouldApply, sourceVersion, projectionSourceVersion] },
                    ...projectionTimestampFields(shouldApply, occurredAt),
                },
            }],
            { upsert: true, timestamps: false },
        );
        return projectionUpdateApplied(result);
    }
}
