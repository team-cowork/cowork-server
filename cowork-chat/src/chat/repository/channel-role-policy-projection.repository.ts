import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, mongo} from 'mongoose';
import {ChannelRolePolicyProjection} from '../schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    projectionSourceVersion,
    projectionTimestampFields,
    isProjectionUpdateApplied,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

export type ChannelRolePolicyPermissionsView = {
    teamId: number;
    roleId: number;
    permissions: Record<string, boolean>;
};

export type ChannelRolePolicyProjectionView = {
    teamId: number;
    channelId: number;
    roleId: number;
    permissions: Record<string, boolean>;
};

@Injectable()
export class ChannelRolePolicyProjectionRepository {
    constructor(
        @InjectModel(ChannelRolePolicyProjection.name)
        private readonly model: Model<ChannelRolePolicyProjection>,
    ) {}

    async upsert(input: {
        teamId: number;
        channelId: number;
        roleId: number;
        permissions: Record<string, boolean>;
        occurredAt: Date;
        sourceVersion: mongo.Long;
    }): Promise<boolean> {
        const applyCondition = activeProjectionCondition(input.sourceVersion);
        const result = await this.model.updateOne(
            {teamId: input.teamId, channelId: input.channelId, roleId: input.roleId},
            [{
                $set: {
                    teamId: input.teamId,
                    channelId: input.channelId,
                    roleId: input.roleId,
                    permissions: {
                        $cond: [applyCondition, {$literal: input.permissions}, {$ifNull: ['$permissions', null]}],
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

    async remove(
        teamId: number,
        channelId: number,
        roleId: number,
        occurredAt: Date,
        sourceVersion: mongo.Long,
    ): Promise<boolean> {
        const applyCondition = deletedProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            {teamId, channelId, roleId},
            [{
                $set: {
                    teamId,
                    channelId,
                    roleId,
                    permissions: {$ifNull: ['$permissions', null]},
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

    async findByChannelIdsAndRoleIds(
        channelIds: number[],
        roleIds: number[],
    ): Promise<ChannelRolePolicyProjectionView[]> {
        if (channelIds.length === 0 || roleIds.length === 0) {
            return [];
        }

        return this.model.find(
            {
                channelId: {$in: channelIds},
                roleId: {$in: roleIds},
                deleted: {$ne: true},
            },
            {
                _id: 0, teamId: 1, channelId: 1, roleId: 1, permissions: 1,
            },
        ).lean<ChannelRolePolicyProjectionView[]>();
    }

    async findPermissionsByChannelIdAndRoleIds(
        channelId: number,
        roleIds: number[],
    ): Promise<ChannelRolePolicyPermissionsView[]> {
        if (roleIds.length === 0) {
            return [];
        }

        return this.model.find(
            {
                channelId,
                roleId: {$in: roleIds},
                permissions: {$type: 'object'},
                deleted: {$ne: true},
            },
            {
                _id: 0, teamId: 1, roleId: 1, permissions: 1,
            },
        ).lean<ChannelRolePolicyPermissionsView[]>();
    }

    async findChannelIdsByRole(teamId: number, roleId: number): Promise<number[]> {
        return this.model.distinct('channelId', {teamId, roleId, deleted: {$ne: true}});
    }

    async findChannelIdsByTeam(teamId: number): Promise<number[]> {
        return this.model.distinct('channelId', {teamId, deleted: {$ne: true}});
    }
}
