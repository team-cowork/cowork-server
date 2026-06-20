import { Test, TestingModule } from '@nestjs/testing';
import { BadRequestException, ForbiddenException, NotFoundException } from '@nestjs/common';
import { Types } from 'mongoose';
import { ChatService } from './chat.service';
import { ChatGateway } from './chat.gateway';
import { ElasticsearchService } from '../search/elasticsearch.service';
import { MinioService } from '../storage/minio.service';
import { ChatMessageProducer } from './kafka/chat-message.producer';
import { GithubIssueProducer } from './kafka/github-issue.producer';
import { ProjectClient } from './service/project.client';
import { ChannelClient } from './service/channel.client';
import { UserClient } from './service/user.client';
import { MessageRepository } from './repository/message.repository';
import { ChannelMemberRepository } from './repository/channel-member.repository';
import { BlockService } from '../block/block.service';

const mockMessageId = new Types.ObjectId().toString();
const mockEmit = jest.fn();
const mockTo = jest.fn(() => ({
    emit: mockEmit,
}));

const makeMockMessage = (overrides = {}) => ({
    _id: new Types.ObjectId(mockMessageId),
    channelId: 1,
    authorId: 42,
    content: '안녕하세요',
    isEdited: false,
    editHistory: [] as { content: string; editedAt: Date }[],
    updatedAt: new Date('2026-05-12T00:00:00.000Z'),
    save: jest.fn().mockResolvedValue({
        content: '수정됨',
        isEdited: true,
        updatedAt: new Date('2026-05-12T00:00:00.000Z'),
    }),
    ...overrides,
});

const mockMessageRepository = {
    findMessages: jest.fn(),
    findFileAttachments: jest.fn(),
    findById: jest.fn(),
    deleteById: jest.fn(),
    createSystemMessage: jest.fn(),
    countUnread: jest.fn(),
    countUnreadForChannels: jest.fn(),
    findLastMessages: jest.fn(),
};


const mockChannelMemberRepository = {
    exists: jest.fn(),
    findTeamIdByChannelAndUser: jest.fn(),
    findChannelIdsByUser: jest.fn(),
    updateLastRead: jest.fn(),
    findMembersByTeam: jest.fn(),
    findMembership: jest.fn(),
    findByChannelId: jest.fn(),
    findDmMemberships: jest.fn(),
    findOtherDmMembers: jest.fn(),
    setHidden: jest.fn(),
};

const mockBlockService = {
    isBlocked: jest.fn(),
};

const mockElasticsearchService = {
    updateMessage: jest.fn().mockResolvedValue(undefined),
    deleteMessage: jest.fn().mockResolvedValue(undefined),
    searchMessages: jest.fn(),
};

const mockMinioService = {
    createPresignedUpload: jest.fn(),
    confirmUpload: jest.fn(),
    assertOwnedAttachmentUrl: jest.fn(),
};

const mockChatMessageProducer = {
    sendMessage: jest.fn(),
};

const mockGithubIssueProducer = {
    send: jest.fn(),
};

const mockProjectClient = {
    getGithubRepoInfo: jest.fn(),
    isMember: jest.fn(),
};

const mockChannelClient = {
    getChannel: jest.fn(),
};

const mockUserClient = {
    getDisplayNames: jest.fn(),
};

const mockChatGateway = {
    server: {
        to: mockTo,
    },
};

