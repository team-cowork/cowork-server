import { ForbiddenException, NotFoundException } from '@nestjs/common';
import { Types } from 'mongoose';
import { MessageService } from './message.service';
import { MessageRepository } from './message.repository';
import { ConversationRepository } from '../conversation/conversation.repository';
import { BlockService } from '../block/block.service';
import { DmGateway } from '../gateway/dm.gateway';
import { MinioService } from '../storage/minio.service';
import { MessageResponseDto } from './dto/message-response.dto';

const makeObjectId = () => new Types.ObjectId();

const makeConversation = (userIdA: number, userIdB: number) => ({
    _id: makeObjectId(),
    participants: [
        { userId: userIdA, isHidden: false, unreadCount: 0, lastReadMessageId: null },
        { userId: userIdB, isHidden: false, unreadCount: 0, lastReadMessageId: null },
    ],
    lastMessageId: null,
    lastMessageAt: null,
    createdAt: new Date(),
    updatedAt: new Date(),
});

const makeMessage = (conversationId: Types.ObjectId, authorId: number, content = '안녕') => ({
    _id: makeObjectId(),
    conversationId,
    authorId,
    content,
    type: 'TEXT',
    attachments: [],
    isEdited: false,
    clientMessageId: null,
    mentions: [],
    reactions: [],
    createdAt: new Date(),
    updatedAt: new Date(),
});

const mockMessageRepo = () => ({
    findMessages: jest.fn(),
    findById: jest.fn(),
    findByIdAndConversationId: jest.fn(),
    createMessage: jest.fn(),
    deleteById: jest.fn(),
    updateContent: jest.fn(),
    addReaction: jest.fn(),
    removeReaction: jest.fn(),
});

const mockConvRepo = () => ({
    findById: jest.fn(),
    getOtherParticipantId: jest.fn(),
    updateConversationOnMessage: jest.fn(),
    markRead: jest.fn(),
});

const mockMinioService = () => ({
    extractObjectKey: jest.fn(),
    confirmUpload: jest.fn(),
});

const mockBlockService = () => ({
    isBlocked: jest.fn(),
});

const mockGateway = () => ({
    broadcastNewMessage: jest.fn<void, [string, MessageResponseDto, number | null]>(),
    broadcastMessageUpdated: jest.fn(),
    broadcastMessageDeleted: jest.fn(),
    broadcastReactionUpdated: jest.fn(),
    broadcastReadUpdated: jest.fn(),
});

