import { ForbiddenException } from '@nestjs/common';
import { ChannelMemberRepository } from '../repository/channel-member.repository';
import { ChannelProjectionRepository } from '../repository/channel-projection.repository';
import { TeamMemberProjectionRepository } from '../repository/team-member-projection.repository';
import { ChannelSearchClient } from './channel-search.client';
import { ChannelMessageReadAccessService } from './channel-message-read-access.service';

describe('ChannelSearchClient', () => {
    const channelRepository = {
        searchVisibleByTeamAndName: jest.fn(),
    };
    const teamMemberRepository = {
        exists: jest.fn(),
    };
    const channelMemberRepository = {
        findMembersByTeam: jest.fn(),
    };
    const accessService = {
        filterVisibleChannelIds: jest.fn(),
    };
    const client = new ChannelSearchClient(
        channelRepository as unknown as ChannelProjectionRepository,
        teamMemberRepository as unknown as TeamMemberProjectionRepository,
        channelMemberRepository as unknown as ChannelMemberRepository,
        accessService as unknown as ChannelMessageReadAccessService,
    );

    beforeEach(() => jest.clearAllMocks());

    it('팀 멤버에게 Kafka projection의 채널 검색 결과를 반환한다', async () => {
        teamMemberRepository.exists.mockResolvedValue(true);
        channelMemberRepository.findMembersByTeam.mockResolvedValue([{ channelId: 3, lastReadMessageId: null }]);
        channelRepository.searchVisibleByTeamAndName.mockResolvedValue([
            {
                channelId: 3,
                name: '배포 알림',
                type: 'TEXT',
                viewType: 'CHAT',
                description: 'release',
                isPrivate: false,
            },
        ]);
        accessService.filterVisibleChannelIds.mockResolvedValue([3]);

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
        expect(channelMemberRepository.findMembersByTeam).toHaveBeenCalledWith(10, 42);
        expect(channelRepository.searchVisibleByTeamAndName).toHaveBeenCalledWith(10, '배포', [3]);
        expect(accessService.filterVisibleChannelIds).toHaveBeenCalledWith(10, 42, [3]);
    });

    it('미가입 비공개 채널은 repository 가시성 필터 결과에 포함되지 않는다', async () => {
        teamMemberRepository.exists.mockResolvedValue(true);
        channelMemberRepository.findMembersByTeam.mockResolvedValue([]);
        channelRepository.searchVisibleByTeamAndName.mockResolvedValue([]);
        accessService.filterVisibleChannelIds.mockResolvedValue([]);

        await expect(client.searchChannels(10, 'secret', 42)).resolves.toEqual([]);

        expect(channelRepository.searchVisibleByTeamAndName).toHaveBeenCalledWith(10, 'secret', []);
    });

    it('팀 멤버가 아니면 projection 검색 결과를 노출하지 않는다', async () => {
        teamMemberRepository.exists.mockResolvedValue(false);

        await expect(client.searchChannels(10, '배포', 99)).rejects.toBeInstanceOf(ForbiddenException);
        expect(channelMemberRepository.findMembersByTeam).not.toHaveBeenCalled();
        expect(channelRepository.searchVisibleByTeamAndName).not.toHaveBeenCalled();
    });

    it('공백 검색어는 권한 검증 후 빈 목록을 반환한다', async () => {
        teamMemberRepository.exists.mockResolvedValue(true);

        await expect(client.searchChannels(10, '   ', 42)).resolves.toEqual([]);
        expect(channelMemberRepository.findMembersByTeam).not.toHaveBeenCalled();
        expect(channelRepository.searchVisibleByTeamAndName).not.toHaveBeenCalled();
    });
});
