import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {TeamMemberProjection} from '../schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    isProjectionUpdateApplied,
    projectionTimestampFields,
    projectionSourceVersion,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

export type TeamMemberProjectionView = {
    teamId: number;
    userId: number;
    role: string;
    teamName: string;
    sourceVersion?: bigint | mongo.Long;
};

export type VersionedTeamMemberProjectionView = {
    nickname: string | null;
    occurredAt: Date;
    sourceVersion: mongo.Long;
} & TeamMemberProjectionView;

@Injectable()
export class TeamMemberProjectionRepository {
    constructor(@InjectModel(TeamMemberProjection.name) private readonly model: Model<TeamMemberProjection>) {}

    async findByTeamIds(teamIds: number[]): Promise<TeamMemberProjectionView[]> {
        return this.model.find({teamId: {$in: teamIds}, deleted: {$ne: true}}).lean<TeamMemberProjectionView[]>();
    }

    async exists(teamId: number, userId: number): Promise<boolean> {
        return (await this.model.exists({teamId, userId, deleted: {$ne: true}})) !== null;
    }

    async findByTeamIdsAndUserIds(
        teamIds: number[],
        userIds: number[],
    ): Promise<TeamMemberProjectionView[]> {
        if (teamIds.length === 0 || userIds.length === 0) {
            return [];
        }

        return this.model.find(
            {teamId: {$in: teamIds}, userId: {$in: userIds}, deleted: {$ne: true}},
            {
                _id: 0, teamId: 1, userId: 1, role: 1, teamName: 1, sourceVersion: 1,
            },
        ).lean<TeamMemberProjectionView[]>();
    }

    async findNicknames(teamId: number, userIds: number[]): Promise<Map<number, string>> {
        if (userIds.length === 0) {
            return new Map();
        }

        const members = await this.model.find(
            {
                teamId, userId: {$in: userIds}, deleted: {$ne: true}, nickname: {$nin: [null, '']},
            },
            {_id: 0, userId: 1, nickname: 1},
        ).lean<Array<{userId: number; nickname: string}>>();
        return new Map(members.map(member => [member.userId, member.nickname]));
    }

    async upsert(member: VersionedTeamMemberProjectionView): Promise<boolean> {
        const applyCondition = activeProjectionCondition(member.sourceVersion);
        const result = await this.model.updateOne(
            {teamId: member.teamId, userId: member.userId},
            [{
                $set: {
                    teamId: member.teamId,
                    userId: member.userId,
                    role: {$cond: [applyCondition, {$literal: member.role}, {$ifNull: ['$role', '']}]},
                    teamName: {
                        $cond: [applyCondition, {$literal: member.teamName}, {$ifNull: ['$teamName', '']}],
                    },
                    nickname: {
                        $cond: [applyCondition, {$literal: member.nickname}, {$ifNull: ['$nickname', null]}],
                    },
                    deleted: {$cond: [applyCondition, false, {$ifNull: ['$deleted', false]}]},
                    sourceOccurredAt: {
                        $cond: [
                            applyCondition,
                            member.occurredAt,
                            {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]},
                        ],
                    },
                    sourceVersion: {
                        $cond: [applyCondition, member.sourceVersion, projectionSourceVersion],
                    },
                    ...projectionTimestampFields(applyCondition, member.occurredAt),
                },
            }],
            {upsert: true, timestamps: false, updatePipeline: true},
        );
        return isProjectionUpdateApplied(result);
    }

    async remove(teamId: number, userId: number, occurredAt: Date, sourceVersion: mongo.Long): Promise<boolean> {
        const applyCondition = deletedProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            {teamId, userId},
            [{
                $set: {
                    teamId,
                    userId,
                    role: {$ifNull: ['$role', '']},
                    teamName: {$ifNull: ['$teamName', '']},
                    nickname: {$ifNull: ['$nickname', null]},
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
