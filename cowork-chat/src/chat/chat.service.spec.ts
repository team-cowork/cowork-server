import { BadRequestException, ForbiddenException, NotFoundException } from '@nestjs/common';
import { Types } from 'mongoose';
import { ChatService } from './chat.service';

const mockMessageId = new Types.ObjectId().toString();
const mockEmit = jest.fn();
const mockTo = jest.fn((room: string) => {
    void room;
    return { emit: mockEmit };
});

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
    findByIdAndChannelId: jest.fn(),
    deleteById: jest.fn(),
    createSystemMessage: jest.fn(),
    countUnread: jest.fn(),
    countUnreadForChannels: jest.fn(),
    findLastMessages: jest.fn(),
    addReaction: jest.fn(),
    removeReaction: jest.fn(),
    findPinnedMessages: jest.fn(),
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

const mockTeamMemberRepository = {
    exists: jest.fn(),
};

const mockChannelMessageReadAccess = {
    canReadChannel: jest.fn().mockResolvedValue(true),
    requireCanRead: jest.fn().mockResolvedValue(undefined),
    findReadableProjectChannelIds: jest.fn(),
    findReadableTeamChannelIds: jest.fn(),
    filterReadableChannelIds: jest.fn(),
    filterReadableUsersByChannel: jest.fn(),
    emitToReadableChannelUsers: jest.fn(),
};

const mockBlockService = {
    isBlocked: jest.fn(),
};

const mockElasticsearchService = {
    updateMessage: jest.fn().mockResolvedValue(undefined),
    deleteMessage: jest.fn().mockResolvedValue(undefined),
    searchMessages: jest.fn(),
    searchTeamMessages: jest.fn(),
};

