import { Test, TestingModule } from '@nestjs/testing';
import { ForbiddenException, NotFoundException } from '@nestjs/common';
import { Types } from 'mongoose';
import { ChatController } from './chat.controller';
import { ChatService } from './chat.service';
import { ChatGateway } from './chat.gateway';
import { ChatMessageProducer } from './kafka/chat-message.producer';
import { GithubIssueProducer } from './kafka/github-issue.producer';
import { ProjectClient } from './service/project.client';
import { MinioService } from '../storage/minio.service';

const mockMessageId = new Types.ObjectId().toString();
const userId = 42;
const userRole = 'ROLE_USER';

const mockChatService = {
    checkMembership: jest.fn(),
    getChannelTeamId: jest.fn(),
    getMessages: jest.fn(),
    editMessage: jest.fn(),
    deleteMessage: jest.fn(),
};

const mockProducer = {
    sendMessage: jest.fn(),
};

const mockGithubIssueProducer = {
    send: jest.fn(),
};

const mockProjectClient = {
    getGithubRepoInfo: jest.fn(),
};

const mockMinioService = {
    createPresignedUpload: jest.fn(),
};

const mockEmit = jest.fn();
const mockTo = jest.fn(() => ({ emit: mockEmit }));
const mockChatGateway = {
    server: { to: mockTo },
};

