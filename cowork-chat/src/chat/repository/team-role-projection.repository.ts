import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {TeamRoleProjection, TeamRoleAssignmentProjection, TeamRoleMemberTombstone} from '../schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    projectionSourceVersion,
    projectionTimestampFields,
    isProjectionUpdateApplied,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

export type TeamRoleProjectionView = {
    teamId: number;
    roleId: number;
    priority: number;
    permissions?: Record<string, boolean>;
};

export type TeamRoleAssignmentProjectionView = {
    teamId: number;
    accountId: number;
    roleId: number;
};

type VersionedAssignmentProjectionView = {
    sourceVersion: bigint | mongo.Long;
} & TeamRoleAssignmentProjectionView;

type MemberTombstoneProjectionView = {
    teamId: number;
    accountId: number;
    sourceVersion: bigint | mongo.Long;
};

type ProjectionVersion = {
    occurredAt: Date;
    sourceVersion: mongo.Long;
};

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
        permissions: Record<string, boolean>;
    }): Promise<boolean> {
        const applyCondition = activeProjectionCondition(input.sourceVersion);
        const result = await this.roleModel.updateOne(
            {teamId: input.teamId, roleId: input.roleId},
            [{
                $set: {
                    teamId: input.teamId,
                    roleId: input.roleId,
                    name: {$cond: [applyCondition, {$literal: input.name}, {$ifNull: ['$name', '']}]},
                    priority: {$cond: [applyCondition, input.priority, {$ifNull: ['$priority', 0]}]},
                    permissions: {
                        $cond: [applyCondition, {$literal: input.permissions}, {$ifNull: ['$permissions', {}]}],
                    },
                    deleted: {$cond: [applyCondition, false, {$ifNull: ['$deleted', false]}]},
                    sourceOccurredAt: {
                        $cond: [applyCondition, input.occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}],
                    },
                    sourceVersion: {$cond: [applyCondition, input.sourceVersion, projectionSourceVersion]},
                    ...projectionTimestampFields(applyCondition, input.occurredAt),
                },
            }],
            {upsert: true, timestamps: false, updatePipeline: true},
        );
        return isProjectionUpdateApplied(result);
    }

    async deleteRole(
        teamId: number,
        roleId: number,
        occurredAt: Date,
        sourceVersion: mongo.Long,
    ): Promise<boolean> {
        const applyCondition = deletedProjectionCondition(sourceVersion);
        const result = await this.roleModel.updateOne(
            {teamId, roleId},
            [{
                $set: {
                    teamId,
                    roleId,
                    name: {$ifNull: ['$name', '']},
                    priority: {$ifNull: ['$priority', 0]},
                    permissions: {$ifNull: ['$permissions', {}]},
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

    async upsertAssignment(input: ProjectionVersion & {
        teamId: number;
        accountId: number;
        roleId: number;
    }): Promise<boolean> {
        const tombstone = await this.memberTombstoneModel.exists({
            teamId: input.teamId,
            accountId: input.accountId,
            sourceVersion: {$gte: input.sourceVersion.toBigInt()},
        });
        if (tombstone) {
            return false;
        }

        const applyCondition = activeProjectionCondition(input.sourceVersion);
        const result = await this.assignmentModel.updateOne(
            {teamId: input.teamId, accountId: input.accountId, roleId: input.roleId},
            [{
                $set: {
                    teamId: input.teamId,
                    accountId: input.accountId,
                    roleId: input.roleId,
                    deleted: {$cond: [applyCondition, false, {$ifNull: ['$deleted', false]}]},
                    sourceOccurredAt: {
                        $cond: [applyCondition, input.occurredAt, {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]}],
                    },
                    sourceVersion: {$cond: [applyCondition, input.sourceVersion, projectionSourceVersion]},
                    ...projectionTimestampFields(applyCondition, input.occurredAt),
                },
            }],
            {upsert: true, timestamps: false, updatePipeline: true},
        );
        return isProjectionUpdateApplied(result);
    }

    async deleteAssignment(
        teamId: number,
        accountId: number,
        roleId: number,
        occurredAt: Date,
        sourceVersion: mongo.Long,
    ): Promise<boolean> {
        const applyCondition = deletedProjectionCondition(sourceVersion);
        const result = await this.assignmentModel.updateOne(
            {teamId, accountId, roleId},
            [{
                $set: {
                    teamId,
                    accountId,
                    roleId,
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

    async deleteMemberAssignments(
        teamId: number,
        accountId: number,
        occurredAt: Date,
        sourceVersion: mongo.Long,
    ): Promise<boolean> {
        const newerTombstone = await this.memberTombstoneModel.exists({
            teamId,
            accountId,
            sourceVersion: {$gt: sourceVersion.toBigInt()},
        });
        if (newerTombstone) {
            return false;
        }

        const result = await this.memberTombstoneModel.updateOne(
            {teamId, accountId},
            [{
                $set: {
                    teamId,
                    accountId,
                    sourceOccurredAt: {
                        $cond: [
                            {$lte: [{$ifNull: ['$sourceVersion', mongo.Long.ZERO]}, sourceVersion]},
                            occurredAt,
                            {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]},
                        ],
                    },
                    sourceVersion: {
                        $cond: [
                            {$lte: [{$ifNull: ['$sourceVersion', mongo.Long.ZERO]}, sourceVersion]},
                            sourceVersion,
                            {$ifNull: ['$sourceVersion', mongo.Long.ZERO]},
                        ],
                    },
                    createdAt: {$ifNull: ['$createdAt', occurredAt]},
                    updatedAt: {
                        $cond: [
                            {$lte: [{$ifNull: ['$sourceVersion', mongo.Long.ZERO]}, sourceVersion]},
                            occurredAt,
                            {$ifNull: ['$updatedAt', occurredAt]},
                        ],
                    },
                },
            }],
            {upsert: true, timestamps: false, updatePipeline: true},
        );

        const applyCondition = deletedProjectionCondition(sourceVersion);
        const assignmentResult = await this.assignmentModel.updateMany(
            {teamId, accountId},
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
        return isProjectionUpdateApplied(result) || assignmentResult.modifiedCount > 0;
    }

    async findAssignmentsByTeamIdsAndAccountIds(
        teamIds: number[],
        accountIds: number[],
    ): Promise<TeamRoleAssignmentProjectionView[]> {
        if (teamIds.length === 0 || accountIds.length === 0) {
            return [];
        }

        const [assignments, tombstones] = await Promise.all([
            this.assignmentModel.find(
                {teamId: {$in: teamIds}, accountId: {$in: accountIds}, deleted: {$ne: true}},
                {
                    _id: 0, teamId: 1, accountId: 1, roleId: 1, sourceVersion: 1,
                },
            ).lean<VersionedAssignmentProjectionView[]>(),
            this.memberTombstoneModel.find(
                {teamId: {$in: teamIds}, accountId: {$in: accountIds}},
                {
                    _id: 0, teamId: 1, accountId: 1, sourceVersion: 1,
                },
            ).lean<MemberTombstoneProjectionView[]>(),
        ]);
        const tombstoneVersionByMember = new Map(tombstones.map(tombstone => [
            `${tombstone.teamId}:${tombstone.accountId}`,
            this.toBigInt(tombstone.sourceVersion),
        ]));
        return assignments
            .filter(assignment => {
                const tombstoneVersion = tombstoneVersionByMember.get(`${assignment.teamId}:${assignment.accountId}`);
                return tombstoneVersion === undefined || this.toBigInt(assignment.sourceVersion) > tombstoneVersion;
            })
            .map(({teamId, accountId, roleId}) => ({teamId, accountId, roleId}));
    }

    async findRolesByIds(roleIds: number[]): Promise<TeamRoleProjectionView[]> {
        if (roleIds.length === 0) {
            return [];
        }

        return this.roleModel.find(
            {roleId: {$in: roleIds}, deleted: {$ne: true}},
            {
                _id: 0, teamId: 1, roleId: 1, priority: 1, permissions: 1,
            },
        ).lean<TeamRoleProjectionView[]>();
    }

    private toBigInt(value: bigint | mongo.Long): bigint {
        return typeof value === 'bigint' ? value : value.toBigInt();
    }
}
