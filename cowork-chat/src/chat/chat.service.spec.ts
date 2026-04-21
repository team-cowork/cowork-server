import { Test, TestingModule } from '@nestjs/testing';
import { getModelToken } from '@nestjs/mongoose';
import { ForbiddenException, NotFoundException } from '@nestjs/common';
import { Types } from 'mongoose';
import { ChatService } from './chat.service';
import { Message } from './schema/message.schema';
import { ChannelMember } from './schema/channel-member.schema';

const mockMessageId = new Types.ObjectId().toString();

const makeMockMessage = (overrides = {}) => ({
    _id: new Types.ObjectId(mockMessageId),
    channelId: 1,
    authorId: 42,
    content: '안녕하세요',
    isEdited: false,
    editHistory: [] as { content: string; editedAt: Date }[],
    save: jest.fn().mockResolvedValue({ content: '수정됨', isEdited: true }),
    ...overrides,
});

const mockAggregate = jest.fn();

const mockMessageModel = {
    aggregate: mockAggregate,
    findById: jest.fn(),
    deleteOne: jest.fn(),
};


const mockMemberModel = {
    exists: jest.fn(),
};

describe('ChatService', () => {
    let service: ChatService;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            providers: [
                ChatService,
                { provide: getModelToken(Message.name), useValue: mockMessageModel },
                { provide: getModelToken(ChannelMember.name), useValue: mockMemberModel },
            ],
        }).compile();

        service = module.get<ChatService>(ChatService);
        jest.clearAllMocks();
    });

    describe('isMember', () => {
        it('채널 멤버이면 true를 반환한다', async () => {
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            await expect(service.isMember(1, 42)).resolves.toBe(true);
        });

        it('채널 멤버가 아니면 false를 반환한다', async () => {
            mockMemberModel.exists.mockResolvedValue(null);
            await expect(service.isMember(1, 99)).resolves.toBe(false);
        });
    });

    describe('checkMembership', () => {
        it('멤버이면 예외 없이 통과한다', async () => {
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            await expect(service.checkMembership(1, 42)).resolves.toBeUndefined();
        });

        it('멤버가 아니면 ForbiddenException을 던진다', async () => {
            mockMemberModel.exists.mockResolvedValue(null);
            await expect(service.checkMembership(1, 99)).rejects.toThrow(ForbiddenException);
        });
    });

    describe('getMessages', () => {
        it('cursor 없이 aggregate를 실행하고 channelId 조건만 포함한다', async () => {
            mockAggregate.mockResolvedValue([]);

            await service.getMessages(1);

            const pipeline = mockAggregate.mock.calls[0][0];
            expect(pipeline[0].$match).toEqual({ channelId: 1 });
            expect(pipeline[1].$sort).toEqual({ _id: -1 });
            expect(pipeline[2].$limit).toBe(100);
        });

        it('before cursor가 있으면 _id 조건이 추가된다', async () => {
            mockAggregate.mockResolvedValue([]);

            await service.getMessages(1, mockMessageId);

            const pipeline = mockAggregate.mock.calls[0][0];
            expect(pipeline[0].$match).toEqual({
                channelId: 1,
                _id: { $lt: new Types.ObjectId(mockMessageId) },
            });
        });

        it('답글에는 $lookup으로 원본 메시지(mentionedMessage)가 embed된다', async () => {
            const parentId = new Types.ObjectId();
            mockAggregate.mockResolvedValue([
                { _id: new Types.ObjectId(), content: '답글', parentMessageId: parentId, mentionedMessage: { _id: parentId, content: '원본', authorId: 1 } },
                { _id: new Types.ObjectId(), content: '일반 메시지', parentMessageId: null, mentionedMessage: null },
            ]);

            const result = await service.getMessages(1);

            expect(result[0].mentionedMessage).toBeDefined();
            expect(result[0].mentionedMessage?.content).toBe('원본');
            expect(result[1].mentionedMessage).toBeNull();
        });
    });

    describe('editMessage', () => {
        it('본인 메시지를 수정하면 editHistory에 이전 내용이 저장된다', async () => {
            const msg = makeMockMessage();
            mockMessageModel.findById.mockResolvedValue(msg);

            await service.editMessage(mockMessageId, 42, { content: '수정됨' }, 'ROLE_USER');

            expect(msg.editHistory).toHaveLength(1);
            expect(msg.editHistory[0].content).toBe('안녕하세요');
            expect(msg.content).toBe('수정됨');
            expect(msg.isEdited).toBe(true);
            expect(msg.save).toHaveBeenCalled();
        });

        it('메시지가 없으면 NotFoundException을 던진다', async () => {
            mockMessageModel.findById.mockResolvedValue(null);
            await expect(
                service.editMessage(mockMessageId, 42, { content: '수정됨' }, 'ROLE_USER'),
            ).rejects.toThrow(NotFoundException);
        });

        it('다른 사람의 메시지를 수정하면 ForbiddenException을 던진다', async () => {
            mockMessageModel.findById.mockResolvedValue(makeMockMessage({ authorId: 100 }));
            await expect(
                service.editMessage(mockMessageId, 42, { content: '수정됨' }, 'ROLE_USER'),
            ).rejects.toThrow(ForbiddenException);
        });

        it('ADMIN은 다른 사람의 메시지도 수정할 수 있다', async () => {
            const msg = makeMockMessage({ authorId: 100 });
            mockMessageModel.findById.mockResolvedValue(msg);

            await service.editMessage(mockMessageId, 42, { content: '관리자 수정' }, 'ROLE_ADMIN');

            expect(msg.save).toHaveBeenCalled();
        });
    });

    describe('deleteMessage', () => {
        it('본인 메시지를 삭제한다', async () => {
            mockMessageModel.findById.mockResolvedValue(makeMockMessage());
            mockMessageModel.deleteOne.mockResolvedValue({ deletedCount: 1 });

            const result = await service.deleteMessage(mockMessageId, 42, 'ROLE_USER');

            expect(mockMessageModel.deleteOne).toHaveBeenCalledWith({ _id: mockMessageId });
            expect(result.messageId).toBe(mockMessageId);
        });

        it('메시지가 없으면 NotFoundException을 던진다', async () => {
            mockMessageModel.findById.mockResolvedValue(null);
            await expect(
                service.deleteMessage(mockMessageId, 42, 'ROLE_USER'),
            ).rejects.toThrow(NotFoundException);
        });

        it('다른 사람의 메시지를 삭제하면 ForbiddenException을 던진다', async () => {
            mockMessageModel.findById.mockResolvedValue(makeMockMessage({ authorId: 100 }));
            await expect(
                service.deleteMessage(mockMessageId, 42, 'ROLE_USER'),
            ).rejects.toThrow(ForbiddenException);
        });

        it('ADMIN은 다른 사람의 메시지도 삭제할 수 있다', async () => {
            mockMessageModel.findById.mockResolvedValue(makeMockMessage({ authorId: 100 }));
            mockMessageModel.deleteOne.mockResolvedValue({ deletedCount: 1 });

            await expect(
                service.deleteMessage(mockMessageId, 42, 'ROLE_ADMIN'),
            ).resolves.toBeDefined();
        });
    });
});
