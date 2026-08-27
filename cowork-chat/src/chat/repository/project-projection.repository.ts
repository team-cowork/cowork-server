import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, mongo } from 'mongoose';
import { ProjectProjection } from '../schema/project-projection.schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    projectionUpdateApplied,
    projectionTimestampFields,
    projectionSourceVersion,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

export interface ProjectProjectionEvent {
    projectId: number;
    teamId: number;
    name: string;
    description: string | null;
    status: string;
    position: number;
    occurredAt: Date;
    sourceVersion: mongo.Long;
}

@Injectable()
export class ProjectProjectionRepository {
    constructor(@InjectModel(ProjectProjection.name) private readonly model: Model<ProjectProjection>) {}

    async upsert(event: ProjectProjectionEvent): Promise<boolean> {
        const shouldApply = activeProjectionCondition(event.sourceVersion);
        const result = await this.model.updateOne(
            { projectId: event.projectId },
            [{
                $set: {
                    projectId: event.projectId,
                    teamId: {
                        $cond: [shouldApply, event.teamId, { $ifNull: ['$teamId', event.teamId] }],
                    },
                    name: {
                        $cond: [shouldApply, { $literal: event.name }, { $ifNull: ['$name', ''] }],
                    },
                    description: {
                        $cond: [
                            shouldApply,
                            { $literal: event.description },
                            { $ifNull: ['$description', null] },
                        ],
                    },
                    status: {
                        $cond: [shouldApply, { $literal: event.status }, { $ifNull: ['$status', ''] }],
                    },
                    position: {
                        $cond: [shouldApply, event.position, { $ifNull: ['$position', 0] }],
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

    async remove(projectId: number, occurredAt: Date, sourceVersion: mongo.Long): Promise<boolean> {
        const shouldApply = deletedProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            { projectId },
            [{
                $set: {
                    projectId,
                    teamId: { $ifNull: ['$teamId', 0] },
                    name: { $ifNull: ['$name', ''] },
                    description: { $ifNull: ['$description', null] },
                    status: { $ifNull: ['$status', ''] },
                    position: { $ifNull: ['$position', 0] },
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
