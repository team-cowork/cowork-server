import { ForbiddenException } from '@nestjs/common';
import { ChannelProjectionRepository } from '../repository/channel-projection.repository';
import { TeamMemberProjectionRepository } from '../repository/team-member-projection.repository';
import { ChannelSearchClient } from './channel-search.client';

describe('ChannelSearchClient', () => {
    const channelRepository = {
        searchByTeamAndName: jest.fn(),
    };
    const memberRepository = {
        exists: jest.fn(),
    };
    const client = new ChannelSearchClient(
        channelRepository as unknown as ChannelProjectionRepository,
        memberRepository as unknown as TeamMemberProjectionRepository,
    );

    beforeEach(() => jest.clearAllMocks());

    it('팀 멤버에게 Kafka projection의 채널 검색 결과를 반환한다', async () => {
        memberRepository.exists.mockResolvedValue(true);
        channelRepository.searchByTeamAndName.mockResolvedValue([
            {
                channelId: 3,
                name: '배포 알림',
                type: 'TEXT',
                viewType: 'CHAT',
                description: 'release',
                isPrivate: false,
            },
        ]);

        await expect(client.searchChannels(10, '배포', 42)).resolves.toEqual([
            {
                id: 3,
                name: '배포 알림',
                type: 'TEXT',
                viewType: 'CHAT',
                description: 'release',
                isPrivate: false,
            },
        ]);
        expect(channelRepository.searchByTeamAndName).toHaveBeenCalledWith(10, '배포');
    });

    it('팀 멤버가 아니면 projection 검색 결과를 노출하지 않는다', async () => {
        memberRepository.exists.mockResolvedValue(false);

        await expect(client.searchChannels(10, '배포', 99)).rejects.toBeInstanceOf(ForbiddenException);
        expect(channelRepository.searchByTeamAndName).not.toHaveBeenCalled();
    });

    it('공백 검색어는 권한 검증 후 빈 목록을 반환한다', async () => {
        memberRepository.exists.mockResolvedValue(true);

        await expect(client.searchChannels(10, '   ', 42)).resolves.toEqual([]);
        expect(channelRepository.searchByTeamAndName).not.toHaveBeenCalled();
    });
});
