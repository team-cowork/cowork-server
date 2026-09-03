import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, mongo } from 'mongoose';
import { ChannelRolePolicyProjection } from '../schema/channel-role-policy-projection.schema';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    projectionSourceVersion,
    projectionTimestampFields,
    projectionUpdateApplied,
    PROJECTION_EPOCH,
} from './versioned-projection.util';

export interface ChannelRolePolicyProjectionView {
    teamId: number;
    channelId: number;
    roleId: number;
    messageRead: boolean;
}

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
        messageRead: boolean;
        occurredAt: Date;
        sourceVersion: mongo.Long;
    }): Promise<boolean> {
        const shouldApply = activeProjectionCondition(input.sourceVersion);
        const result = await this.model.updateOne(
            { teamId: input.teamId, channelId: input.channelId, roleId: input.roleId },
            [{
                $set: {
                    teamId: input.teamId,
                    channelId: input.channelId,
                    roleId: input.roleId,
                    messageRead: {
                        $cond: [shouldApply, input.messageRead, { $ifNull: ['$messageRead', null] }],
                    },
                    deleted: { $cond: [shouldApply, false, { $ifNull: ['$deleted', false] }] },
                    sourceOccurredAt: {
                        $cond: [shouldApply, input.occurredAt, { $ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH] }],
                    },
                    sourceVersion: { $cond: [shouldApply, input.sourceVersion, projectionSourceVersion] },
                    ...projectionTimestampFields(shouldApply, input.occurredAt),
                },
            }],
            { upsert: true, timestamps: false },
        );
        return projectionUpdateApplied(result);
    }

    async remove(
        teamId: number,
        channelId: number,
        roleId: number,
        occurredAt: Date,
        sourceVersion: mongo.Long,
    ): Promise<boolean> {
        const shouldApply = deletedProjectionCondition(sourceVersion);
        const result = await this.model.updateOne(
            { teamId, channelId, roleId },
            [{
                $set: {
                    teamId,
                    channelId,
                    roleId,
                    messageRead: { $ifNull: ['$messageRead', null] },
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

    async findByChannelIdsAndRoleIds(
        channelIds: number[],
        roleIds: number[],
    ): Promise<ChannelRolePolicyProjectionView[]> {
        if (channelIds.length === 0 || roleIds.length === 0) return [];
        return this.model.find(
            {
                channelId: { $in: channelIds },
                roleId: { $in: roleIds },
                messageRead: { $type: 'bool' },
                deleted: { $ne: true },
            },
            { _id: 0, teamId: 1, channelId: 1, roleId: 1, messageRead: 1 },
        ).lean<ChannelRolePolicyProjectionView[]>();
    }

    async findChannelIdsByRole(teamId: number, roleId: number): Promise<number[]> {
        return this.model.distinct('channelId', { teamId, roleId, deleted: { $ne: true } });
    }

    async findChannelIdsByTeam(teamId: number): Promise<number[]> {
        return this.model.distinct('channelId', { teamId, deleted: { $ne: true } });
    }
}
