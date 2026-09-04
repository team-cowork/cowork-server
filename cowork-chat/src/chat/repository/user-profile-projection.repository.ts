import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, mongo } from 'mongoose';
import { UserProfileProjection } from '../schema/user-profile-projection.schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    projectionUpdateApplied,
    projectionTimestampFields,
    projectionSourceVersion,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

export interface UserProfileProjectionView {
    userId: number;
    name: string;
    nickname: string | null;
    githubId: string | null;
}

export interface VersionedUserProfileProjectionView extends UserProfileProjectionView {
    occurredAt: Date;
    sourceVersion: mongo.Long;
}

@Injectable()
export class UserProfileProjectionRepository {
    constructor(@InjectModel(UserProfileProjection.name) private readonly model: Model<UserProfileProjection>) {}

    async findByUserIds(userIds: number[]): Promise<UserProfileProjectionView[]> {
        if (userIds.length === 0) return [];
        return this.model.find({ userId: { $in: userIds }, deleted: { $ne: true } }).lean<UserProfileProjectionView[]>();
    }

    async upsert(profile: VersionedUserProfileProjectionView): Promise<boolean> {
        const shouldApply = activeProjectionCondition(profile.sourceVersion);
        const result = await this.model.updateOne(
            { userId: profile.userId },
            [{
                $set: {
                    userId: profile.userId,
                    name: { $cond: [shouldApply, { $literal: profile.name }, { $ifNull: ['$name', ''] }] },
                    nickname: {
                        $cond: [shouldApply, { $literal: profile.nickname }, { $ifNull: ['$nickname', null] }],
                    },
                    githubId: {
                        $cond: [shouldApply, { $literal: profile.githubId }, { $ifNull: ['$githubId', null] }],
                    },
                    deleted: { $cond: [shouldApply, false, { $ifNull: ['$deleted', false] }] },
                    sourceOccurredAt: {
                        $cond: [shouldApply, profile.occurredAt, { $ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH] }],
                    },
                    sourceVersion: {
                        $cond: [shouldApply, profile.sourceVersion, projectionSourceVersion],
                    },
                    ...projectionTimestampFields(shouldApply, profile.occurredAt),
                },
            }],
            { upsert: true, timestamps: false, updatePipeline: true },
        );
        return projectionUpdateApplied(result);
    }

    async remove(userId: number, occurredAt: Date, sourceVersion: mongo.Long): Promise<boolean> {
        const shouldApply = deletedProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            { userId },
            [{
                $set: {
                    userId,
                    name: { $ifNull: ['$name', ''] },
                    nickname: { $ifNull: ['$nickname', null] },
                    githubId: { $ifNull: ['$githubId', null] },
                    deleted: { $cond: [shouldApply, true, { $ifNull: ['$deleted', false] }] },
                    sourceOccurredAt: {
                        $cond: [shouldApply, occurredAt, { $ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH] }],
                    },
                    sourceVersion: { $cond: [shouldApply, sourceVersion, projectionSourceVersion] },
                    ...projectionTimestampFields(shouldApply, occurredAt),
                },
            }],
            { upsert: true, timestamps: false, updatePipeline: true },
        );
        return projectionUpdateApplied(result);
    }
}
