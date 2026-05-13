import { Test, TestingModule } from '@nestjs/testing';
import { ProjectMessageController } from './project-message.controller';
import { ChatService } from './chat.service';

const userId = 42;

const mockChatService = {
    searchProjectMessages: jest.fn(),
};

describe('ProjectMessageController', () => {
    let controller: ProjectMessageController;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            controllers: [ProjectMessageController],
            providers: [
                { provide: ChatService, useValue: mockChatService },
            ],
        }).compile();

        controller = module.get<ProjectMessageController>(ProjectMessageController);
        jest.clearAllMocks();
    });

    describe('searchMessages', () => {
        it('프로젝트 메시지 검색을 ChatService에 위임한다', async () => {
            const query = { q: '안녕', channelId: 2, limit: 50 } as any;
            const expected = {
                messages: [
                    {
                        messageId: 'msg-1',
                        channelId: 2,
                        authorId: 42,
                        content: '안녕하세요',
                        highlight: ['<em>안녕</em>하세요'],
                        type: 'TEXT',
                        hasAttachments: false,
                        isPinned: false,
                        createdAt: '2026-05-11T10:00:00.000Z',
                    },
                ],
                nextCursor: 'cursor',
            };
            mockChatService.searchProjectMessages.mockResolvedValue(expected);

            const result = await controller.searchMessages(5, query, userId);

            expect(mockChatService.searchProjectMessages).toHaveBeenCalledWith(5, query, 42);
            expect(result).toEqual(expected);
        });

        it('서비스 예외를 그대로 전파한다', async () => {
            const error = new Error('search failed');
            mockChatService.searchProjectMessages.mockRejectedValue(error);

            await expect(
                controller.searchMessages(5, { q: '안녕' } as any, userId),
            ).rejects.toThrow(error);
        });
    });
});
