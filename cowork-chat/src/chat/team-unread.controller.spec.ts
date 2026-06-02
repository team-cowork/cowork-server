import { Test, TestingModule } from '@nestjs/testing';
import { ForbiddenException } from '@nestjs/common';
import { TeamUnreadController } from './team-unread.controller';
import { ChatService } from './chat.service';
import { UnreadCountItemDto } from './dto/unread-count-response.dto';

const mockChatService = {
    getTeamUnread: jest.fn(),
};

const userId = 42;

describe('TeamUnreadController', () => {
    let controller: TeamUnreadController;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            controllers: [TeamUnreadController],
            providers: [
                { provide: ChatService, useValue: mockChatService },
            ],
        }).compile();

        controller = module.get<TeamUnreadController>(TeamUnreadController);
        jest.clearAllMocks();
    });

    describe('getTeamUnread', () => {
        it('팀 내 채널별 미읽 카운트를 반환한다', async () => {
            const expected: UnreadCountItemDto[] = [
                { channelId: 1, unreadCount: 3 },
                { channelId: 2, unreadCount: 0 },
            ];
            mockChatService.getTeamUnread.mockResolvedValue(expected);

            const result = await controller.getTeamUnread(10, userId);

            expect(mockChatService.getTeamUnread).toHaveBeenCalledWith(10, 42);
            expect(result).toEqual(expected);
        });

        it('가입한 채널이 없으면 빈 배열을 반환한다', async () => {
            mockChatService.getTeamUnread.mockResolvedValue([]);

            const result = await controller.getTeamUnread(10, userId);

            expect(result).toEqual([]);
        });

        it('서비스 오류가 전파된다', async () => {
            mockChatService.getTeamUnread.mockRejectedValue(new ForbiddenException());

            await expect(
                controller.getTeamUnread(10, userId),
            ).rejects.toThrow(ForbiddenException);
        });
    });
});
