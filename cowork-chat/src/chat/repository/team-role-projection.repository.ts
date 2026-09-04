import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, mongo } from 'mongoose';
import { TeamRoleProjection } from '../schema/team-role-projection.schema';
import { TeamRoleAssignmentProjection } from '../schema/team-role-assignment-projection.schema';
import { TeamRoleMemberTombstone } from '../schema/team-role-member-tombstone.schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    projectionSourceVersion,
    projectionTimestampFields,
    projectionUpdateApplied,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

export interface TeamRoleProjectionView {
    teamId: number;
    roleId: number;
    priority: number;
}

export interface TeamRoleAssignmentProjectionView {
    teamId: number;
    accountId: number;
    roleId: number;
}

interface VersionedAssignmentProjectionView extends TeamRoleAssignmentProjectionView {
    sourceVersion: bigint | mongo.Long;
}

interface MemberTombstoneProjectionView {
    teamId: number;
    accountId: number;
    sourceVersion: bigint | mongo.Long;
}

interface ProjectionVersion {
    occurredAt: Date;
    sourceVersion: mongo.Long;
}

@Injectable()
export class TeamRoleProjectionRepository {
    constructor(
        @InjectModel(TeamRoleProjection.name) private readonly roleModel: Model<TeamRoleProjection>,
        @InjectModel(TeamRoleAssignmentProjection.name)
        private readonly assignmentModel: Model<TeamRoleAssignmentProjection>,
        @InjectModel(TeamRoleMemberTombstone.name)
        private readonly memberTombstoneModel: Model<TeamRoleMemberTombstone>,
    ) {}