const mockObjectStorageService = {
    createPresignedUpload: jest.fn(),
    confirmUpload: jest.fn(),
    assertOwnedAttachmentUrl: jest.fn(),
    extractObjectKey: jest.fn(),
    removeObject: jest.fn(),
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

const mockUnreadCounterService = {
    getMany: jest.fn(),
    setMany: jest.fn().mockResolvedValue(undefined),
    set: jest.fn().mockResolvedValue(undefined),
    incrementIfPresent: jest.fn().mockResolvedValue(undefined),
};

describe('ChatService', () => {
    let service: ChatService;

    beforeEach(() => {
        service = new ChatService(
            mockMessageRepository as never,
            mockChannelMemberRepository as never,
            mockTeamMemberRepository as never,
            mockChannelMessageReadAccess as never,
            mockElasticsearchService as never,
            mockObjectStorageService as never,
            mockChatMessageProducer as never,
            mockGithubIssueProducer as never,
            mockProjectClient as never,
            mockChannelClient as never,
            mockUserClient as never,
            mockBlockService as never,
            mockChatGateway as never,
            mockUnreadCounterService as never,
        );
        jest.clearAllMocks();
        mockChannelMessageReadAccess.canReadChannel.mockResolvedValue(true);
        mockChannelMessageReadAccess.requireCanRead.mockResolvedValue(undefined);
        mockChannelMessageReadAccess.findReadableProjectChannelIds.mockResolvedValue([]);
        mockChannelMessageReadAccess.findReadableTeamChannelIds.mockResolvedValue([]);
        mockChannelMessageReadAccess.filterReadableChannelIds.mockImplementation(
            (_teamId: number, _userId: number, channelIds: number[]) => Promise.resolve(channelIds),
        );
        mockChannelMessageReadAccess.filterReadableUsersByChannel.mockImplementation(
            (usersByChannel: Map<number, number[]>) => Promise.resolve(usersByChannel),
        );
        mockChannelMessageReadAccess.emitToReadableChannelUsers.mockImplementation(
            (_io: typeof mockChatGateway.server, channelId: number, event: string, payload: unknown) => {
                mockTo(`chat:${channelId}`);
                mockEmit(event, payload);
                return Promise.resolve();
            },
        );
    });

    describe('searchTeamMessages', () => {
        it('팀 멤버지만 가입 채널이 없으면 권한 오류 대신 빈 결과를 반환한다', async () => {
            mockTeamMemberRepository.exists.mockResolvedValue(true);
            mockChannelMemberRepository.findMembersByTeam.mockResolvedValue([]);

            await expect(service.searchTeamMessages(10, { teamId: 10, q: '배포' }, { userId: 42 }))
                .resolves.toEqual({ messages: [], nextCursor: null });
            expect(mockElasticsearchService.searchTeamMessages).not.toHaveBeenCalled();
        });

        it('팀 소속 정보에 없으면 검색을 거부한다', async () => {
            mockTeamMemberRepository.exists.mockResolvedValue(false);

            await expect(service.searchTeamMessages(10, { teamId: 10, q: '배포' }, { userId: 99 }))
                .rejects.toBeInstanceOf(ForbiddenException);
        });

        it('요청한 채널을 읽을 수 없으면 검색을 거부한다', async () => {
            mockTeamMemberRepository.exists.mockResolvedValue(true);
            mockChannelMessageReadAccess.findReadableTeamChannelIds.mockResolvedValue([1, 2]);

            await expect(service.searchTeamMessages(
                10,
                { teamId: 10, channelId: 99, q: '배포' },
                { userId: 42 },
            )).rejects.toBeInstanceOf(ForbiddenException);
        });
    });

    describe('searchProjectMessages', () => {
        it('프로젝트 멤버지만 읽을 수 있는 가입 채널이 없으면 ES를 호출하지 않고 빈 결과를 반환한다', async () => {
            mockProjectClient.isMember.mockResolvedValue(true);
            mockChannelMessageReadAccess.findReadableProjectChannelIds.mockResolvedValue([]);

            await expect(service.searchProjectMessages(7, { q: '배포' }, { userId: 42 }))
                .resolves.toEqual({ messages: [], nextCursor: null });

            expect(mockElasticsearchService.searchMessages).not.toHaveBeenCalled();
        });

        it('프로젝트 멤버가 아니면 검색을 거부한다', async () => {
            mockProjectClient.isMember.mockResolvedValue(false);

            await expect(service.searchProjectMessages(7, { q: '배포' }, { userId: 99 }))
                .rejects.toBeInstanceOf(ForbiddenException);
        });

        it('요청한 채널을 읽을 수 없으면 검색을 거부한다', async () => {
            mockProjectClient.isMember.mockResolvedValue(true);
            mockChannelMessageReadAccess.findReadableProjectChannelIds.mockResolvedValue([1, 2]);

            await expect(service.searchProjectMessages(
                7,
                { channelId: 99, q: '배포' },
                { userId: 42 },
            )).rejects.toBeInstanceOf(ForbiddenException);
        });
    });

    describe('sendMessage', () => {
        const ctx = { channelId: 1, userId: 42, userRole: 'USER' };

        it('팀 채널 메시지는 클라이언트 teamId를 멤버십의 teamId로 덮어쓴다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue({ teamId: 100, channelType: 'TEXT' });

            await service.sendMessage(ctx, { teamId: 999, content: 'hi' });

            expect(mockChatMessageProducer.sendMessage).toHaveBeenCalledWith(
                1,
                expect.objectContaining({ teamId: 100, content: 'hi' }),
                42,
                'USER',
            );
        });

        it('채널 멤버가 아니면 ForbiddenException을 던진다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue(null);

            await expect(service.sendMessage(ctx, { content: 'hi' })).rejects.toThrow(ForbiddenException);
            expect(mockChatMessageProducer.sendMessage).not.toHaveBeenCalled();
        });

        it('첨부파일이 있으면 각 url의 소유권을 검증한다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue({ teamId: 100, channelType: 'TEXT' });
            mockObjectStorageService.assertOwnedAttachmentUrl.mockReturnValue(undefined);
            const attachments = [
                { name: 'a.png', url: 'http://object-storage/chat-files/1/42/uuid.png', size: 1, mimeType: 'image/png' },
            ];

            await service.sendMessage(ctx, { content: 'hi', attachments });

            expect(mockObjectStorageService.assertOwnedAttachmentUrl).toHaveBeenCalledWith(attachments[0].url, 1, 42);
            expect(mockChatMessageProducer.sendMessage).toHaveBeenCalled();
        });

        it('소유하지 않은 첨부파일 url이면 검증에서 던진 예외가 전파되고 발행되지 않는다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue({ teamId: 100, channelType: 'TEXT' });
            mockObjectStorageService.assertOwnedAttachmentUrl.mockImplementation(() => {
                throw new BadRequestException('첨부파일 URL이 유효하지 않습니다');
            });
            const attachments = [
                { name: 'a.png', url: 'http://object-storage/chat-files/999/7/uuid.png', size: 1, mimeType: 'image/png' },
            ];

            await expect(service.sendMessage(ctx, { content: 'hi', attachments })).rejects.toThrow(BadRequestException);
            expect(mockChatMessageProducer.sendMessage).not.toHaveBeenCalled();
        });

        it('DM 채널에서 수신자가 발신자를 차단했으면 ForbiddenException을 던진다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue({ teamId: null, channelType: 'DM' });
            mockChannelMemberRepository.findByChannelId.mockResolvedValue([{ userId: 42 }, { userId: 7 }]);
            mockBlockService.isBlocked.mockResolvedValue(true);

            await expect(service.sendMessage(ctx, { content: 'hi' })).rejects.toThrow(ForbiddenException);
            expect(mockBlockService.isBlocked).toHaveBeenCalledWith(7, 42);
            expect(mockChatMessageProducer.sendMessage).not.toHaveBeenCalled();
        });

        it('DM 채널 메시지는 teamId/projectId를 null로 강제하고 수신자 숨김을 해제한다', async () => {
            mockChannelMemberRepository.findMembership.mockResolvedValue({ teamId: null, channelType: 'DM' });
            mockChannelMemberRepository.findByChannelId.mockResolvedValue([{ userId: 42 }, { userId: 7 }]);
            mockBlockService.isBlocked.mockResolvedValue(false);

            await service.sendMessage(ctx, { teamId: 999, projectId: 5, content: 'hi' });

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

    describe('publishGithubIssueCreateCommand', () => {
        it('프로젝트에 연결된 단일 저장소의 팀 경계를 검증한 뒤 명령을 발행한다', async () => {
            mockChannelMemberRepository.findTeamIdByChannelAndUser.mockResolvedValue(10);
            mockProjectClient.getGithubRepoInfo.mockResolvedValue({
                repoId: 7,
                teamId: 10,
                owner: 'cowork-org',
                repo: 'server',
            });

            await service.publishGithubIssueCreateCommand(
                { channelId: 3, userId: 42 },
                { projectId: 5, title: '배포 오류', body: '재현 절차' },
            );

            expect(mockProjectClient.getGithubRepoInfo).toHaveBeenCalledWith(5);
            expect(mockGithubIssueProducer.send).toHaveBeenCalledWith({
                channelId: 3,
                teamId: 10,
                projectId: 5,
                owner: 'cowork-org',
                repo: 'server',
                title: '배포 오류',
                body: '재현 절차',
                requesterId: 42,
            });
        });

        it('프로젝트에 연결된 저장소가 없으면 명령을 발행하지 않는다', async () => {
            mockChannelMemberRepository.findTeamIdByChannelAndUser.mockResolvedValue(10);
            mockProjectClient.getGithubRepoInfo.mockResolvedValue(null);

            await expect(service.publishGithubIssueCreateCommand(
                { channelId: 3, userId: 42 },
                { projectId: 5, title: '배포 오류' },
            )).rejects.toBeInstanceOf(BadRequestException);
            expect(mockGithubIssueProducer.send).not.toHaveBeenCalled();
        });

        it('저장소가 속한 팀과 채널 팀이 다르면 명령을 발행하지 않는다', async () => {
            mockChannelMemberRepository.findTeamIdByChannelAndUser.mockResolvedValue(10);
            mockProjectClient.getGithubRepoInfo.mockResolvedValue({
                repoId: 7,
                teamId: 20,
                owner: 'cowork-org',
                repo: 'server',
            });

            await expect(service.publishGithubIssueCreateCommand(
                { channelId: 3, userId: 42 },
                { projectId: 5, title: '배포 오류' },
            )).rejects.toBeInstanceOf(ForbiddenException);
            expect(mockGithubIssueProducer.send).not.toHaveBeenCalled();
        });

        it('팀에 속하지 않는 채널에서는 명령을 발행하지 않는다', async () => {
            mockChannelMemberRepository.findTeamIdByChannelAndUser.mockResolvedValue(null);

            await expect(service.publishGithubIssueCreateCommand(
                { channelId: 3, userId: 42 },
                { projectId: 5, title: '배포 오류' },
            )).rejects.toBeInstanceOf(ForbiddenException);
            expect(mockProjectClient.getGithubRepoInfo).not.toHaveBeenCalled();
            expect(mockGithubIssueProducer.send).not.toHaveBeenCalled();
        });
    });

    describe('getMessages', () => {
        it('채널 멤버가 아니면 ForbiddenException을 던진다', async () => {
            mockChannelMessageReadAccess.requireCanRead.mockRejectedValueOnce(new ForbiddenException());

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

            expect(mockChannelClient.getChannel).toHaveBeenCalledWith(1);
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

    });

    describe('deleteFile', () => {
        const fileId = Buffer.from(JSON.stringify({ messageId: mockMessageId })).toString('base64url');
        const ctx = { channelId: 1, userId: 42, userRole: 'MEMBER' };

        it('변조된 fileId는 저장소를 조회하기 전에 거부한다', async () => {
            await expect(service.deleteFile(ctx, 'not-a-file-id')).rejects.toBeInstanceOf(BadRequestException);

            expect(mockChannelMessageReadAccess.requireCanRead).not.toHaveBeenCalled();
            expect(mockMessageRepository.findByIdAndChannelId).not.toHaveBeenCalled();
        });

        it('파일 메시지가 없으면 NOT_FOUND로 응답한다', async () => {
            mockMessageRepository.findByIdAndChannelId.mockResolvedValue(null);

            await expect(service.deleteFile(ctx, fileId)).rejects.toBeInstanceOf(NotFoundException);

            expect(mockMessageRepository.deleteById).not.toHaveBeenCalled();
        });

        it('다른 사용자가 올린 파일이면 삭제를 거부한다', async () => {
            mockMessageRepository.findByIdAndChannelId.mockResolvedValue({
                authorId: 7,
                attachments: [],
            });

            await expect(service.deleteFile(ctx, fileId)).rejects.toBeInstanceOf(ForbiddenException);
            expect(mockMessageRepository.deleteById).not.toHaveBeenCalled();
            expect(mockObjectStorageService.removeObject).not.toHaveBeenCalled();
        });

        it('채널 저장 경로 밖의 객체는 삭제하지 않는다', async () => {
            mockMessageRepository.findByIdAndChannelId.mockResolvedValue({
                authorId: 42,
                attachments: [{ url: 'https://storage.example.com/other/secret.png' }],
                projectId: null,
            });
            mockObjectStorageService.extractObjectKey.mockReturnValue('other/secret.png');
            mockMessageRepository.deleteById.mockResolvedValue({ deletedCount: 1 });

            await service.deleteFile(ctx, fileId);

            expect(mockObjectStorageService.removeObject).not.toHaveBeenCalled();
            expect(mockMessageRepository.deleteById).toHaveBeenCalledWith(mockMessageId);
        });
    });

    describe('handleSlashCommand', () => {
        it('지원하지 않는 명령은 거부한다', async () => {
            await expect(service.handleSlashCommand(
                { channelId: 1, userId: 42 },
                { command: 'unsupported', payload: {} } as never,
            )).rejects.toBeInstanceOf(BadRequestException);
            expect(mockGithubIssueProducer.send).not.toHaveBeenCalled();
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

            await service.editMessage(ctx({ userRole: 'ADMIN' }), { content: '관리자 수정' });

            expect(msg.save).toHaveBeenCalled();
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
                service.deleteMessage(ctx({ userRole: 'ADMIN' })),
            ).resolves.toBeDefined();
        });

    });

    describe('pinMessage', () => {
        it('이미 고정된 메시지는 다시 고정하지 않는다', async () => {
            const message = makeMockMessage({ isPinned: true });
            mockMessageRepository.findById.mockResolvedValue(message);

            await expect(service.pinMessage({
                channelId: 1,
                messageId: mockMessageId,
                userId: 42,
                userRole: 'MEMBER',
            })).rejects.toBeInstanceOf(BadRequestException);
            expect(message.save).not.toHaveBeenCalled();
        });
    });

    describe('unpinMessage', () => {
        it('고정되지 않은 메시지는 고정 해제하지 않는다', async () => {
            const message = makeMockMessage({ isPinned: false });
            mockMessageRepository.findById.mockResolvedValue(message);

            await expect(service.unpinMessage({
                channelId: 1,
                messageId: mockMessageId,
                userId: 42,
                userRole: 'MEMBER',
            })).rejects.toBeInstanceOf(BadRequestException);
            expect(message.save).not.toHaveBeenCalled();
        });
    });

    describe('addReaction', () => {
        it('대상 메시지가 없으면 반응을 추가하지 않는다', async () => {
            mockMessageRepository.addReaction.mockResolvedValue(null);

            await expect(service.addReaction(
                { channelId: 1, userId: 42 },
                mockMessageId,
                '👍',
            )).rejects.toBeInstanceOf(NotFoundException);
        });
    });

    describe('removeReaction', () => {
        it('대상 메시지가 없으면 반응을 제거하지 않는다', async () => {
            mockMessageRepository.removeReaction.mockResolvedValue(null);

            await expect(service.removeReaction(
                { channelId: 1, userId: 42 },
                mockMessageId,
                '👍',
            )).rejects.toBeInstanceOf(NotFoundException);
        });
    });

    describe('readChannel', () => {
        const msgId = new Types.ObjectId();

        it('멤버가 아니면 ForbiddenException을 던진다', async () => {
            mockChannelMessageReadAccess.requireCanRead.mockRejectedValueOnce(new ForbiddenException());

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
            expect(mockUnreadCounterService.set).toHaveBeenCalledWith(1, 42, 3);
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
        it('message_read가 허용된 팀 멤버(SYSTEM_AUTHOR_ID 제외)의 안읽음 수를 증가시킨다', async () => {
            mockMessageRepository.createSystemMessage.mockResolvedValue({ toObject: jest.fn() });
            mockChannelMemberRepository.findByChannelId.mockResolvedValue([
                { userId: 7 },
                { userId: 9 },
                { userId: 0 },
            ]);

            await service.saveSystemMessage(10, 1, '이슈가 생성됐어요', 100);

            expect(mockUnreadCounterService.incrementIfPresent).toHaveBeenCalledWith(1, [7, 9]);
        });
    });
});
