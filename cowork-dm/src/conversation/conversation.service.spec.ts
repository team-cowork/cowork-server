import { BadRequestException, ForbiddenException, NotFoundException } from '@nestjs/common';
import { ConversationService } from './conversation.service';
import { ConversationRepository } from './conversation.repository';
import { Types } from 'mongoose';

const makeConversation = (userIdA: number, userIdB: number) => ({
    _id: new Types.ObjectId(),
    participants: [
        { userId: userIdA, isHidden: false, unreadCount: 0, lastReadMessageId: null },
        { userId: userIdB, isHidden: false, unreadCount: 0, lastReadMessageId: null },
    ],
    lastMessageId: null,
    lastMessageAt: null,
    createdAt: new Date(),
    updatedAt: new Date(),
});

const mockRepo = () => ({
    findOrCreate: jest.fn(),
    findVisibleByUserId: jest.fn(),
    findById: jest.fn(),
    hideForUser: jest.fn(),
});

describe('ConversationService', () => {
    let service: ConversationService;
    let repo: ReturnType<typeof mockRepo>;

    beforeEach(() => {
        repo = mockRepo();
        service = new ConversationService(repo as unknown as ConversationRepository);
    });

    describe('openConversation', () => {
        it('자기 자신과 DM 시도 시 BadRequestException', async () => {
            await expect(service.openConversation(1, 1)).rejects.toThrow(BadRequestException);
        });

        it('정상적으로 대화방을 개설하거나 기존 반환', async () => {
            const conv = makeConversation(1, 2);
            repo.findOrCreate.mockResolvedValue(conv);

            const result = await service.openConversation(1, 2);

            expect(repo.findOrCreate).toHaveBeenCalledWith(1, 2);
            expect(result.id).toBe(conv._id.toString());
        });
    });

    describe('getMyConversations', () => {
        it('사용자의 대화방 목록을 반환', async () => {
            const convs = [makeConversation(1, 2), makeConversation(1, 3)];
            repo.findVisibleByUserId.mockResolvedValue(convs);

            const result = await service.getMyConversations(1);

            expect(result).toHaveLength(2);
            expect(repo.findVisibleByUserId).toHaveBeenCalledWith(1);
        });

        it('대화방이 없으면 빈 배열 반환', async () => {
            repo.findVisibleByUserId.mockResolvedValue([]);

            const result = await service.getMyConversations(1);

            expect(result).toEqual([]);
        });
    });

    describe('hideConversation', () => {
        it('대화방이 없으면 NotFoundException', async () => {
            repo.findById.mockResolvedValue(null);

            await expect(service.hideConversation('conv1', 1)).rejects.toThrow(NotFoundException);
        });

        it('참여자가 아니면 ForbiddenException', async () => {
            const conv = makeConversation(2, 3);
            repo.findById.mockResolvedValue(conv);

            await expect(service.hideConversation(conv._id.toString(), 1)).rejects.toThrow(ForbiddenException);
        });

        it('참여자가 대화방을 숨길 수 있다', async () => {
            const conv = makeConversation(1, 2);
            repo.findById.mockResolvedValue(conv);
            repo.hideForUser.mockResolvedValue(conv);

            await expect(service.hideConversation(conv._id.toString(), 1)).resolves.toBeUndefined();
            expect(repo.hideForUser).toHaveBeenCalledWith(conv._id.toString(), 1);
        });
    });

    describe('getConversationOrThrow', () => {
        it('대화방이 없으면 NotFoundException', async () => {
            repo.findById.mockResolvedValue(null);

            await expect(service.getConversationOrThrow('conv1', 1)).rejects.toThrow(NotFoundException);
        });

        it('참여자가 아니면 ForbiddenException', async () => {
            const conv = makeConversation(2, 3);
            repo.findById.mockResolvedValue(conv);

            await expect(service.getConversationOrThrow(conv._id.toString(), 1)).rejects.toThrow(ForbiddenException);
        });

        it('참여자면 대화방 반환', async () => {
            const conv = makeConversation(1, 2);
            repo.findById.mockResolvedValue(conv);

            const result = await service.getConversationOrThrow(conv._id.toString(), 1);

            expect(result).toBe(conv);
        });
    });
});
