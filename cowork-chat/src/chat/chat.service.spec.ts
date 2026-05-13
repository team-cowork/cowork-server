import { Test, TestingModule } from '@nestjs/testing';
import { getModelToken } from '@nestjs/mongoose';
import { ForbiddenException, NotFoundException } from '@nestjs/common';
import { Types } from 'mongoose';
import { ChatService } from './chat.service';
import { ChatGateway } from './chat.gateway';
import { Message } from './schema/message.schema';
import { ChannelMember } from './schema/channel-member.schema';
import { ElasticsearchService } from '../search/elasticsearch.service';
import { MinioService } from '../storage/minio.service';
import { ChatMessageProducer } from './kafka/chat-message.producer';
import { GithubIssueProducer } from './kafka/github-issue.producer';
import { ProjectClient } from './service/project.client';
import { ChannelClient } from './service/channel.client';
import { UserClient } from './service/user.client';

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

const mockAggregate = jest.fn();

const mockMessageModel = {
    aggregate: mockAggregate,
    findById: jest.fn(),
    deleteOne: jest.fn(),
    create: jest.fn(),
    collection: { name: 'messages' },
};


const mockMemberModel = {
    exists: jest.fn(),
};

const mockElasticsearchService = {
    updateMessage: jest.fn().mockResolvedValue(undefined),
    deleteMessage: jest.fn().mockResolvedValue(undefined),
    searchMessages: jest.fn(),
};