describe('ChatController', () => {
    let controller: ChatController;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            controllers: [ChatController],
            providers: [
                { provide: ChatService, useValue: mockChatService },
                { provide: ChatGateway, useValue: mockChatGateway },
                { provide: ChatMessageProducer, useValue: mockProducer },
                { provide: MinioService, useValue: mockMinioService },
                { provide: GithubIssueProducer, useValue: mockGithubIssueProducer },
                { provide: ProjectClient, useValue: mockProjectClient },
            ],
        }).compile();

        controller = module.get<ChatController>(ChatController);
        jest.clearAllMocks();
    });

    describe('createFileUploadUrl', () => {
        it('멤버십 검증 후 MinIO presigned URL을 발급한다', async () => {
            mockChatService.checkMembership.mockResolvedValue(undefined);
            mockMinioService.createPresignedUpload.mockResolvedValue({
                objectKey: 'chat-files/1/42/file.png',
                uploadUrl: 'http://localhost:9000/upload',
                fileUrl: 'http://localhost:9000/cowork-bucket/chat-files/1/42/file.png',
                expiresInSeconds: 600,
            });

            const result = await controller.createFileUploadUrl(
                1,
                { filename: 'file.png', contentType: 'image/png', size: 1024 },
                userId,
            );

            expect(mockChatService.checkMembership).toHaveBeenCalledWith(1, 42);
            expect(mockMinioService.createPresignedUpload).toHaveBeenCalledWith({
                channelId: 1,
                userId: 42,
                filename: 'file.png',
                contentType: 'image/png',
                size: 1024,
            });
            expect(result.headers).toEqual({ 'Content-Type': 'image/png' });
        });
    });

    describe('sendMessage', () => {
        const dto = { teamId: 10, content: '안녕하세요' };

        it('멤버십 검증 후 Kafka로 메시지를 발행한다', async () => {
            mockChatService.checkMembership.mockResolvedValue(undefined);
            mockProducer.sendMessage.mockResolvedValue(undefined);

            const result = await controller.sendMessage(1, dto as any, userId, userRole);

            expect(mockChatService.checkMembership).toHaveBeenCalledWith(1, 42);
            expect(mockProducer.sendMessage).toHaveBeenCalledWith(1, dto, 42, 'ROLE_USER');
            expect(result).toEqual({ queued: true });
        });

        it('채널 멤버가 아니면 ForbiddenException이 전파된다', async () => {
            mockChatService.checkMembership.mockRejectedValue(new ForbiddenException());

            await expect(
                controller.sendMessage(1, dto as any, userId, userRole),
            ).rejects.toThrow(ForbiddenException);

            expect(mockProducer.sendMessage).not.toHaveBeenCalled();
        });
    });

    describe('createGithubIssue', () => {
        const dto = {
            projectId: 100,
            title: '로그인 버그',
            body: '특정 브라우저에서 재현됨',
        };

        it('프로젝트 레포 정보를 조회한 뒤 github.issue.create 토픽으로 이벤트를 발행한다', async () => {
            mockChatService.checkMembership.mockResolvedValue(undefined);
            mockChatService.getChannelTeamId.mockResolvedValue(10);
            mockProjectClient.getGithubRepoInfo.mockResolvedValue({ teamId: 10, owner: 'my-org', repo: 'backend' });
            mockGithubIssueProducer.send.mockResolvedValue(undefined);

            const result = await controller.createGithubIssue(1, dto as any, userId);

            expect(mockChatService.checkMembership).toHaveBeenCalledWith(1, 42);
            expect(mockChatService.getChannelTeamId).toHaveBeenCalledWith(1);
            expect(mockProjectClient.getGithubRepoInfo).toHaveBeenCalledWith(100);
            expect(mockGithubIssueProducer.send).toHaveBeenCalledWith({
                channelId: 1,
                teamId: 10,
                projectId: 100,
                owner: 'my-org',
                repo: 'backend',
                title: '로그인 버그',
                body: '특정 브라우저에서 재현됨',
                requesterId: 42,
            });
            expect(result).toEqual({ queued: true });
        });

        it('body 없이도 이슈 생성 이벤트를 발행한다', async () => {
            mockChatService.checkMembership.mockResolvedValue(undefined);
            mockChatService.getChannelTeamId.mockResolvedValue(10);
            mockProjectClient.getGithubRepoInfo.mockResolvedValue({ teamId: 10, owner: 'my-org', repo: 'backend' });
            mockGithubIssueProducer.send.mockResolvedValue(undefined);

            await controller.createGithubIssue(1, { ...dto, body: undefined } as any, userId);

            expect(mockGithubIssueProducer.send).toHaveBeenCalledWith(
                expect.objectContaining({ body: undefined }),
            );
        });

        it('채널 멤버가 아니면 ForbiddenException이 전파되고 이벤트를 발행하지 않는다', async () => {
            mockChatService.checkMembership.mockRejectedValue(new ForbiddenException());

            await expect(
                controller.createGithubIssue(1, dto as any, userId),
            ).rejects.toThrow(ForbiddenException);

            expect(mockGithubIssueProducer.send).not.toHaveBeenCalled();
        });

        it('프로젝트에 GitHub 레포 정보가 없으면 BadRequestException을 던진다', async () => {
            mockChatService.checkMembership.mockResolvedValue(undefined);
            mockChatService.getChannelTeamId.mockResolvedValue(10);
            mockProjectClient.getGithubRepoInfo.mockResolvedValue(null);

            await expect(
                controller.createGithubIssue(1, dto as any, userId),
            ).rejects.toThrow('프로젝트 GitHub 레포지토리 정보를 찾을 수 없습니다');

            expect(mockGithubIssueProducer.send).not.toHaveBeenCalled();
        });

        it('프로젝트의 teamId가 채널 teamId와 다르면 ForbiddenException을 던진다', async () => {
            mockChatService.checkMembership.mockResolvedValue(undefined);
            mockChatService.getChannelTeamId.mockResolvedValue(10);
            mockProjectClient.getGithubRepoInfo.mockResolvedValue({ teamId: 99, owner: 'other-org', repo: 'backend' });

            await expect(
                controller.createGithubIssue(1, dto as any, userId),
            ).rejects.toThrow('해당 프로젝트는 이 채널의 팀에 속하지 않습니다');

            expect(mockGithubIssueProducer.send).not.toHaveBeenCalled();
        });

        it('producer 오류가 전파된다', async () => {
            mockChatService.checkMembership.mockResolvedValue(undefined);
            mockChatService.getChannelTeamId.mockResolvedValue(10);
            mockProjectClient.getGithubRepoInfo.mockResolvedValue({ teamId: 10, owner: 'my-org', repo: 'backend' });
            mockGithubIssueProducer.send.mockRejectedValue(new Error('Kafka 연결 오류'));

            await expect(
                controller.createGithubIssue(1, dto as any, userId),
            ).rejects.toThrow('Kafka 연결 오류');
        });
    });

    describe('getMessages', () => {
        it('멤버십 검증 후 채널 메시지를 조회한다', async () => {
            mockChatService.checkMembership.mockResolvedValue(undefined);
            mockChatService.getMessages.mockResolvedValue([]);

            const result = await controller.getMessages(1, {}, userId);

            expect(mockChatService.checkMembership).toHaveBeenCalledWith(1, 42);
            expect(mockChatService.getMessages).toHaveBeenCalledWith(1, undefined);
            expect(result).toEqual([]);
        });

        it('cursor(before) 파라미터를 서비스로 전달한다', async () => {
            mockChatService.checkMembership.mockResolvedValue(undefined);
            mockChatService.getMessages.mockResolvedValue([]);
            await controller.getMessages(1, { before: mockMessageId }, userId);
            expect(mockChatService.getMessages).toHaveBeenCalledWith(1, mockMessageId);
        });

        it('채널 멤버가 아니면 ForbiddenException이 발생한다', async () => {
            mockChatService.checkMembership.mockRejectedValue(new ForbiddenException());
            await expect(controller.getMessages(1, {}, userId)).rejects.toThrow(ForbiddenException);
        });
    });

    describe('editMessage', () => {
        it('메시지를 수정하고 Socket.io로 브로드캐스트한다', async () => {
            const updated = { content: '수정됨' };
            mockChatService.checkMembership.mockResolvedValue(undefined);
            mockChatService.editMessage.mockResolvedValue(updated);

            const result = await controller.editMessage(1, mockMessageId, { content: '수정됨' }, userId, userRole);

            expect(mockChatService.editMessage).toHaveBeenCalledWith(
                mockMessageId, 42, { content: '수정됨' }, 'ROLE_USER',
            );
            expect(mockTo).toHaveBeenCalledWith('chat:1');
            expect(mockEmit).toHaveBeenCalledWith('message:edited', expect.objectContaining({
                messageId: mockMessageId,
                content: '수정됨',
            }));
            expect(result).toBe(updated);
        });

        it('본인 메시지가 아니면 ForbiddenException이 전파된다', async () => {
            mockChatService.editMessage.mockRejectedValue(new ForbiddenException());
            await expect(
                controller.editMessage(1, mockMessageId, { content: '수정됨' }, userId, userRole),
            ).rejects.toThrow(ForbiddenException);
        });

        it('메시지가 없으면 NotFoundException이 전파된다', async () => {
            mockChatService.editMessage.mockRejectedValue(new NotFoundException());
            await expect(
                controller.editMessage(1, mockMessageId, { content: '수정됨' }, userId, userRole),
            ).rejects.toThrow(NotFoundException);
        });
    });

    describe('deleteMessage', () => {
        it('메시지를 삭제하고 Socket.io로 브로드캐스트한다', async () => {
            mockChatService.checkMembership.mockResolvedValue(undefined);
            mockChatService.deleteMessage.mockResolvedValue({ channelId: 1, messageId: mockMessageId });

            await controller.deleteMessage(1, mockMessageId, userId, userRole);

            expect(mockChatService.deleteMessage).toHaveBeenCalledWith(
                mockMessageId, 42, 'ROLE_USER',
            );
            expect(mockTo).toHaveBeenCalledWith('chat:1');
            expect(mockEmit).toHaveBeenCalledWith('message:deleted', { messageId: mockMessageId });
        });

        it('본인 메시지가 아니면 ForbiddenException이 전파된다', async () => {
            mockChatService.deleteMessage.mockRejectedValue(new ForbiddenException());
            await expect(
                controller.deleteMessage(1, mockMessageId, userId, userRole),
            ).rejects.toThrow(ForbiddenException);
        });
    });
});