describe('MessageService', () => {
    let service: MessageService;
    let messageRepo: ReturnType<typeof mockMessageRepo>;
    let convRepo: ReturnType<typeof mockConvRepo>;
    let blockService: ReturnType<typeof mockBlockService>;
    let gateway: ReturnType<typeof mockGateway>;
    let minioService: ReturnType<typeof mockMinioService>;

    beforeEach(() => {
        messageRepo = mockMessageRepo();
        convRepo = mockConvRepo();
        blockService = mockBlockService();
        gateway = mockGateway();
        minioService = mockMinioService();
        service = new MessageService(
            messageRepo as unknown as MessageRepository,
            convRepo as unknown as ConversationRepository,
            blockService as unknown as BlockService,
            gateway as unknown as DmGateway,
            minioService as unknown as MinioService,
        );
    });

    describe('getMessages', () => {
        it('대화방이 없으면 NotFoundException', async () => {
            convRepo.findById.mockResolvedValue(null);

            await expect(service.getMessages('conv1', 1)).rejects.toThrow(NotFoundException);
        });

        it('참여자가 아니면 ForbiddenException', async () => {
            const conv = makeConversation(2, 3);
            convRepo.findById.mockResolvedValue(conv);

            await expect(service.getMessages(conv._id.toString(), 1)).rejects.toThrow(ForbiddenException);
        });

        it('메시지 목록을 반환한다', async () => {
            const conv = makeConversation(1, 2);
            const msgs = [makeMessage(conv._id, 1), makeMessage(conv._id, 2)];
            convRepo.findById.mockResolvedValue(conv);
            messageRepo.findMessages.mockResolvedValue(msgs);

            const result = await service.getMessages(conv._id.toString(), 1);

            expect(result).toHaveLength(2);
            expect(messageRepo.findMessages).toHaveBeenCalledWith(conv._id.toString(), undefined);
        });

        it('before 커서 파라미터를 전달한다', async () => {
            const conv = makeConversation(1, 2);
            convRepo.findById.mockResolvedValue(conv);
            messageRepo.findMessages.mockResolvedValue([]);

            const cursor = makeObjectId().toString();
            await service.getMessages(conv._id.toString(), 1, cursor);

            expect(messageRepo.findMessages).toHaveBeenCalledWith(conv._id.toString(), cursor);
        });
    });

    describe('sendMessage', () => {
        it('대화방이 없으면 NotFoundException', async () => {
            convRepo.findById.mockResolvedValue(null);

            await expect(service.sendMessage('conv1', 1, { content: '안녕', type: 'TEXT' } as any)).rejects.toThrow(NotFoundException);
        });

        it('참여자가 아니면 ForbiddenException', async () => {
            const conv = makeConversation(2, 3);
            convRepo.findById.mockResolvedValue(conv);

            await expect(service.sendMessage(conv._id.toString(), 1, { content: '안녕' } as any)).rejects.toThrow(ForbiddenException);
        });

        it('수신자가 발신자를 차단했으면 ForbiddenException', async () => {
            const conv = makeConversation(1, 2);
            convRepo.findById.mockResolvedValue(conv);
            convRepo.getOtherParticipantId.mockReturnValue(2);
            blockService.isBlocked.mockResolvedValue(true);

            await expect(service.sendMessage(conv._id.toString(), 1, { content: '안녕' } as any)).rejects.toThrow(ForbiddenException);
        });

        it('정상적으로 메시지를 전송한다', async () => {
            const conv = makeConversation(1, 2);
            const msg = makeMessage(conv._id, 1);
            convRepo.findById.mockResolvedValue(conv);
            convRepo.getOtherParticipantId.mockReturnValue(2);
            blockService.isBlocked.mockResolvedValue(false);
            messageRepo.createMessage.mockResolvedValue(msg);
            convRepo.updateConversationOnMessage.mockResolvedValue(undefined);

            const result = await service.sendMessage(conv._id.toString(), 1, { content: '안녕', type: 'TEXT' } as any);

            expect(result.authorId).toBe(1);
            expect(result.content).toBe('안녕');
            expect(gateway.broadcastNewMessage).toHaveBeenCalledWith(conv._id.toString(), result, 2);
        });

        it('수신자가 없는 경우 receiverId=null 로 updateConversationOnMessage를 호출한다', async () => {
            const conv = makeConversation(1, 2);
            const msg = makeMessage(conv._id, 1);
            convRepo.findById.mockResolvedValue(conv);
            convRepo.getOtherParticipantId.mockReturnValue(null);
            messageRepo.createMessage.mockResolvedValue(msg);
            convRepo.updateConversationOnMessage.mockResolvedValue(undefined);

            await service.sendMessage(conv._id.toString(), 1, { content: '안녕' } as any);

            expect(convRepo.updateConversationOnMessage).toHaveBeenCalledWith(
                expect.anything(),
                null,
                expect.anything(),
                expect.anything(),
            );
        });
    });

    describe('editMessage', () => {
        it('메시지가 없으면 NotFoundException', async () => {
            messageRepo.findByIdAndConversationId.mockResolvedValue(null);

            await expect(service.editMessage('conv1', 'msg1', 1, '수정')).rejects.toThrow(NotFoundException);
        });

        it('작성자가 아니면 ForbiddenException', async () => {
            const conv = makeConversation(1, 2);
            const msg = makeMessage(conv._id, 2);
            messageRepo.findByIdAndConversationId.mockResolvedValue(msg);

            await expect(service.editMessage(conv._id.toString(), msg._id.toString(), 1, '수정')).rejects.toThrow(ForbiddenException);
        });

        it('메시지를 수정하고 WebSocket 브로드캐스트', async () => {
            const conv = makeConversation(1, 2);
            const msg = makeMessage(conv._id, 1);
            const updated = { ...msg, content: '수정된 내용', isEdited: true };
            messageRepo.findByIdAndConversationId.mockResolvedValue(msg);
            messageRepo.updateContent.mockResolvedValue(updated);

            const result = await service.editMessage(conv._id.toString(), msg._id.toString(), 1, '수정된 내용');

            expect(result.content).toBe('수정된 내용');
            expect(result.isEdited).toBe(true);
            expect(gateway.broadcastMessageUpdated).toHaveBeenCalledWith(conv._id.toString(), result);
        });

        it('updateContent 결과가 null이면 NotFoundException', async () => {
            const conv = makeConversation(1, 2);
            const msg = makeMessage(conv._id, 1);
            messageRepo.findByIdAndConversationId.mockResolvedValue(msg);
            messageRepo.updateContent.mockResolvedValue(null);

            await expect(service.editMessage(conv._id.toString(), msg._id.toString(), 1, '수정')).rejects.toThrow(NotFoundException);
        });
    });

    describe('deleteMessage', () => {
        it('메시지가 없으면 NotFoundException', async () => {
            messageRepo.findByIdAndConversationId.mockResolvedValue(null);

            await expect(service.deleteMessage('conv1', 'msg1', 1)).rejects.toThrow(NotFoundException);
        });

        it('작성자가 아니면 ForbiddenException', async () => {
            const conv = makeConversation(1, 2);
            const msg = makeMessage(conv._id, 2);
            messageRepo.findByIdAndConversationId.mockResolvedValue(msg);

            await expect(service.deleteMessage(conv._id.toString(), msg._id.toString(), 1)).rejects.toThrow(ForbiddenException);
        });

        it('메시지를 삭제하고 WebSocket 브로드캐스트', async () => {
            const conv = makeConversation(1, 2);
            const msg = makeMessage(conv._id, 1);
            messageRepo.findByIdAndConversationId.mockResolvedValue(msg);
            messageRepo.deleteById.mockResolvedValue(undefined);

            await service.deleteMessage(conv._id.toString(), msg._id.toString(), 1);

            expect(messageRepo.deleteById).toHaveBeenCalledWith(msg._id.toString());
            expect(gateway.broadcastMessageDeleted).toHaveBeenCalledWith(conv._id.toString(), msg._id.toString());
        });
    });

    describe('reactMessage', () => {
        it('대화방이 없으면 NotFoundException', async () => {
            convRepo.findById.mockResolvedValue(null);

            await expect(service.reactMessage('conv1', 'msg1', 1, '👍', 'ADD')).rejects.toThrow(NotFoundException);
        });

        it('참여자가 아니면 ForbiddenException', async () => {
            const conv = makeConversation(2, 3);
            convRepo.findById.mockResolvedValue(conv);

            await expect(service.reactMessage(conv._id.toString(), 'msg1', 1, '👍', 'ADD')).rejects.toThrow(ForbiddenException);
        });

        it('메시지가 없으면 NotFoundException (addReaction null)', async () => {
            const conv = makeConversation(1, 2);
            convRepo.findById.mockResolvedValue(conv);
            messageRepo.addReaction.mockResolvedValue(null);

            await expect(service.reactMessage(conv._id.toString(), 'msg1', 1, '👍', 'ADD')).rejects.toThrow(NotFoundException);
        });

        it('ADD 리액션 후 WebSocket 브로드캐스트', async () => {
            const conv = makeConversation(1, 2);
            const msgId = makeObjectId().toString();
            const updatedMsg = {
                ...makeMessage(conv._id, 1),
                reactions: [{ emoji: '👍', userIds: [1] }],
            };
            convRepo.findById.mockResolvedValue(conv);
            messageRepo.addReaction.mockResolvedValue(1);
            messageRepo.findById.mockResolvedValue(updatedMsg);

            await service.reactMessage(conv._id.toString(), msgId, 1, '👍', 'ADD');

            expect(gateway.broadcastReactionUpdated).toHaveBeenCalledWith(
                conv._id.toString(),
                msgId,
                [{ emoji: '👍', count: 1, myReaction: true }],
            );
        });

        it('REMOVE 리액션 후 WebSocket 브로드캐스트', async () => {
            const conv = makeConversation(1, 2);
            const msgId = makeObjectId().toString();
            const updatedMsg = {
                ...makeMessage(conv._id, 1),
                reactions: [],
            };
            convRepo.findById.mockResolvedValue(conv);
            messageRepo.removeReaction.mockResolvedValue(0);
            messageRepo.findById.mockResolvedValue(updatedMsg);

            await service.reactMessage(conv._id.toString(), msgId, 1, '👍', 'REMOVE');

            expect(gateway.broadcastReactionUpdated).toHaveBeenCalledWith(conv._id.toString(), msgId, []);
        });
    });

    describe('markRead', () => {
        it('대화방이 없으면 NotFoundException', async () => {
            convRepo.findById.mockResolvedValue(null);

            await expect(service.markRead('conv1', 1, 'msg1')).rejects.toThrow(NotFoundException);
        });

        it('참여자가 아니면 ForbiddenException', async () => {
            const conv = makeConversation(2, 3);
            convRepo.findById.mockResolvedValue(conv);

            await expect(service.markRead(conv._id.toString(), 1, 'msg1')).rejects.toThrow(ForbiddenException);
        });

        it('읽음 처리 후 WebSocket 브로드캐스트', async () => {
            const conv = makeConversation(1, 2);
            const msgId = makeObjectId().toString();
            convRepo.findById.mockResolvedValue(conv);
            convRepo.markRead.mockResolvedValue(undefined);

            await service.markRead(conv._id.toString(), 1, msgId);

            expect(convRepo.markRead).toHaveBeenCalledWith(conv._id.toString(), 1, msgId);
            expect(gateway.broadcastReadUpdated).toHaveBeenCalledWith(conv._id.toString(), 1, msgId);
        });
    });
});