    async upsertRole(input: ProjectionVersion & {
        teamId: number;
        roleId: number;
        name: string;
        priority: number;
        permissions: string[];
    }): Promise<boolean> {
        const shouldApply = activeProjectionCondition(input.sourceVersion);
        const result = await this.roleModel.updateOne(
            { teamId: input.teamId, roleId: input.roleId },
            [{
                $set: {
                    teamId: input.teamId,
                    roleId: input.roleId,
                    name: { $cond: [shouldApply, { $literal: input.name }, { $ifNull: ['$name', ''] }] },
                    priority: { $cond: [shouldApply, input.priority, { $ifNull: ['$priority', 0] }] },
                    permissions: {
                        $cond: [shouldApply, { $literal: input.permissions }, { $ifNull: ['$permissions', []] }],
                    },
                    deleted: { $cond: [shouldApply, false, { $ifNull: ['$deleted', false] }] },
                    sourceOccurredAt: {
                        $cond: [shouldApply, input.occurredAt, { $ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH] }],
                    },
                    sourceVersion: { $cond: [shouldApply, input.sourceVersion, projectionSourceVersion] },
                    ...projectionTimestampFields(shouldApply, input.occurredAt),
                },
            }],
            { upsert: true, timestamps: false, updatePipeline: true },
        );
        return projectionUpdateApplied(result);
    }

    async deleteRole(
        teamId: number,
        roleId: number,
        occurredAt: Date,
        sourceVersion: mongo.Long,
    ): Promise<boolean> {
        const shouldApply = deletedProjectionCondition(sourceVersion);
        const result = await this.roleModel.updateOne(
            { teamId, roleId },
            [{
                $set: {
                    teamId,
                    roleId,
                    name: { $ifNull: ['$name', ''] },
                    priority: { $ifNull: ['$priority', 0] },
                    permissions: { $ifNull: ['$permissions', []] },
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

    async upsertAssignment(input: ProjectionVersion & {
        teamId: number;
        accountId: number;
        roleId: number;
    }): Promise<boolean> {
        const tombstone = await this.memberTombstoneModel.exists({
            teamId: input.teamId,
            accountId: input.accountId,
            sourceVersion: { $gte: input.sourceVersion.toBigInt() },
        });
        if (tombstone) return false;

        const shouldApply = activeProjectionCondition(input.sourceVersion);
        const result = await this.assignmentModel.updateOne(
            { teamId: input.teamId, accountId: input.accountId, roleId: input.roleId },
            [{
                $set: {
                    teamId: input.teamId,
                    accountId: input.accountId,
                    roleId: input.roleId,
                    deleted: { $cond: [shouldApply, false, { $ifNull: ['$deleted', false] }] },
                    sourceOccurredAt: {
                        $cond: [shouldApply, input.occurredAt, { $ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH] }],
                    },
                    sourceVersion: { $cond: [shouldApply, input.sourceVersion, projectionSourceVersion] },
                    ...projectionTimestampFields(shouldApply, input.occurredAt),
                },
            }],
            { upsert: true, timestamps: false, updatePipeline: true },
        );
        return projectionUpdateApplied(result);
    }

    async deleteAssignment(
        teamId: number,
        accountId: number,
        roleId: number,
        occurredAt: Date,
        sourceVersion: mongo.Long,
    ): Promise<boolean> {
        const shouldApply = deletedProjectionCondition(sourceVersion);
        const result = await this.assignmentModel.updateOne(
            { teamId, accountId, roleId },
            [{
                $set: {
                    teamId,
                    accountId,
                    roleId,
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

    async deleteMemberAssignments(
        teamId: number,
        accountId: number,
        occurredAt: Date,
        sourceVersion: mongo.Long,
    ): Promise<boolean> {
        const newerTombstone = await this.memberTombstoneModel.exists({
            teamId,
            accountId,
            sourceVersion: { $gt: sourceVersion.toBigInt() },
        });
        if (newerTombstone) return false;

        const result = await this.memberTombstoneModel.updateOne(
            { teamId, accountId },
            [{
                $set: {
                    teamId,
                    accountId,
                    sourceOccurredAt: {
                        $cond: [
                            { $lte: [{ $ifNull: ['$sourceVersion', mongo.Long.ZERO] }, sourceVersion] },
                            occurredAt,
                            { $ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH] },
                        ],
                    },
                    sourceVersion: {
                        $cond: [
                            { $lte: [{ $ifNull: ['$sourceVersion', mongo.Long.ZERO] }, sourceVersion] },
                            sourceVersion,
                            { $ifNull: ['$sourceVersion', mongo.Long.ZERO] },
                        ],
                    },
                    createdAt: { $ifNull: ['$createdAt', occurredAt] },
                    updatedAt: {
                        $cond: [
                            { $lte: [{ $ifNull: ['$sourceVersion', mongo.Long.ZERO] }, sourceVersion] },
                            occurredAt,
                            { $ifNull: ['$updatedAt', occurredAt] },
                        ],
                    },
                },
            }],
            { upsert: true, timestamps: false, updatePipeline: true },
        );

        const shouldDelete = deletedProjectionCondition(sourceVersion);
        const assignmentResult = await this.assignmentModel.updateMany(
            { teamId, accountId },
            [{
                $set: {
                    deleted: { $cond: [shouldDelete, true, { $ifNull: ['$deleted', false] }] },
                    sourceOccurredAt: {
                        $cond: [shouldDelete, occurredAt, { $ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH] }],
                    },
                    sourceVersion: { $cond: [shouldDelete, sourceVersion, projectionSourceVersion] },
                    ...projectionTimestampFields(shouldDelete, occurredAt),
                },
            }],
            { timestamps: false, updatePipeline: true },
        );
        return projectionUpdateApplied(result) || assignmentResult.modifiedCount > 0;
    }

    async findAssignmentsByTeamIdsAndAccountIds(
        teamIds: number[],
        accountIds: number[],
    ): Promise<TeamRoleAssignmentProjectionView[]> {
        if (teamIds.length === 0 || accountIds.length === 0) return [];
        const [assignments, tombstones] = await Promise.all([
            this.assignmentModel.find(
                { teamId: { $in: teamIds }, accountId: { $in: accountIds }, deleted: { $ne: true } },
                { _id: 0, teamId: 1, accountId: 1, roleId: 1, sourceVersion: 1 },
            ).lean<VersionedAssignmentProjectionView[]>(),
            this.memberTombstoneModel.find(
                { teamId: { $in: teamIds }, accountId: { $in: accountIds } },
                { _id: 0, teamId: 1, accountId: 1, sourceVersion: 1 },
            ).lean<MemberTombstoneProjectionView[]>(),
        ]);
        const tombstoneVersionByMember = new Map(tombstones.map((tombstone) => [
            `${tombstone.teamId}:${tombstone.accountId}`,
            this.toBigInt(tombstone.sourceVersion),
        ]));
        return assignments
            .filter((assignment) => {
                const tombstoneVersion = tombstoneVersionByMember.get(`${assignment.teamId}:${assignment.accountId}`);
                return tombstoneVersion === undefined || this.toBigInt(assignment.sourceVersion) > tombstoneVersion;
            })
            .map(({ teamId, accountId, roleId }) => ({ teamId, accountId, roleId }));
    }

    async findRolesByIds(roleIds: number[]): Promise<TeamRoleProjectionView[]> {
        if (roleIds.length === 0) return [];
        return this.roleModel.find(
            { roleId: { $in: roleIds }, deleted: { $ne: true } },
            { _id: 0, teamId: 1, roleId: 1, priority: 1 },
        ).lean<TeamRoleProjectionView[]>();
    }

    private toBigInt(value: bigint | mongo.Long): bigint {
        return typeof value === 'bigint' ? value : value.toBigInt();
    }
}
