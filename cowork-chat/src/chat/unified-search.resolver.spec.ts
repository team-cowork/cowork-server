import { Request } from 'express';
import { ChatService } from './chat.service';
import { ChannelSearchClient } from './service/channel-search.client';
import { UnifiedSearchResolver } from './unified-search.resolver';

describe('UnifiedSearchResolver', () => {
    const chatService = {
        searchTeamMessages: jest.fn(),
    };
    const channelSearchClient = {
        searchChannels: jest.fn(),
    };
    const resolver = new UnifiedSearchResolver(
        chatService as unknown as ChatService,
        channelSearchClient as unknown as ChannelSearchClient,
    );

    beforeEach(() => jest.clearAllMocks());

    it('메시지와 채널 검색에 같은 팀과 요청자 접근 범위를 전달한다', async () => {
        chatService.searchTeamMessages.mockResolvedValue({ messages: [{ messageId: 'message-1' }], nextCursor: null });
        channelSearchClient.searchChannels.mockResolvedValue([{ id: 3, name: '배포' }]);
        const request = { headers: { 'x-user-id': '42' } } as unknown as Request;

        await expect(resolver.unifiedSearch(request, 10, '배포')).resolves.toEqual({
            messages: [{ messageId: 'message-1' }],
            messageNextCursor: null,
            channels: [{ id: 3, name: '배포' }],
        });

        expect(chatService.searchTeamMessages).toHaveBeenCalledWith(
            10,
            expect.objectContaining({ teamId: 10, q: '배포' }),
            { userId: 42 },
        );
        expect(channelSearchClient.searchChannels).toHaveBeenCalledWith(10, '배포', 42);
    });
});
