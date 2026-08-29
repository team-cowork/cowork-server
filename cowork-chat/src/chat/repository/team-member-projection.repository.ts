import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, mongo } from 'mongoose';
import { TeamMemberProjection } from '../schema/team-member-projection.schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    projectionUpdateApplied,
    projectionTimestampFields,
    projectionSourceVersion,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

export interface TeamMemberProjectionView {
    teamId: number;
    userId: number;
    role: string;
    teamName: string;
}

export interface VersionedTeamMemberProjectionView extends TeamMemberProjectionView {
    occurredAt: Date;
    sourceVersion: mongo.Long;
}

@Injectable()
export class TeamMemberProjectionRepository {
    constructor(@InjectModel(TeamMemberProjection.name) private readonly model: Model<TeamMemberProjection>) {}

    async exists(teamId: number, userId: number): Promise<boolean> {
        return (await this.model.exists({ teamId, userId, deleted: { $ne: true } })) !== null;
    }

    async findByTeamIdsAndUserIds(
        teamIds: number[],
        userIds: number[],
    ): Promise<TeamMemberProjectionView[]> {
        if (teamIds.length === 0 || userIds.length === 0) return [];
        return this.model.find(
            { teamId: { $in: teamIds }, userId: { $in: userIds }, deleted: { $ne: true } },
            { _id: 0, teamId: 1, userId: 1, role: 1, teamName: 1 },
        ).lean<TeamMemberProjectionView[]>();
    }

    async upsert(member: VersionedTeamMemberProjectionView): Promise<boolean> {
        const shouldApply = activeProjectionCondition(member.sourceVersion);
        const result = await this.model.updateOne(
            { teamId: member.teamId, userId: member.userId },
            [{
                $set: {
                    teamId: member.teamId,
                    userId: member.userId,
                    role: { $cond: [shouldApply, { $literal: member.role }, { $ifNull: ['$role', ''] }] },
                    teamName: {
                        $cond: [shouldApply, { $literal: member.teamName }, { $ifNull: ['$teamName', ''] }],
                    },
                    deleted: { $cond: [shouldApply, false, { $ifNull: ['$deleted', false] }] },
                    sourceOccurredAt: {
                        $cond: [
                            shouldApply,
                            member.occurredAt,
                            { $ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH] },
                        ],
                    },
                    sourceVersion: {
                        $cond: [shouldApply, member.sourceVersion, projectionSourceVersion],
                    },
                    ...projectionTimestampFields(shouldApply, member.occurredAt),
                },
            }],
            { upsert: true, timestamps: false },
        );
        return projectionUpdateApplied(result);
    }

    async remove(teamId: number, userId: number, occurredAt: Date, sourceVersion: mongo.Long): Promise<boolean> {
        const shouldApply = deletedProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            { teamId, userId },
            [{
                $set: {
                    teamId,
                    userId,
                    role: { $ifNull: ['$role', ''] },
                    teamName: { $ifNull: ['$teamName', ''] },
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
