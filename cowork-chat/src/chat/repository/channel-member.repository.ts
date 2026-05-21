import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model } from 'mongoose';
import { ChannelMember } from '../schema/channel-member.schema';

@Injectable()
export class ChannelMemberRepository {
    constructor(
        @InjectModel(ChannelMember.name) private readonly memberModel: Model<ChannelMember>,
    ) {}

    async exists(channelId: number, userId: number): Promise<boolean> {
        const member = await this.memberModel.exists({ channelId, userId });
        return member !== null;
    }

    async findTeamIdByChannelAndUser(channelId: number, userId: number): Promise<number | null> {
        const member = await this.memberModel.findOne({ channelId, userId }, { teamId: 1 });
        return member?.teamId ?? null;
    }

    async findChannelIdsByUser(userId: number): Promise<number[]> {
        const memberships = await this.memberModel.find({ userId }, { channelId: 1 }).lean();
        return memberships.map((membership) => membership.channelId);
    }

    findByChannelId(channelId: number): Promise<ChannelMember[]> {
        return this.memberModel.find({ channelId }).lean() as Promise<ChannelMember[]>;
    }
}
