import { ForbiddenException, Injectable } from '@nestjs/common';
import { ChannelMemberRepository } from '../repository/channel-member.repository';
import { ChannelProjectionRepository } from '../repository/channel-projection.repository';
import { TeamMemberProjectionRepository } from '../repository/team-member-projection.repository';
import { ChannelMessageReadAccessService } from './channel-message-read-access.service';

export interface ChannelSearchItem {
    id: number;
    name: string;
    type: string;
    viewType: string;
    description: string | null;
    isPrivate: boolean;
}

/** Kafka로 동기화된 로컬 채널 projection 검색기. */
@Injectable()
export class ChannelSearchClient {
    constructor(
        private readonly channelRepository: ChannelProjectionRepository,
        private readonly memberRepository: TeamMemberProjectionRepository,
        private readonly channelMemberRepository: ChannelMemberRepository,
        private readonly accessService: ChannelMessageReadAccessService,
    ) {}

    async searchChannels(teamId: number, query: string, userId: number): Promise<ChannelSearchItem[]> {
        if (!(await this.memberRepository.exists(teamId, userId))) {
            throw new ForbiddenException('팀 접근 권한이 없습니다');
        }
        if (query.trim().length === 0) return [];

        const memberships = await this.channelMemberRepository.findMembersByTeam(teamId, userId);
        const memberChannelIds = memberships.map((membership) => membership.channelId);
        const channels = await this.channelRepository.searchVisibleByTeamAndName(teamId, query, memberChannelIds);
        const visibleChannelIds = new Set(await this.accessService.filterVisibleChannelIds(
            teamId,
            userId,
            channels.map((channel) => channel.channelId),
        ));
        return channels.filter((channel) => visibleChannelIds.has(channel.channelId)).map((channel) => ({
            id: channel.channelId,
            name: channel.name,
            type: channel.type,
            viewType: channel.viewType,
            description: channel.description,
            isPrivate: channel.isPrivate,
        }));
    }
}
