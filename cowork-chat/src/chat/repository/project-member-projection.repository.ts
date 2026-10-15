import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {ProjectMemberProjection} from '../schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    isProjectionUpdateApplied,
    projectionTimestampFields,
    projectionSourceVersion,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

@Injectable()
export class ProjectMemberProjectionRepository {
    constructor(@InjectModel(ProjectMemberProjection.name) private readonly model: Model<ProjectMemberProjection>) {}

    async exists(projectId: number, userId: number): Promise<boolean> {
        return (await this.model.exists({projectId, userId, deleted: {$ne: true}})) !== null;
    }

    async add(projectId: number, userId: number, occurredAt: Date, sourceVersion: mongo.Long): Promise<boolean> {
        const applyCondition = activeProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            {projectId, userId},
            [{
                $set: {
                    projectId,
                    userId,
                    deleted: {$cond: [applyCondition, false, {$ifNull: ['$deleted', false]}]},
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

    async remove(projectId: number, userId: number, occurredAt: Date, sourceVersion: mongo.Long): Promise<boolean> {
        const applyCondition = deletedProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            {projectId, userId},
            [{
                $set: {
                    projectId,
                    userId,
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

    async removeByProjectId(projectId: number, occurredAt: Date, sourceVersion: mongo.Long): Promise<boolean> {
        const applyCondition = deletedProjectionCondition(sourceVersion);
        const result = await this.model.updateMany(
            {projectId},
            [{
                $set: {
                    deleted: {$cond: [applyCondition, true, {$ifNull: ['$deleted', false]}]},
                    sourceOccurredAt: {
                        $cond: [applyCondition, occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}],
                    },
                    sourceVersion: {$cond: [applyCondition, sourceVersion, projectionSourceVersion]},
                    ...projectionTimestampFields(applyCondition, occurredAt),
                },
            }],
            {timestamps: false, updatePipeline: true},
        );
        return result.modifiedCount > 0;
    }
}
