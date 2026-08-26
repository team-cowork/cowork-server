import { ForbiddenException, Injectable } from '@nestjs/common';
import { ChannelProjectionRepository } from '../repository/channel-projection.repository';
import { TeamMemberProjectionRepository } from '../repository/team-member-projection.repository';

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
    ) {}

    async searchChannels(teamId: number, query: string, userId: number): Promise<ChannelSearchItem[]> {
        if (!(await this.memberRepository.exists(teamId, userId))) {
            throw new ForbiddenException('팀 접근 권한이 없습니다');
        }
        if (query.trim().length === 0) return [];

        const channels = await this.channelRepository.searchByTeamAndName(teamId, query);
        return channels.map((channel) => ({
            id: channel.channelId,
            name: channel.name,
            type: channel.type,
            viewType: channel.viewType,
            description: channel.description,
            isPrivate: channel.isPrivate,
        }));
    }
}