const mockMinioService = {
    createPresignedUpload: jest.fn(),
    confirmUpload: jest.fn(),
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
    getDisplayName: jest.fn(),
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
                { provide: getModelToken(Message.name), useValue: mockMessageModel },
                { provide: getModelToken(ChannelMember.name), useValue: mockMemberModel },
                { provide: ElasticsearchService, useValue: mockElasticsearchService },
                { provide: MinioService, useValue: mockMinioService },
                { provide: ChatMessageProducer, useValue: mockChatMessageProducer },
                { provide: GithubIssueProducer, useValue: mockGithubIssueProducer },
                { provide: ProjectClient, useValue: mockProjectClient },
                { provide: ChannelClient, useValue: mockChannelClient },
                { provide: UserClient, useValue: mockUserClient },
                { provide: ChatGateway, useValue: mockChatGateway },
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

    describe('getFileList', () => {
        it('FILE_SHARE 채널의 첨부파일을 파일 단위로 반환한다', async () => {
            const createdAt = new Date('2026-05-12T02:03:04.000Z');
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockChannelClient.getChannel.mockResolvedValue({ id: 1, viewType: 'FILE_SHARE' });
            mockAggregate.mockResolvedValue([
                {
                    _id: new Types.ObjectId('665f00000000000000000001'),
                    authorId: 42,
                    createdAt,
                    attachmentIndex: 0,
                    attachment: {
                        name: 'report.pdf',
                        url: 'http://localhost:9000/cowork-bucket/chat-files/1/42/report.pdf',
                        size: 2048,
                        mimeType: 'application/pdf',
                    },
                },
            ]);
            mockUserClient.getDisplayName.mockResolvedValue('홍길동');

            const result = await service.getFileList(1, 42);

            expect(mockChannelClient.getChannel).toHaveBeenCalledWith(1, 42);
            expect(mockAggregate).toHaveBeenCalled();
            expect(mockUserClient.getDisplayName).toHaveBeenCalledWith(42);
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
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockChannelClient.getChannel.mockResolvedValue({ id: 1, viewType: 'TEXT' });

            await expect(service.getFileList(1, 42)).rejects.toThrow('FILE_SHARE 채널에서만 파일 목록을 조회할 수 있습니다');
            expect(mockAggregate).not.toHaveBeenCalled();
        });

        it('before 커서를 사용해 다음 페이지를 조회한다', async () => {
            const cursorRow = {
                _id: new Types.ObjectId('665f00000000000000000002'),
                createdAt: new Date('2026-05-12T03:00:00.000Z'),
                attachmentIndex: 1,
            };
            const before = Buffer.from(JSON.stringify({
                uploadedAt: cursorRow.createdAt.toISOString(),
                messageId: cursorRow._id.toString(),
                attachmentIndex: cursorRow.attachmentIndex,
            })).toString('base64');

            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockChannelClient.getChannel.mockResolvedValue({ id: 1, viewType: 'FILE_SHARE' });
            mockAggregate.mockResolvedValue([]);

            await service.getFileList(1, 42, before, 20);

            const pipeline = mockAggregate.mock.calls[0][0];
            expect(pipeline[2].$match).toEqual({
                $or: [
                    { createdAt: { $lt: new Date(cursorRow.createdAt) } },
                    {
                        createdAt: new Date(cursorRow.createdAt),
                        _id: { $lt: new Types.ObjectId(cursorRow._id.toString()) },
                    },
                    {
                        createdAt: new Date(cursorRow.createdAt),
                        _id: new Types.ObjectId(cursorRow._id.toString()),
                        attachmentIndex: { $lt: 1 },
                    },
                ],
            });
        });
    });

    describe('editMessage', () => {
        it('본인 메시지를 수정하면 editHistory에 이전 내용이 저장된다', async () => {
            const msg = makeMockMessage();
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(msg);

            await service.editMessage(1, mockMessageId, 42, { content: '수정됨' }, 'MEMBER');

            expect(msg.editHistory).toHaveLength(1);
            expect(msg.editHistory[0].content).toBe('안녕하세요');
            expect(msg.content).toBe('수정됨');
            expect(msg.isEdited).toBe(true);
            expect(msg.save).toHaveBeenCalled();
        });

        it('메시지가 없으면 NotFoundException을 던진다', async () => {
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(null);
            await expect(
                service.editMessage(1, mockMessageId, 42, { content: '수정됨' }, 'MEMBER'),
            ).rejects.toThrow(NotFoundException);
        });

        it('다른 사람의 메시지를 수정하면 ForbiddenException을 던진다', async () => {
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(makeMockMessage({ authorId: 100 }));
            await expect(
                service.editMessage(1, mockMessageId, 42, { content: '수정됨' }, 'MEMBER'),
            ).rejects.toThrow(ForbiddenException);
        });

        it('ADMIN은 다른 사람의 메시지도 수정할 수 있다', async () => {
            const msg = makeMockMessage({ authorId: 100 });
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(msg);

            await service.editMessage(1, mockMessageId, 42, { content: '관리자 수정' }, 'ROLE_ADMIN');

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
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(msg);
            mockElasticsearchService.updateMessage.mockResolvedValue(undefined);

            await service.editMessage(1, mockMessageId, 42, { content: '수정됨' }, 'MEMBER');

            await new Promise((r) => setImmediate(r));
            expect(mockElasticsearchService.updateMessage).toHaveBeenCalledWith(mockMessageId, '수정됨');
        });

        it('다른 채널의 메시지를 수정하려 하면 ForbiddenException을 던진다', async () => {
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(makeMockMessage({ channelId: 2 }));

            await expect(
                service.editMessage(1, mockMessageId, 42, { content: '수정됨' }, 'MEMBER'),
            ).rejects.toThrow(ForbiddenException);
        });

        it('내용이 동일하면 저장과 이벤트 발행을 생략한다', async () => {
            const msg = makeMockMessage();
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(msg);

            const result = await service.editMessage(1, mockMessageId, 42, { content: '안녕하세요' }, 'MEMBER');

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
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(msg);

            await service.editMessage(1, mockMessageId, 42, { content: '수정됨' }, 'MEMBER');

            expect(mockTo).toHaveBeenCalledWith('chat:1');
            expect(mockEmit).toHaveBeenCalledWith('message:edited', {
                messageId: mockMessageId,
                content: '수정됨',
                editedAt: savedAt.toISOString(),
            });
        });

        it('ES 오류가 발생해도 editMessage 결과에 영향을 주지 않는다', async () => {
            const msg = makeMockMessage();
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(msg);
            mockElasticsearchService.updateMessage.mockRejectedValue(new Error('ES down'));

            await expect(
                service.editMessage(1, mockMessageId, 42, { content: '수정됨' }, 'MEMBER'),
            ).resolves.toBeDefined();
        });
    });

    describe('deleteMessage', () => {
        it('본인 메시지를 삭제한다', async () => {
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(makeMockMessage());
            mockMessageModel.deleteOne.mockResolvedValue({ deletedCount: 1 });

            const result = await service.deleteMessage(1, mockMessageId, 42, 'MEMBER');

            expect(mockMessageModel.deleteOne).toHaveBeenCalledWith({ _id: mockMessageId });
            expect(result.messageId).toBe(mockMessageId);
        });

        it('메시지가 없으면 NotFoundException을 던진다', async () => {
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(null);
            await expect(
                service.deleteMessage(1, mockMessageId, 42, 'MEMBER'),
            ).rejects.toThrow(NotFoundException);
        });

        it('다른 사람의 메시지를 삭제하면 ForbiddenException을 던진다', async () => {
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(makeMockMessage({ authorId: 100 }));
            await expect(
                service.deleteMessage(1, mockMessageId, 42, 'MEMBER'),
            ).rejects.toThrow(ForbiddenException);
        });

        it('ADMIN은 다른 사람의 메시지도 삭제할 수 있다', async () => {
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(makeMockMessage({ authorId: 100 }));
            mockMessageModel.deleteOne.mockResolvedValue({ deletedCount: 1 });

            await expect(
                service.deleteMessage(1, mockMessageId, 42, 'ROLE_ADMIN'),
            ).resolves.toBeDefined();
        });

        it('삭제 후 ES deleteMessage를 fire-and-forget으로 호출한다', async () => {
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(makeMockMessage({ projectId: 10 }));
            mockMessageModel.deleteOne.mockResolvedValue({ deletedCount: 1 });
            mockElasticsearchService.deleteMessage.mockResolvedValue(undefined);

            await service.deleteMessage(1, mockMessageId, 42, 'MEMBER');

            await new Promise((r) => setImmediate(r));
            expect(mockElasticsearchService.deleteMessage).toHaveBeenCalledWith(mockMessageId);
        });

        it('ES 오류가 발생해도 deleteMessage 결과에 영향을 주지 않는다', async () => {
            mockMemberModel.exists.mockResolvedValue({ _id: 'some-id' });
            mockMessageModel.findById.mockResolvedValue(makeMockMessage());
            mockMessageModel.deleteOne.mockResolvedValue({ deletedCount: 1 });
            mockElasticsearchService.deleteMessage.mockRejectedValue(new Error('ES down'));

            await expect(
                service.deleteMessage(1, mockMessageId, 42, 'MEMBER'),
            ).resolves.toBeDefined();
        });
    });

    describe('saveSystemMessage', () => {
        it('시스템 메시지는 clientMessageId 없이 저장한다', async () => {
            mockMessageModel.create.mockResolvedValue({ toObject: jest.fn() });

            await service.saveSystemMessage(10, 1, '이슈가 생성됐어요', 100);

            expect(mockMessageModel.create).toHaveBeenCalledWith({
                teamId: 10,
                projectId: 100,
                channelId: 1,
                authorId: 0,
                content: '이슈가 생성됐어요',
                type: 'SYSTEM',
                attachments: [],
                mentions: [],
                clientMessageId: undefined,
                notificationStatus: 'SENT',
            });
        });
    });
});
