import { Test, TestingModule } from '@nestjs/testing';
import { ConfigService } from '@nestjs/config';
import { Server } from 'socket.io';
import { DicoshotService } from 'dicoshot-nest';
import { GithubRepoEventConsumer } from './github-repo-event.consumer';
import { ChatService } from '../chat.service';
import { ProjectClient } from '../service/project.client';
import { GithubRepoEvent } from './event/github-repo.event';

type ConsumerWithPrivates = Omit<GithubRepoEventConsumer, 'handleRepoEvent'> & {
    handleRepoEvent: (event: GithubRepoEvent) => Promise<void>;
};

const mockToObject = jest.fn();
const mockSaveSystemMessage = jest.fn();
const mockGetGithubWebhookTarget = jest.fn();

const mockChatService = {
    saveSystemMessage: mockSaveSystemMessage,
};

const mockProjectClient = {
    getGithubWebhookTarget: mockGetGithubWebhookTarget,
};

const mockEmit = jest.fn();
const mockTo = jest.fn(() => ({ emit: mockEmit }));
const mockIo = { to: mockTo } as unknown as Server;

describe('GithubRepoEventConsumer', () => {
    let consumer: GithubRepoEventConsumer;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            providers: [
                GithubRepoEventConsumer,
                {
                    provide: ChatService,
                    useValue: mockChatService,
                },
                {
                    provide: ProjectClient,
                    useValue: mockProjectClient,
                },
                {
                    provide: ConfigService,
                    useValue: { get: jest.fn().mockReturnValue('localhost:9092') },
                },
                {
                    provide: DicoshotService,
                    useValue: { sendCustom: jest.fn().mockResolvedValue(true) },
                },
            ],
        }).compile();

        consumer = module.get<GithubRepoEventConsumer>(GithubRepoEventConsumer);
        consumer.setSocketServer(mockIo);
        jest.clearAllMocks();
    });

    const callHandleRepoEvent = (event: GithubRepoEvent) => (consumer as unknown as ConsumerWithPrivates).handleRepoEvent(event);

    describe('GitHub 알림 채널이 지정되지 않은 경우', () => {
        it('project-service가 webhook target을 찾지 못하면(null) 아무 것도 하지 않는다', async () => {
            mockGetGithubWebhookTarget.mockResolvedValue(null);

            const event: GithubRepoEvent = {
                owner: 'my-org',
                repo: 'backend',
                eventType: 'push',
                action: 'push',
                summary: 'main 브랜치에 새 커밋이 푸시됐어요',
            };

            await callHandleRepoEvent(event);

            expect(mockGetGithubWebhookTarget).toHaveBeenCalledWith('my-org', 'backend');
            expect(mockSaveSystemMessage).not.toHaveBeenCalled();
            expect(mockTo).not.toHaveBeenCalled();
            expect(mockEmit).not.toHaveBeenCalled();
        });
    });

    describe('GitHub 알림 채널이 지정된 경우', () => {
        it('SYSTEM 메시지를 저장하고 WebSocket으로 브로드캐스트한다', async () => {
            mockGetGithubWebhookTarget.mockResolvedValue({ teamId: 1, projectId: 100, channelId: 5 });
            const savedDoc = { toObject: mockToObject.mockReturnValue({ content: 'main 브랜치에 새 커밋이 푸시됐어요' }) };
            mockSaveSystemMessage.mockResolvedValue(savedDoc);

            const event: GithubRepoEvent = {
                owner: 'my-org',
                repo: 'backend',
                eventType: 'push',
                action: 'push',
                summary: 'main 브랜치에 새 커밋이 푸시됐어요',
            };

            await callHandleRepoEvent(event);

            expect(mockGetGithubWebhookTarget).toHaveBeenCalledWith('my-org', 'backend');
            expect(mockSaveSystemMessage).toHaveBeenCalledWith(1, 5, 'main 브랜치에 새 커밋이 푸시됐어요', 100);
            expect(mockTo).toHaveBeenCalledWith('chat:5');
            expect(mockEmit).toHaveBeenCalledWith('message', expect.anything());
        });
    });

    describe('WebSocket 서버 미설정', () => {
        it('io가 없어도 메시지 저장은 정상 처리된다', async () => {
            const consumerWithoutIo = new GithubRepoEventConsumer(
                mockChatService as unknown as ChatService,
                mockProjectClient as unknown as ProjectClient,
                { get: jest.fn().mockReturnValue('localhost:9092') } as unknown as ConfigService,
                { sendCustom: jest.fn().mockResolvedValue(true) } as unknown as DicoshotService,
            );
            mockGetGithubWebhookTarget.mockResolvedValue({ teamId: 1, projectId: 100, channelId: 5 });
            mockSaveSystemMessage.mockResolvedValue({ toObject: jest.fn().mockReturnValue({}) });

            await expect(
                (consumerWithoutIo as unknown as ConsumerWithPrivates).handleRepoEvent({
                    owner: 'my-org',
                    repo: 'backend',
                    eventType: 'issues',
                    action: 'opened',
                    summary: '새 이슈가 등록됐어요',
                }),
            ).resolves.not.toThrow();

            expect(mockSaveSystemMessage).toHaveBeenCalled();
        });
    });
});
