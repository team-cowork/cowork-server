import {Injectable} from '@nestjs/common';
import {InjectModel} from '@nestjs/mongoose';
import {Model, Types} from 'mongoose';
import {DmParticipant} from '../schema';

@Injectable()
export class DmParticipantRepository {
    constructor(
        @InjectModel(DmParticipant.name) private readonly memberModel: Model<DmParticipant>,
    ) {}

    async exists(channelId: number, userId: number): Promise<boolean> {
        const member = await this.memberModel.exists({
            channelId, userId, channelType: 'DM', deleted: {$ne: true},
        });
        return member !== null;
    }

    async findByChannelId(channelId: number): Promise<DmParticipant[]> {
        return this.memberModel.find({channelId, channelType: 'DM', deleted: {$ne: true}}).lean();
    }

    async findByChannelIds(channelIds: number[]): Promise<Map<number, DmParticipant[]>> {
        if (channelIds.length === 0) {
            return new Map();
        }

        const members = await this.memberModel
            .find({channelId: {$in: channelIds}, channelType: 'DM', deleted: {$ne: true}})
            .lean();
        const result = new Map<number, DmParticipant[]>();
        for (const member of members) {
            const list = result.get(member.channelId);
            if (list) {
                list.push(member);
            } else {
                result.set(member.channelId, [member]);
            }
        }

        return result;
    }

    async findByChannelIdsAndUserIds(
        channelIds: number[],
        userIds: number[],
    ): Promise<Map<number, DmParticipant[]>> {
        if (channelIds.length === 0 || userIds.length === 0) {
            return new Map();
        }

        const members = await this.memberModel
            .find({
                channelId: {$in: channelIds},
                userId: {$in: userIds},
                channelType: 'DM', deleted: {$ne: true},
            })
            .lean();
        const result = new Map<number, DmParticipant[]>();
        for (const member of members) {
            const list = result.get(member.channelId);
            if (list) {
                list.push(member);
            } else {
                result.set(member.channelId, [member]);
            }
        }

        return result;
    }

    async findMembership(
        channelId: number,
        userId: number,
    ): Promise<{teamId: number | null; channelType: string} | null> {
        const member = await this.memberModel
            .findOne(
                {
                    channelId, userId, channelType: 'DM', deleted: {$ne: true},
                },
                {teamId: 1, channelType: 1},
            )
            .lean();
        if (!member) {
            return null;
        }

        return {
            teamId: member.teamId ?? null,
            channelType: member.channelType ?? 'TEXT',
        };
    }

    async findDmMemberships(
        userId: number,
    ): Promise<Array<{channelId: number; lastReadMessageId: Types.ObjectId | null}>> {
        const memberships = await this.memberModel
            .find(
                {
                    userId, channelType: 'DM', isHidden: {$ne: true}, deleted: {$ne: true},
                },
                {channelId: 1, lastReadMessageId: 1},
            )
            .lean();
        return memberships.map(m => ({
            channelId: m.channelId,
            lastReadMessageId: (m.lastReadMessageId as Types.ObjectId | null | undefined) ?? null,
        }));
    }

    async findOtherDmMembers(channelIds: number[], userId: number): Promise<Map<number, number>> {
        if (channelIds.length === 0) {
            return new Map();
        }

        const others = await this.memberModel
            .find(
                {
                    channelId: {$in: channelIds}, userId: {$ne: userId}, channelType: 'DM', deleted: {$ne: true},
                },
                {channelId: 1, userId: 1},
            )
            .lean();
        return new Map(others.map(m => [m.channelId, m.userId]));
    }

    async setHidden(channelId: number, userId: number, isHidden: boolean): Promise<boolean> {
        const result = await this.memberModel.updateOne(
            {
                channelId, userId, channelType: 'DM', deleted: {$ne: true},
            },
            {$set: {isHidden}},
        );
        return result.matchedCount > 0;
    }
}
