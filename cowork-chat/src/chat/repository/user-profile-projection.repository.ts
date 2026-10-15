import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {UserProfileProjection} from '../schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    isProjectionUpdateApplied,
    projectionTimestampFields,
    projectionSourceVersion,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

export type UserProfileProjectionView = {
    userId: number;
    name: string;
    nickname: string | null;
    githubId: string | null;
};

export type VersionedUserProfileProjectionView = {
    occurredAt: Date;
    sourceVersion: mongo.Long;
} & UserProfileProjectionView;

@Injectable()
export class UserProfileProjectionRepository {
    constructor(@InjectModel(UserProfileProjection.name) private readonly model: Model<UserProfileProjection>) {}

    async findByUserIds(userIds: number[]): Promise<UserProfileProjectionView[]> {
        return userIds.length === 0 ? [] : this.model.find({userId: {$in: userIds}, deleted: {$ne: true}}).lean<UserProfileProjectionView[]>();
    }

    async upsert(profile: VersionedUserProfileProjectionView): Promise<boolean> {
        const applyCondition = activeProjectionCondition(profile.sourceVersion);
        const result = await this.model.updateOne(
            {userId: profile.userId},
            [{
                $set: {
                    userId: profile.userId,
                    name: {$cond: [applyCondition, {$literal: profile.name}, {$ifNull: ['$name', '']}]},
                    nickname: {
                        $cond: [applyCondition, {$literal: profile.nickname}, {$ifNull: ['$nickname', null]}],
                    },
                    githubId: {
                        $cond: [applyCondition, {$literal: profile.githubId}, {$ifNull: ['$githubId', null]}],
                    },
                    deleted: {$cond: [applyCondition, false, {$ifNull: ['$deleted', false]}]},
                    sourceOccurredAt: {
                        $cond: [applyCondition, profile.occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}],
                    },
                    sourceVersion: {
                        $cond: [applyCondition, profile.sourceVersion, projectionSourceVersion],
                    },
                    ...projectionTimestampFields(applyCondition, profile.occurredAt),
                },
            }],
            {upsert: true, timestamps: false, updatePipeline: true},
        );
        return isProjectionUpdateApplied(result);
    }

    async remove(userId: number, occurredAt: Date, sourceVersion: mongo.Long): Promise<boolean> {
        const applyCondition = deletedProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            {userId},
            [{
                $set: {
                    userId,
                    name: {$ifNull: ['$name', '']},
                    nickname: {$ifNull: ['$nickname', null]},
                    githubId: {$ifNull: ['$githubId', null]},
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
