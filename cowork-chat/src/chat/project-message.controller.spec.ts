import { Test, TestingModule } from '@nestjs/testing';
import { ForbiddenException } from '@nestjs/common';
import { getModelToken } from '@nestjs/mongoose';
import { ProjectMessageController } from './project-message.controller';
import { ElasticsearchService } from '../search/elasticsearch.service';
import { ProjectClient } from './service/project.client';
import { ChannelMember } from './schema/channel-member.schema';

const userId = 42;

const mockMemberModel = {
    find: jest.fn(),
};

const mockElasticsearchService = {
    searchMessages: jest.fn(),
};

const mockProjectClient = {
    isMember: jest.fn(),
};

describe('ProjectMessageController', () => {
    let controller: ProjectMessageController;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            controllers: [ProjectMessageController],
            providers: [
                { provide: getModelToken(ChannelMember.name), useValue: mockMemberModel },
                { provide: ElasticsearchService, useValue: mockElasticsearchService },
                { provide: ProjectClient, useValue: mockProjectClient },
            ],
        }).compile();

        controller = module.get<ProjectMessageController>(ProjectMessageController);
        jest.clearAllMocks();
    });

    describe('searchMessages', () => {
        const memberships = [{ channelId: 2 }, { channelId: 3 }];
        const searchResult = {
            hits: [
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
            nextCursor: null,
        };

        it('프로젝트 멤버이면 채널 멤버십 기반으로 ES 검색 결과를 반환한다', async () => {
            mockProjectClient.isMember.mockResolvedValue(true);
            mockMemberModel.find.mockReturnValue({ lean: () => Promise.resolve(memberships) });
            mockElasticsearchService.searchMessages.mockResolvedValue(searchResult);

            const result = await controller.searchMessages(5, { q: '안녕', limit: 50 } as any, userId);

            expect(mockProjectClient.isMember).toHaveBeenCalledWith(5, 42);
            expect(mockElasticsearchService.searchMessages).toHaveBeenCalledWith(
                expect.objectContaining({
                    projectId: 5,
                    accessibleChannelIds: [2, 3],
                    q: '안녕',
                    limit: 50,
                }),
            );
            expect(result.messages).toHaveLength(1);
            expect(result.nextCursor).toBeNull();
        });

        it('프로젝트 멤버가 아니면 ForbiddenException을 던진다', async () => {
            mockProjectClient.isMember.mockResolvedValue(false);

            await expect(
                controller.searchMessages(5, { q: '안녕' } as any, userId),
            ).rejects.toThrow(ForbiddenException);

            expect(mockMemberModel.find).not.toHaveBeenCalled();
            expect(mockElasticsearchService.searchMessages).not.toHaveBeenCalled();
        });

        it('channelId 필터가 있고 해당 채널에 접근 가능하면 그 채널로만 검색한다', async () => {
            mockProjectClient.isMember.mockResolvedValue(true);
            mockMemberModel.find.mockReturnValue({ lean: () => Promise.resolve(memberships) });
            mockElasticsearchService.searchMessages.mockResolvedValue({ hits: [], nextCursor: null });

            await controller.searchMessages(5, { q: '안녕', channelId: 2 } as any, userId);

            expect(mockElasticsearchService.searchMessages).toHaveBeenCalledWith(
                expect.objectContaining({ accessibleChannelIds: [2] }),
            );
        });

        it('channelId 필터가 있지만 해당 채널 멤버가 아니면 ForbiddenException을 던진다', async () => {
            mockProjectClient.isMember.mockResolvedValue(true);
            mockMemberModel.find.mockReturnValue({ lean: () => Promise.resolve(memberships) });

            await expect(
                controller.searchMessages(5, { q: '안녕', channelId: 99 } as any, userId),
            ).rejects.toThrow(ForbiddenException);

            expect(mockElasticsearchService.searchMessages).not.toHaveBeenCalled();
        });

        it('limit 기본값은 50이다', async () => {
            mockProjectClient.isMember.mockResolvedValue(true);
            mockMemberModel.find.mockReturnValue({ lean: () => Promise.resolve(memberships) });
            mockElasticsearchService.searchMessages.mockResolvedValue({ hits: [], nextCursor: null });

            await controller.searchMessages(5, { q: '안녕' } as any, userId);

            expect(mockElasticsearchService.searchMessages).toHaveBeenCalledWith(
                expect.objectContaining({ limit: 50 }),
            );
        });

        it('nextCursor가 있으면 응답에 포함된다', async () => {
            mockProjectClient.isMember.mockResolvedValue(true);
            mockMemberModel.find.mockReturnValue({ lean: () => Promise.resolve(memberships) });
            mockElasticsearchService.searchMessages.mockResolvedValue({
                hits: [],
                nextCursor: 'next-msg-id',
            });

            const result = await controller.searchMessages(5, { q: '안녕' } as any, userId);

            expect(result.nextCursor).toBe('next-msg-id');
        });

        it('채널 멤버십이 없으면 accessibleChannelIds가 빈 배열로 전달된다', async () => {
            mockProjectClient.isMember.mockResolvedValue(true);
            mockMemberModel.find.mockReturnValue({ lean: () => Promise.resolve([]) });
            mockElasticsearchService.searchMessages.mockResolvedValue({ hits: [], nextCursor: null });

            await controller.searchMessages(5, { q: '안녕' } as any, userId);

            expect(mockElasticsearchService.searchMessages).toHaveBeenCalledWith(
                expect.objectContaining({ accessibleChannelIds: [] }),
            );
        });
    });
});
