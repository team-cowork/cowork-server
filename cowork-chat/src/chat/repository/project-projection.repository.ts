import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {ProjectProjection} from '../schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    isProjectionUpdateApplied,
    projectionTimestampFields,
    projectionSourceVersion,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

export type ProjectProjectionEvent = {
    projectId: number;
    teamId: number;
    createdBy?: number | null;
    name: string;
    description: string | null;
    status: string;
    position: number;
    occurredAt: Date;
    sourceVersion: mongo.Long;
};

@Injectable()
export class ProjectProjectionRepository {
    constructor(@InjectModel(ProjectProjection.name) private readonly model: Model<ProjectProjection>) {}

    async findById(projectId: number): Promise<ProjectProjection | null> {
        return this.model.findOne({projectId, deleted: {$ne: true}}).lean<ProjectProjection>();
    }

    async upsert(event: ProjectProjectionEvent): Promise<boolean> {
        const applyCondition = activeProjectionCondition(event.sourceVersion);
        const result = await this.model.updateOne(
            {projectId: event.projectId},
            [{
                $set: {
                    projectId: event.projectId,
                    createdBy: {
                        $cond: [
                            {$or: [applyCondition, {$and: [{$eq: [event.sourceVersion, projectionSourceVersion]}, {$eq: [{$ifNull: ['$createdBy', null]}, null]}]}]},
                            event.createdBy ?? null,
                            {$ifNull: ['$createdBy', null]},
                        ],
                    },
                    teamId: {
                        $cond: [applyCondition, event.teamId, {$ifNull: ['$teamId', event.teamId]}],
                    },
                    name: {
                        $cond: [applyCondition, {$literal: event.name}, {$ifNull: ['$name', '']}],
                    },
                    description: {
                        $cond: [
                            applyCondition,
                            {$literal: event.description},
                            {$ifNull: ['$description', null]},
                        ],
                    },
                    status: {
                        $cond: [applyCondition, {$literal: event.status}, {$ifNull: ['$status', '']}],
                    },
                    position: {
                        $cond: [applyCondition, event.position, {$ifNull: ['$position', 0]}],
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

    async remove(projectId: number, occurredAt: Date, sourceVersion: mongo.Long): Promise<boolean> {
        const applyCondition = deletedProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            {projectId},
            [{
                $set: {
                    projectId,
                    teamId: {$ifNull: ['$teamId', 0]},
                    name: {$ifNull: ['$name', '']},
                    description: {$ifNull: ['$description', null]},
                    status: {$ifNull: ['$status', '']},
                    position: {$ifNull: ['$position', 0]},
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
