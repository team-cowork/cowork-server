import { Test, TestingModule } from '@nestjs/testing';
import { ConfigService } from '@nestjs/config';
import { getModelToken } from '@nestjs/mongoose';
import { Server } from 'socket.io';
import { GithubIssueResultConsumer } from './github-issue-result.consumer';
import { Message } from '../schema/message.schema';
import { GithubIssueResultEvent } from './event/github-issue.event';

const mockToObject = jest.fn();
const mockMessageCreate = jest.fn();

const mockMessageModel = {
    create: mockMessageCreate,
};

const mockEmit = jest.fn();
const mockTo = jest.fn(() => ({ emit: mockEmit }));
const mockIo = { to: mockTo } as unknown as Server;

describe('GithubIssueResultConsumer', () => {
    let consumer: GithubIssueResultConsumer;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            providers: [
                GithubIssueResultConsumer,
                {
                    provide: getModelToken(Message.name),
                    useValue: mockMessageModel,
                },
                {
                    provide: ConfigService,
                    useValue: { get: jest.fn().mockReturnValue('localhost:9092') },
                },
            ],
        }).compile();

        consumer = module.get<GithubIssueResultConsumer>(GithubIssueResultConsumer);
        consumer.setSocketServer(mockIo);
        jest.clearAllMocks();
    });

    const callHandleResultEvent = (event: GithubIssueResultEvent) =>
        (consumer as any).handleResultEvent(event);

    describe('이슈 생성 성공', () => {
        it('성공 SYSTEM 메시지를 저장하고 WebSocket으로 브로드캐스트한다', async () => {
            const savedDoc = { toObject: mockToObject.mockReturnValue({ content: '성공' }) };
            mockMessageCreate.mockResolvedValue(savedDoc);

            const event: GithubIssueResultEvent = {
                channelId: 5,
                teamId: 1,
                success: true,
                issueUrl: 'https://github.com/my-org/backend/issues/42',
            };

            await callHandleResultEvent(event);

            expect(mockMessageCreate).toHaveBeenCalledWith({
                teamId: 1,
                projectId: null,
                channelId: 5,
                authorId: 0,
                content: '✅ 이슈가 생성됐어요: https://github.com/my-org/backend/issues/42',
                type: 'SYSTEM',
                attachments: [],
                mentions: [],
                notificationStatus: 'SENT',
            });
            expect(mockTo).toHaveBeenCalledWith('chat:5');
            expect(mockEmit).toHaveBeenCalledWith('message', expect.anything());
        });
    });

    describe('이슈 생성 실패', () => {
        it('실패 SYSTEM 메시지를 저장하고 WebSocket으로 브로드캐스트한다', async () => {
            const savedDoc = { toObject: mockToObject.mockReturnValue({ content: '실패' }) };
            mockMessageCreate.mockResolvedValue(savedDoc);

            const event: GithubIssueResultEvent = {
                channelId: 5,
                teamId: 1,
                success: false,
                error: '레포지토리를 찾을 수 없어요',
            };

            await callHandleResultEvent(event);

            expect(mockMessageCreate).toHaveBeenCalledWith(
                expect.objectContaining({
                    content: '❌ 이슈 생성 실패: 레포지토리를 찾을 수 없어요',
                    type: 'SYSTEM',
                }),
            );
            expect(mockTo).toHaveBeenCalledWith('chat:5');
        });

        it('error 필드가 없으면 기본 오류 메시지를 사용한다', async () => {
            mockMessageCreate.mockResolvedValue({ toObject: jest.fn().mockReturnValue({}) });

            await callHandleResultEvent({ channelId: 5, teamId: 1, success: false });

            expect(mockMessageCreate).toHaveBeenCalledWith(
                expect.objectContaining({
                    content: '❌ 이슈 생성 실패: 알 수 없는 오류',
                }),
            );
        });
    });

    describe('WebSocket 서버 미설정', () => {
        it('io가 없어도 메시지 저장은 정상 처리된다', async () => {
            const consumerWithoutIo = new (GithubIssueResultConsumer as any)(
                mockMessageModel,
                { get: jest.fn().mockReturnValue('localhost:9092') },
            );
            mockMessageCreate.mockResolvedValue({ toObject: jest.fn().mockReturnValue({}) });

            await expect(
                (consumerWithoutIo as any).handleResultEvent({
                    channelId: 5,
                    teamId: 1,
                    success: true,
                    issueUrl: 'https://github.com/my-org/backend/issues/1',
                }),
            ).resolves.not.toThrow();

            expect(mockMessageCreate).toHaveBeenCalled();
        });
    });
});
