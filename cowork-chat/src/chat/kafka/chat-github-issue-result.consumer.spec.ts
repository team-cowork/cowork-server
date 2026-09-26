import { ChatGithubIssueResultConsumer } from './chat-github-issue-result.consumer';
import { ChatGithubIssueCreateResult } from './event/chat-github-issue.event';

const mockChatService = {
    saveSystemMessage: jest.fn(),
};

const mockConfigService = {};

const mockDicoshot = {
    sendCustom: jest.fn().mockResolvedValue(undefined),
};

const mockChannelMessageReadAccess = {
    emitToReadableChannelUsers: jest.fn().mockResolvedValue(undefined),
};

describe('ChatGithubIssueResultConsumer', () => {
    let consumer: ChatGithubIssueResultConsumer;

    beforeEach(() => {
        jest.clearAllMocks();
        consumer = new ChatGithubIssueResultConsumer(
            mockChatService as never,
            mockConfigService as never,
            mockDicoshot as never,
            mockChannelMessageReadAccess as never,
        );
        consumer.setSocketServer({} as never);
    });

    const baseResult: ChatGithubIssueCreateResult = {
        operationId: 'op-1',
        idempotencyKey: 'key-1',
        channelId: 3,
        teamId: 10,
        projectId: 5,
        requesterId: 42,
        status: 'REJECTED',
        error: { code: 'FORBIDDEN', message: '프로젝트 수정 권한이 없습니다.' },
        occurredAt: '2026-09-26T00:00:00.000Z',
    };

    describe('handleResultEvent (REJECTED)', () => {
        it('거부 사유를 담은 시스템 메시지를 저장하고 브로드캐스트한다', async () => {
            const savedMessage = { toObject: jest.fn().mockReturnValue({ id: 'm1' }) };
            mockChatService.saveSystemMessage.mockResolvedValue(savedMessage);

            await (consumer as unknown as {
                handleResultEvent(event: ChatGithubIssueCreateResult): Promise<void>;
            }).handleResultEvent(baseResult);

            expect(mockChatService.saveSystemMessage).toHaveBeenCalledWith(
                10,
                3,
                '❌ 이슈 생성 실패: 프로젝트 수정 권한이 없습니다.',
                5,
            );
            expect(mockChannelMessageReadAccess.emitToReadableChannelUsers).toHaveBeenCalledTimes(1);
        });

        it('error가 없으면 알 수 없는 오류 문구를 사용한다', async () => {
            mockChatService.saveSystemMessage.mockResolvedValue({ toObject: jest.fn().mockReturnValue({}) });

            await (consumer as unknown as {
                handleResultEvent(event: ChatGithubIssueCreateResult): Promise<void>;
            }).handleResultEvent({ ...baseResult, error: null });

            expect(mockChatService.saveSystemMessage).toHaveBeenCalledWith(
                10,
                3,
                '❌ 이슈 생성 실패: 알 수 없는 오류',
                5,
            );
        });
    });

    describe('handleResultEvent (ACCEPTED)', () => {
        it('시스템 메시지를 저장하거나 브로드캐스트하지 않는다', async () => {
            await (consumer as unknown as {
                handleResultEvent(event: ChatGithubIssueCreateResult): Promise<void>;
            }).handleResultEvent({ ...baseResult, status: 'ACCEPTED', error: null });

            expect(mockChatService.saveSystemMessage).not.toHaveBeenCalled();
            expect(mockChannelMessageReadAccess.emitToReadableChannelUsers).not.toHaveBeenCalled();
        });
    });
});
