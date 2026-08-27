import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, mongo } from 'mongoose';
import { ChannelProjection } from '../schema/channel-projection.schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    projectionUpdateApplied,
    projectionTimestampFields,
    projectionSourceVersion,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

export interface ChannelProjectionView {
    channelId: number;
    teamId: number | null;
    projectId: number | null;
    name: string;
    type: string;
    viewType: string;
    description: string | null;
    isPrivate: boolean;
    position: number;
}

export interface ChannelProjectionEvent extends ChannelProjectionView {
    eventType: 'CREATED' | 'UPDATED';
    occurredAt: Date;
    sourceVersion: mongo.Long;
}

@Injectable()
export class ChannelProjectionRepository {
    constructor(@InjectModel(ChannelProjection.name) private readonly model: Model<ChannelProjection>) {}

    async findById(channelId: number): Promise<ChannelProjectionView | null> {
        return this.model.findOne({ channelId, deleted: { $ne: true } }).lean<ChannelProjectionView>();
    }

    async searchByTeamAndName(teamId: number, query: string): Promise<ChannelProjectionView[]> {
        const escapedQuery = query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        return this.model
            .find({ teamId, deleted: { $ne: true }, name: { $regex: escapedQuery, $options: 'i' } })
            .sort({ position: 1, channelId: 1 })
            .lean<ChannelProjectionView[]>();
    }

    async upsert(event: ChannelProjectionEvent): Promise<boolean> {
        const {
            channelId,
            teamId,
            projectId,
            name,
            type,
            viewType,
            description,
            isPrivate,
            position,
            occurredAt,
            sourceVersion,
        } = event;
        const shouldApply = activeProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            { channelId },
            [{
                $set: {
                    channelId,
                    teamId: { $cond: [shouldApply, { $literal: teamId }, { $ifNull: ['$teamId', null] }] },
                    projectId: {
                        $cond: [shouldApply, { $literal: projectId }, { $ifNull: ['$projectId', null] }],
                    },
                    name: { $cond: [shouldApply, { $literal: name }, { $ifNull: ['$name', ''] }] },
                    type: { $cond: [shouldApply, { $literal: type }, { $ifNull: ['$type', ''] }] },
                    viewType: { $cond: [shouldApply, { $literal: viewType }, { $ifNull: ['$viewType', ''] }] },
                    description: {
                        $cond: [shouldApply, { $literal: description }, { $ifNull: ['$description', null] }],
                    },
                    isPrivate: { $cond: [shouldApply, isPrivate, { $ifNull: ['$isPrivate', false] }] },
                    position: { $cond: [shouldApply, position, { $ifNull: ['$position', 0] }] },
                    deleted: { $cond: [shouldApply, false, { $ifNull: ['$deleted', false] }] },
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

    async remove(channelId: number, occurredAt: Date, sourceVersion: mongo.Long): Promise<boolean> {
        const shouldApply = deletedProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            { channelId },
            [{
                $set: {
                    channelId,
                    teamId: { $ifNull: ['$teamId', null] },
                    projectId: { $ifNull: ['$projectId', null] },
                    name: { $ifNull: ['$name', ''] },
                    type: { $ifNull: ['$type', ''] },
                    viewType: { $ifNull: ['$viewType', ''] },
                    description: { $ifNull: ['$description', null] },
                    isPrivate: { $ifNull: ['$isPrivate', false] },
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