describe('ChatService', () => {
    let service: ChatService;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            providers: [
                ChatService,
                { provide: MessageRepository, useValue: mockMessageRepository },
                { provide: ChannelMemberRepository, useValue: mockChannelMemberRepository },
                { provide: ElasticsearchService, useValue: mockElasticsearchService },
                { provide: MinioService, useValue: mockMinioService },
                { provide: ChatMessageProducer, useValue: mockChatMessageProducer },
                { provide: GithubIssueProducer, useValue: mockGithubIssueProducer },
                { provide: ProjectClient, useValue: mockProjectClient },
                { provide: ChannelClient, useValue: mockChannelClient },
                { provide: UserClient, useValue: mockUserClient },
                { provide: BlockService, useValue: mockBlockService },
                { provide: ChatGateway, useValue: mockChatGateway },
            ],
        }).compile();

        service = module.get<ChatService>(ChatService);
        jest.clearAllMocks();
    });

    describe('isMember', () => {
        it('채널 멤버이면 true를 반환한다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            await expect(service.isMember(1, 42)).resolves.toBe(true);
        });

        it('채널 멤버가 아니면 false를 반환한다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(false);
            await expect(service.isMember(1, 99)).resolves.toBe(false);
        });
    });

    describe('checkMembership', () => {
        it('멤버이면 예외 없이 통과한다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            await expect(service.checkMembership(1, 42)).resolves.toBeUndefined();
        });

        it('멤버가 아니면 ForbiddenException을 던진다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(false);
            await expect(service.checkMembership(1, 99)).rejects.toThrow(ForbiddenException);
        });
    });

    describe('sendMessage', () => {
        const ctx = { channelId: 1, userId: 42, userRole: 'USER' };

        it('일반 채널 메시지는 DTO 그대로 producer에 위임한다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue({ teamId: 100, channelType: 'TEXT' });

            await service.sendMessage(ctx, { teamId: 100, content: 'hi' } as any);

            expect(mockChatMessageProducer.sendMessage).toHaveBeenCalledWith(1, { teamId: 100, content: 'hi' }, 42, 'USER');
            expect(mockBlockService.isBlocked).not.toHaveBeenCalled();
        });

        it('팀 채널 메시지는 클라이언트 teamId를 멤버십의 teamId로 덮어쓴다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue({ teamId: 100, channelType: 'TEXT' });

            await service.sendMessage(ctx, { teamId: 999, content: 'hi' } as any);

            expect(mockChatMessageProducer.sendMessage).toHaveBeenCalledWith(
                1,
                expect.objectContaining({ teamId: 100, content: 'hi' }),
                42,
                'USER',
            );
        });

        it('채널 멤버가 아니면 ForbiddenException을 던진다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue(null);

            await expect(service.sendMessage(ctx, { content: 'hi' } as any)).rejects.toThrow(ForbiddenException);
            expect(mockChatMessageProducer.sendMessage).not.toHaveBeenCalled();
        });

        it('첨부파일이 있으면 각 url의 소유권을 검증한다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue({ teamId: 100, channelType: 'TEXT' });
            mockMinioService.assertOwnedAttachmentUrl.mockReturnValue(undefined);
            const attachments = [
                { name: 'a.png', url: 'http://minio/chat-files/1/42/uuid.png', size: 1, mimeType: 'image/png' },
            ];

            await service.sendMessage(ctx, { content: 'hi', attachments } as any);

            expect(mockMinioService.assertOwnedAttachmentUrl).toHaveBeenCalledWith(attachments[0].url, 1, 42);
            expect(mockChatMessageProducer.sendMessage).toHaveBeenCalled();
        });

        it('소유하지 않은 첨부파일 url이면 검증에서 던진 예외가 전파되고 발행되지 않는다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue({ teamId: 100, channelType: 'TEXT' });
            mockMinioService.assertOwnedAttachmentUrl.mockImplementation(() => {
                throw new BadRequestException('첨부파일 URL이 유효하지 않습니다');
            });
            const attachments = [
                { name: 'a.png', url: 'http://minio/chat-files/999/7/uuid.png', size: 1, mimeType: 'image/png' },
            ];

            await expect(service.sendMessage(ctx, { content: 'hi', attachments } as any)).rejects.toThrow(BadRequestException);
            expect(mockChatMessageProducer.sendMessage).not.toHaveBeenCalled();
        });

        it('DM 채널에서 수신자가 발신자를 차단했으면 ForbiddenException을 던진다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue({ teamId: null, channelType: 'DM' });
            mockChannelMemberRepository.findByChannelId.mockResolvedValue([{ userId: 42 }, { userId: 7 }]);
            mockBlockService.isBlocked.mockResolvedValue(true);

            await expect(service.sendMessage(ctx, { content: 'hi' } as any)).rejects.toThrow(ForbiddenException);
            expect(mockBlockService.isBlocked).toHaveBeenCalledWith(7, 42);
            expect(mockChatMessageProducer.sendMessage).not.toHaveBeenCalled();
        });

        it('DM 채널 메시지는 teamId/projectId를 null로 강제하고 수신자 숨김을 해제한다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue({ teamId: null, channelType: 'DM' });
            mockChannelMemberRepository.findByChannelId.mockResolvedValue([{ userId: 42 }, { userId: 7 }]);
            mockBlockService.isBlocked.mockResolvedValue(false);

            await service.sendMessage(ctx, { teamId: 999, projectId: 5, content: 'hi' } as any);

            expect(mockChannelMemberRepository.setHidden).toHaveBeenCalledWith(1, 7, false);
            expect(mockChatMessageProducer.sendMessage).toHaveBeenCalledWith(
                1,
                expect.objectContaining({ teamId: null, projectId: null, content: 'hi' }),
                42,
                'USER',
            );
        });
    });

    describe('getMyDms', () => {
        it('숨기지 않은 DM을 마지막 메시지 시각 내림차순으로 반환한다', async () => {
            mockChannelMemberRepository.findDmMemberships.mockResolvedValue([
                { channelId: 1, lastReadMessageId: null },
                { channelId: 2, lastReadMessageId: null },
            ]);
            mockChannelMemberRepository.findOtherDmMembers.mockResolvedValue(new Map([[1, 7], [2, 9]]));
            mockMessageRepository.findLastMessages.mockResolvedValue(new Map([
                [1, { messageId: 'a', authorId: 7, content: '예전', type: 'TEXT', createdAt: new Date('2026-01-01') }],
                [2, { messageId: 'b', authorId: 9, content: '최신', type: 'TEXT', createdAt: new Date('2026-06-01') }],
            ]));
            mockMessageRepository.countUnreadForChannels.mockResolvedValue(new Map([[1, 3]]));

            const result = await service.getMyDms(42);

            expect(result.map((dm) => dm.channelId)).toEqual([2, 1]);
            expect(result[1].unreadCount).toBe(3);
            expect(result[0].otherUserId).toBe(9);
        });

        it('DM이 없으면 빈 배열을 반환한다', async () => {
            mockChannelMemberRepository.findDmMemberships.mockResolvedValue([]);
            await expect(service.getMyDms(42)).resolves.toEqual([]);
        });
    });

    describe('hideDm', () => {
        it('DM 멤버이면 숨김 처리한다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue({ teamId: null, channelType: 'DM' });
            mockChannelMemberRepository.setHidden.mockResolvedValue(true);

            await service.hideDm(1, 42);

            expect(mockChannelMemberRepository.setHidden).toHaveBeenCalledWith(1, 42, true);
        });

        it('DM 채널이 아니면 ForbiddenException을 던진다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue({ teamId: 100, channelType: 'TEXT' });

            await expect(service.hideDm(1, 42)).rejects.toThrow(ForbiddenException);
        });
    });

    describe('getMessages', () => {
        it('메시지 조회를 레포지토리에 위임한다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findMessages.mockResolvedValue([]);

            await service.getMessages({ channelId: 1, userId: 42 });

            expect(mockMessageRepository.findMessages).toHaveBeenCalledWith(1, undefined, undefined);
        });

        it('before cursor를 레포지토리에 전달한다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findMessages.mockResolvedValue([]);

            await service.getMessages({ channelId: 1, userId: 42 }, mockMessageId);

            expect(mockMessageRepository.findMessages).toHaveBeenCalledWith(1, mockMessageId, undefined);
        });

        it('레포지토리 조회 결과를 그대로 반환한다', async () => {
            const parentId = new Types.ObjectId();
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findMessages.mockResolvedValue([
                { _id: new Types.ObjectId(), content: '답글', parentMessageId: parentId, mentionedMessage: { _id: parentId, content: '원본', authorId: 1 } },
                { _id: new Types.ObjectId(), content: '일반 메시지', parentMessageId: null, mentionedMessage: null },
            ]);

            const result = await service.getMessages({ channelId: 1, userId: 42 });

            expect(result[0].mentionedMessage).toBeDefined();
            expect(result[0].mentionedMessage?.content).toBe('원본');
            expect(result[1].mentionedMessage).toBeNull();
        });

        it('채널 멤버가 아니면 ForbiddenException을 던진다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(false);

            await expect(service.getMessages({ channelId: 1, userId: 99 })).rejects.toThrow(ForbiddenException);
        });
    });

    describe('getFileList', () => {
        it('FILE_SHARE 채널의 첨부파일을 파일 단위로 반환한다', async () => {
            const createdAt = new Date('2026-05-12T02:03:04.000Z');
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockChannelClient.getChannel.mockResolvedValue({ id: 1, viewType: 'FILE_SHARE' });
            mockMessageRepository.findFileAttachments.mockResolvedValue({
                items: [
                    {
                        messageId: '665f00000000000000000001',
                        uploaderId: 42,
                        uploadedAt: createdAt.toISOString(),
                        fileName: 'report.pdf',
                        fileUrl: 'http://localhost:9000/cowork-bucket/chat-files/1/42/report.pdf',
                        fileSize: 2048,
                        mimeType: 'application/pdf',
                        attachmentIndex: 0,
                    },
                ],
                nextCursor: null,
            });
            mockUserClient.getDisplayNames.mockResolvedValue(new Map([[42, '홍길동']]));

            const result = await service.getFileList({ channelId: 1, userId: 42 }, {});

            expect(mockChannelClient.getChannel).toHaveBeenCalledWith(1, 42);
            expect(mockMessageRepository.findFileAttachments).toHaveBeenCalledWith(1, undefined, 20);
            expect(mockUserClient.getDisplayNames).toHaveBeenCalledWith([42]);
            expect(result.files).toEqual([
                {
                    messageId: '665f00000000000000000001',
                    fileName: 'report.pdf',
                    fileSize: 2048,
                    fileUrl: 'http://localhost:9000/cowork-bucket/chat-files/1/42/report.pdf',
                    mimeType: 'application/pdf',
                    uploaderId: 42,
                    uploaderName: '홍길동',
                    uploadedAt: createdAt.toISOString(),
                },
            ]);
            expect(result.nextCursor).toBeNull();
        });

        it('FILE_SHARE가 아니면 BadRequestException을 던진다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockChannelClient.getChannel.mockResolvedValue({ id: 1, viewType: 'TEXT' });

            await expect(service.getFileList({ channelId: 1, userId: 42 }, {})).rejects.toThrow('FILE_SHARE 채널에서만 파일 목록을 조회할 수 있습니다');
            expect(mockMessageRepository.findFileAttachments).not.toHaveBeenCalled();
        });

        it('before 커서를 레포지토리에 전달한다', async () => {
            const before = 'cursor';
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockChannelClient.getChannel.mockResolvedValue({ id: 1, viewType: 'FILE_SHARE' });
            mockMessageRepository.findFileAttachments.mockResolvedValue({ items: [], nextCursor: null });

            await service.getFileList({ channelId: 1, userId: 42 }, { before, limit: 20 });

            expect(mockMessageRepository.findFileAttachments).toHaveBeenCalledWith(1, before, 20);
        });
    });

    describe('editMessage', () => {
        const ctx = (overrides = {}) => ({ channelId: 1, messageId: mockMessageId, userId: 42, userRole: 'MEMBER', ...overrides });

        it('본인 메시지를 수정하면 editHistory에 이전 내용이 저장된다', async () => {
            const msg = makeMockMessage();
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(msg);

            await service.editMessage(ctx(), { content: '수정됨' });

            expect(msg.editHistory).toHaveLength(1);
            expect(msg.editHistory[0].content).toBe('안녕하세요');
            expect(msg.content).toBe('수정됨');
            expect(msg.isEdited).toBe(true);
            expect(msg.save).toHaveBeenCalled();
        });

        it('메시지가 없으면 NotFoundException을 던진다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(null);
            await expect(
                service.editMessage(ctx(), { content: '수정됨' }),
            ).rejects.toThrow(NotFoundException);
        });

        it('다른 사람의 메시지를 수정하면 ForbiddenException을 던진다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(makeMockMessage({ authorId: 100 }));
            await expect(
                service.editMessage(ctx(), { content: '수정됨' }),
            ).rejects.toThrow(ForbiddenException);
        });

        it('ADMIN은 다른 사람의 메시지도 수정할 수 있다', async () => {
            const msg = makeMockMessage({ authorId: 100 });
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(msg);

            await service.editMessage(ctx({ userRole: 'ROLE_ADMIN' }), { content: '관리자 수정' });

            expect(msg.save).toHaveBeenCalled();
        });

        it('수정 후 ES updateMessage를 fire-and-forget으로 호출한다', async () => {
            const msg = makeMockMessage({
                projectId: 10,
                save: jest.fn().mockResolvedValue({
                    content: '수정됨',
                    isEdited: true,
                    projectId: 10,
                    updatedAt: new Date('2026-05-12T00:00:00.000Z'),
                }),
            });
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(msg);
            mockElasticsearchService.updateMessage.mockResolvedValue(undefined);

            await service.editMessage(ctx(), { content: '수정됨' });

            await new Promise((r) => setImmediate(r));
            expect(mockElasticsearchService.updateMessage).toHaveBeenCalledWith(mockMessageId, '수정됨');
        });

        it('다른 채널의 메시지를 수정하려 하면 ForbiddenException을 던진다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(makeMockMessage({ channelId: 2 }));

            await expect(
                service.editMessage(ctx(), { content: '수정됨' }),
            ).rejects.toThrow(ForbiddenException);
        });

        it('내용이 동일하면 저장과 이벤트 발행을 생략한다', async () => {
            const msg = makeMockMessage();
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(msg);

            const result = await service.editMessage(ctx(), { content: '안녕하세요' });

            expect(msg.save).not.toHaveBeenCalled();
            expect(mockElasticsearchService.updateMessage).not.toHaveBeenCalled();
            expect(mockTo).not.toHaveBeenCalled();
            expect(result).toBe(msg);
        });

        it('수정 이벤트에는 저장된 updatedAt 값을 사용한다', async () => {
            const savedAt = new Date('2026-05-12T01:02:03.000Z');
            const msg = makeMockMessage({
                save: jest.fn().mockResolvedValue({
                    content: '수정됨',
                    isEdited: true,
                    updatedAt: savedAt,
                }),
            });
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(msg);

            await service.editMessage(ctx(), { content: '수정됨' });

            expect(mockTo).toHaveBeenCalledWith('chat:1');
            expect(mockEmit).toHaveBeenCalledWith('message:edited', {
                messageId: mockMessageId,
                content: '수정됨',
                editedAt: savedAt.toISOString(),
            });
        });

        it('ES 오류가 발생해도 editMessage 결과에 영향을 주지 않는다', async () => {
            const msg = makeMockMessage();
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(msg);
            mockElasticsearchService.updateMessage.mockRejectedValue(new Error('ES down'));

            await expect(
                service.editMessage(ctx(), { content: '수정됨' }),
            ).resolves.toBeDefined();
        });
    });

    describe('deleteMessage', () => {
        const ctx = (overrides = {}) => ({ channelId: 1, messageId: mockMessageId, userId: 42, userRole: 'MEMBER', ...overrides });

        it('본인 메시지를 삭제한다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(makeMockMessage());
            mockMessageRepository.deleteById.mockResolvedValue({ deletedCount: 1 });

            const result = await service.deleteMessage(ctx());

            expect(mockMessageRepository.deleteById).toHaveBeenCalledWith(mockMessageId);
            expect(result.messageId).toBe(mockMessageId);
        });

        it('메시지가 없으면 NotFoundException을 던진다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(null);
            await expect(
                service.deleteMessage(ctx()),
            ).rejects.toThrow(NotFoundException);
        });

        it('다른 사람의 메시지를 삭제하면 ForbiddenException을 던진다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(makeMockMessage({ authorId: 100 }));
            await expect(
                service.deleteMessage(ctx()),
            ).rejects.toThrow(ForbiddenException);
        });

        it('ADMIN은 다른 사람의 메시지도 삭제할 수 있다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(makeMockMessage({ authorId: 100 }));
            mockMessageRepository.deleteById.mockResolvedValue({ deletedCount: 1 });

            await expect(
                service.deleteMessage(ctx({ userRole: 'ROLE_ADMIN' })),
            ).resolves.toBeDefined();
        });

        it('삭제 후 ES deleteMessage를 fire-and-forget으로 호출한다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(makeMockMessage({ projectId: 10 }));
            mockMessageRepository.deleteById.mockResolvedValue({ deletedCount: 1 });
            mockElasticsearchService.deleteMessage.mockResolvedValue(undefined);

            await service.deleteMessage(ctx());

            await new Promise((r) => setImmediate(r));
            expect(mockElasticsearchService.deleteMessage).toHaveBeenCalledWith(mockMessageId);
        });

        it('ES 오류가 발생해도 deleteMessage 결과에 영향을 주지 않는다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockMessageRepository.findById.mockResolvedValue(makeMockMessage());
            mockMessageRepository.deleteById.mockResolvedValue({ deletedCount: 1 });
            mockElasticsearchService.deleteMessage.mockRejectedValue(new Error('ES down'));

            await expect(
                service.deleteMessage(ctx()),
            ).resolves.toBeDefined();
        });
    });

    describe('readChannel', () => {
        const msgId = new Types.ObjectId();

        it('멤버가 아니면 ForbiddenException을 던진다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(false);

            await expect(
                service.readChannel({ channelId: 1, userId: 42 }, msgId.toString()),
            ).rejects.toThrow(ForbiddenException);
        });

        it('lastReadMessageId를 업데이트하고 unreadCount를 계산한다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockChannelMemberRepository.updateLastRead.mockResolvedValue(undefined);
            mockMessageRepository.countUnread.mockResolvedValue(3);

            await service.readChannel({ channelId: 1, userId: 42 }, msgId.toString());

            expect(mockChannelMemberRepository.updateLastRead).toHaveBeenCalledWith(
                1,
                42,
                expect.any(Types.ObjectId),
            );
            expect(mockMessageRepository.countUnread).toHaveBeenCalledWith(1, expect.any(Types.ObjectId));
        });

        it('user:{userId} 룸으로 channel:unread:updated 이벤트를 emit한다', async () => {
            mockChannelMemberRepository.exists.mockResolvedValue(true);
            mockChannelMemberRepository.updateLastRead.mockResolvedValue(undefined);
            mockMessageRepository.countUnread.mockResolvedValue(0);

            await service.readChannel({ channelId: 1, userId: 42 }, msgId.toString());

            expect(mockTo).toHaveBeenCalledWith('user:42');
            expect(mockEmit).toHaveBeenCalledWith('channel:unread:updated', { channelId: 1, unreadCount: 0 });
        });
    });

    describe('getTeamUnread', () => {
        it('가입한 채널별 미읽 카운트를 반환한다', async () => {
            const oid1 = new Types.ObjectId();
            const oid2 = new Types.ObjectId();
            mockChannelMemberRepository.findMembersByTeam.mockResolvedValue([
                { channelId: 1, lastReadMessageId: oid1 },
                { channelId: 2, lastReadMessageId: oid2 },
            ]);
            mockMessageRepository.countUnreadForChannels.mockResolvedValue(new Map([[1, 5], [2, 0]]));

            const result = await service.getTeamUnread(10, 42);

            expect(mockChannelMemberRepository.findMembersByTeam).toHaveBeenCalledWith(10, 42);
            expect(mockMessageRepository.countUnreadForChannels).toHaveBeenCalledWith([
                { channelId: 1, lastReadMessageId: oid1 },
                { channelId: 2, lastReadMessageId: oid2 },
            ]);
            expect(result).toEqual([
                { channelId: 1, unreadCount: 5 },
                { channelId: 2, unreadCount: 0 },
            ]);
        });

        it('가입한 채널이 없으면 빈 배열을 반환한다', async () => {
            mockChannelMemberRepository.findMembersByTeam.mockResolvedValue([]);

            const result = await service.getTeamUnread(10, 42);

            expect(result).toEqual([]);
            expect(mockMessageRepository.countUnreadForChannels).not.toHaveBeenCalled();
        });

        it('lastReadMessageId가 null이면 해당 채널은 Map에서 0으로 fallback된다', async () => {
            mockChannelMemberRepository.findMembersByTeam.mockResolvedValue([
                { channelId: 3, lastReadMessageId: null },
            ]);
            mockMessageRepository.countUnreadForChannels.mockResolvedValue(new Map([[3, 10]]));

            const result = await service.getTeamUnread(10, 42);

            expect(mockMessageRepository.countUnreadForChannels).toHaveBeenCalledWith([
                { channelId: 3, lastReadMessageId: null },
            ]);
            expect(result).toEqual([{ channelId: 3, unreadCount: 10 }]);
        });
    });

    describe('saveSystemMessage', () => {
        it('시스템 메시지는 clientMessageId 없이 저장한다', async () => {
            mockMessageRepository.createSystemMessage.mockResolvedValue({ toObject: jest.fn() });

            await service.saveSystemMessage(10, 1, '이슈가 생성됐어요', 100);

            expect(mockMessageRepository.createSystemMessage).toHaveBeenCalledWith(10, 1, '이슈가 생성됐어요', 100, 0);
        });
    });
});
