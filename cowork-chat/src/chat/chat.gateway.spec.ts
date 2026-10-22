import {ChatGateway, type ChatSocket} from './chat.gateway';

const mockConfigService = {
    get: jest.fn().mockReturnValue(''),
};

const mockRateLimiter = {
    tryAcquire: jest.fn().mockResolvedValue(true),
};

const mockProjectionReadiness = {
    isReady: jest.fn(),
};

const mockJwtService = {
    verifyAsync: jest.fn(),
};

const mockSocket = (authToken?: string, headers: Record<string, string> = {}) => ({
    id: 'socket-1',
    handshake: {auth: {token: authToken}, headers},
    data: {} as Record<string, unknown>,
    rooms: new Set<string>(),
    join: jest.fn(),
    leave: jest.fn(),
    emit: jest.fn(),
    to: jest.fn(() => ({emit: jest.fn()})),
    disconnect: jest.fn(),
    use: jest.fn(),
});

const mockChatService = {
    isMember: jest.fn(),
    isTeamMember: jest.fn(),
    emitToReadableChannelUsers: jest.fn(),
    evictUnauthorizedRooms: jest.fn(),
};

const mockConsumer = {
    setSocketServer: jest.fn(),
};

const mockGithubIssueResultConsumer = {
    setSocketServer: jest.fn(),
};

const mockChatGithubIssueResultConsumer = {
    setSocketServer: jest.fn(),
};

const mockGithubRepoEventConsumer = {
    setSocketServer: jest.fn(),
};

const mockChannelEventConsumer = {
    setSocketServer: jest.fn(),
};

const mockProjectEventConsumer = {
    setSocketServer: jest.fn(),
};

const mockMembershipConsumer = {
    setSocketServer: jest.fn(),
    setReadAccessEvaluator: jest.fn(),
    setReadableEmitter: jest.fn(),
};

const mockTeamRoleEventConsumer = {
    setSocketServer: jest.fn(),
};

const mockChannelRolePolicyEventConsumer = {
    setSocketServer: jest.fn(),
};

const mockTeamMemberEventConsumer = {
    setSocketServer: jest.fn(),
};

describe('ChatGateway', () => {
    let gateway: ChatGateway;

    beforeEach(() => {
        jest.clearAllMocks();
        mockJwtService.verifyAsync.mockResolvedValue({sub: '42', role: 'MEMBER'});
        mockProjectionReadiness.isReady.mockReturnValue(true);
        mockChatService.isMember.mockResolvedValue(true);
        mockChatService.emitToReadableChannelUsers.mockResolvedValue(undefined);
        mockChatService.evictUnauthorizedRooms.mockResolvedValue(0);

        gateway = new ChatGateway(
            mockChatService as never,
            mockConsumer as never,
            mockGithubIssueResultConsumer as never,
            mockChatGithubIssueResultConsumer as never,
            mockGithubRepoEventConsumer as never,
            mockChannelEventConsumer as never,
            mockProjectEventConsumer as never,
            mockMembershipConsumer as never,
            mockTeamRoleEventConsumer as never,
            mockChannelRolePolicyEventConsumer as never,
            mockTeamMemberEventConsumer as never,
            mockConfigService as never,
            mockJwtService as never,
            mockRateLimiter as never,
            mockProjectionReadiness as never,
            {isReady: () => true} as never,
        );
    });

    describe('handshake 인증', () => {
        // afterInit이 등록한 handshake 미들웨어를 실행해 next에 전달된 오류(connect_error)를 돌려준다.
        const handshake = async (client: ReturnType<typeof mockSocket>): Promise<Error | undefined> => {
            const server = {use: jest.fn()};
            gateway.afterInit(server as never);
            gateway.onModuleDestroy();
            const middleware = server.use.mock.calls[0][0] as (socket: unknown, next: (error?: Error) => void) => void;
            return new Promise((resolve) => middleware(client, resolve));
        };

        it('Gateway가 주입한 X-User-Id/X-User-Role 헤더가 있으면 이를 우선 신뢰한다', async () => {
            const client = mockSocket(undefined, {'x-user-id': '7', 'x-user-role': 'ADMIN'});
            expect(await handshake(client)).toBeUndefined();
            expect(client.data.userId).toBe(7);
            expect(client.data.userRole).toBe('ADMIN');
            expect(mockJwtService.verifyAsync).not.toHaveBeenCalled();
        });

        it('X-User-Id 헤더가 유효하지 않으면 connect_error로 거절한다', async () => {
            const client = mockSocket(undefined, {'x-user-id': 'not-a-number'});
            expect((await handshake(client))?.message).toBe('인증 실패: X-User-Id 헤더가 유효하지 않습니다');
        });

        it('헤더가 없으면 auth.token의 JWT를 직접 검증해 연결한다', async () => {
            const client = mockSocket('valid-token');
            expect(await handshake(client)).toBeUndefined();
            expect(client.data.userId).toBe(42);
            expect(client.data.userRole).toBe('MEMBER');
        });

        it('헤더도 토큰도 없이 연결하면 connect_error로 거절한다', async () => {
            const client = mockSocket(undefined);
            expect((await handshake(client))?.message).toBe('인증 실패: 인증 정보가 전달되지 않았습니다');
        });

        it('유효하지 않은 토큰으로 연결하면 connect_error로 거절한다', async () => {
            mockJwtService.verifyAsync.mockRejectedValue(new Error('Invalid token'));
            const client = mockSocket('invalid-token');
            expect((await handshake(client))?.message).toBe('인증 실패: Invalid token');
        });

        it('Projection 동기화가 끝나지 않았으면 인증 전에 connect_error로 거절한다', async () => {
            mockProjectionReadiness.isReady.mockReturnValue(false);
            const client = mockSocket('valid-token');
            expect((await handshake(client))?.message).toBe('Kafka projections are synchronizing');
            expect(mockJwtService.verifyAsync).not.toHaveBeenCalled();
        });
    });

    describe('handleConnection', () => {
        it('인증된 소켓을 개인 room에 넣는다', async () => {
            const client = mockSocket('valid-token');
            client.data.userId = 42;
            await gateway.handleConnection(client as unknown as ChatSocket);
            expect(client.join).toHaveBeenCalledWith('user:42');
        });
    });

    describe('handleJoin', () => {
        it('채널 멤버이면 room에 참가한다', async () => {
            mockChatService.isMember.mockResolvedValue(true);
            const client = mockSocket('valid-token');
            client.data.userId = 42;

            await gateway.handleJoin(client as unknown as ChatSocket, {channelId: 1});

            expect(client.join).toHaveBeenCalledWith('chat:1');
            expect(client.emit).not.toHaveBeenCalledWith('error', expect.anything());
        });

        it('읽기 권한이 없으면 room에 가입하지 않고 error 이벤트를 emit한다', async () => {
            mockChatService.isMember.mockResolvedValue(false);
            const client = mockSocket('valid-token');
            client.data.userId = 42;

            await gateway.handleJoin(client as unknown as ChatSocket, {channelId: 1});

            expect(client.join).not.toHaveBeenCalled();
            expect(client.emit).toHaveBeenCalledWith('error', {message: '채널 접근 권한이 없습니다'});
        });

        it('인가를 통과하기 전에는 room에 가입하지 않는다 — room 멤버십이 곧 판정 결과이기 때문이다', async () => {
            const client = mockSocket('valid-token');
            client.data.userId = 42;

            await gateway.handleJoin(client as unknown as ChatSocket, {channelId: 1});

            expect(mockChatService.isMember.mock.invocationCallOrder[0])
                .toBeLessThan(client.join.mock.invocationCallOrder[0]);
        });

        it('가입 직후 재확인에서 권한이 사라졌으면 room에서 제거한다', async () => {
            mockChatService.isMember.mockResolvedValueOnce(true).mockResolvedValueOnce(false);
            const client = mockSocket('valid-token');
            client.data.userId = 42;

            await gateway.handleJoin(client as unknown as ChatSocket, {channelId: 1});

            expect(client.join).toHaveBeenCalledWith('chat:1');
            expect(client.leave).toHaveBeenCalledWith('chat:1');
            expect(client.emit).toHaveBeenCalledWith('error', {message: '채널 접근 권한이 없습니다'});
        });

        it('가입 뒤 재확인이 실패하면 room에서 제거하고 오류를 다시 던진다', async () => {
            mockChatService.isMember.mockResolvedValueOnce(true).mockRejectedValueOnce(new Error('mongo down'));
            const client = mockSocket('valid-token');
            client.data.userId = 42;

            await expect(gateway.handleJoin(client as unknown as ChatSocket, {channelId: 1})).rejects.toThrow('mongo down');
            expect(client.leave).toHaveBeenCalledWith('chat:1');
        });
    });

    describe('handleJoinTeam', () => {
        it('팀 멤버가 아니면 room에서 제거하고 error 이벤트를 emit한다', async () => {
            mockChatService.isTeamMember.mockResolvedValue(false);
            const client = mockSocket('valid-token');
            client.data.userId = 42;

            await gateway.handleJoinTeam(client as unknown as ChatSocket, {teamId: 5});

            expect(client.join.mock.invocationCallOrder[0])
                .toBeLessThan(mockChatService.isTeamMember.mock.invocationCallOrder[0]);
            expect(client.leave).toHaveBeenCalledWith('team:5');
            expect(client.emit).toHaveBeenCalledWith('error', {message: '팀 접근 권한이 없습니다'});
        });

        it('권한 확인이 실패하면 room에서 제거하고 오류를 다시 던진다', async () => {
            mockChatService.isTeamMember.mockRejectedValueOnce(new Error('mongo down'));
            const client = mockSocket('valid-token');
            client.data.userId = 42;

            await expect(gateway.handleJoinTeam(client as unknown as ChatSocket, {teamId: 5})).rejects.toThrow('mongo down');
            expect(client.leave).toHaveBeenCalledWith('team:5');
        });
    });

    describe('connection state recovery', () => {
        it('복원된 연결이면 복원된 room을 현재 권한으로 다시 검증한다', async () => {
            const client = Object.assign(mockSocket('valid-token'), {recovered: true});
            client.data.userId = 42;

            await gateway.handleConnection(client as unknown as ChatSocket);

            expect(mockChatService.evictUnauthorizedRooms).toHaveBeenCalledWith([client]);
            expect(client.disconnect).not.toHaveBeenCalled();
        });

        it('복원 room 재검증이 실패해도 인증 실패로 연결을 끊지 않는다', async () => {
            mockChatService.evictUnauthorizedRooms.mockRejectedValueOnce(new Error('mongo down'));
            const client = Object.assign(mockSocket('valid-token'), {recovered: true});
            client.data.userId = 42;

            await gateway.handleConnection(client as unknown as ChatSocket);

            expect(client.disconnect).not.toHaveBeenCalled();
        });
    });

    describe('typing rate limit', () => {
        it('룸에 참여한 상태에서 typing:start를 호출하면 같은 룸에 typing 이벤트를 릴레이한다', async () => {
            const client = mockSocket('valid-token');
            client.data.userId = 42;
            client.rooms.add('chat:1');
            await gateway.handleTypingStart(client as unknown as ChatSocket, {channelId: 1});

            expect(mockRateLimiter.tryAcquire).toHaveBeenCalledWith('chat:typingrate:42', expect.any(Number), expect.any(Number));
            expect(mockChatService.emitToReadableChannelUsers).toHaveBeenCalledWith(
                1,
                'typing',
                {channelId: 1, userId: 42, isTyping: true},
                'socket-1',
            );
        });

        it('rate limiter가 한도 초과를 반환하면 typing 이벤트를 조용히 무시한다', async () => {
            mockRateLimiter.tryAcquire.mockResolvedValueOnce(false);
            const client = mockSocket('valid-token');
            client.data.userId = 42;
            client.rooms.add('chat:1');
            await gateway.handleTypingStart(client as unknown as ChatSocket, {channelId: 1});

            expect(mockChatService.emitToReadableChannelUsers).not.toHaveBeenCalled();
        });

        it('typing 시점에 권한이 회수됐으면 sender를 room에서 제거하고 이벤트를 보내지 않는다', async () => {
            mockChatService.isMember.mockResolvedValueOnce(false);
            const client = mockSocket('valid-token');
            client.data.userId = 42;
            client.rooms.add('chat:1');

            await gateway.handleTypingStart(client as unknown as ChatSocket, {channelId: 1});

            expect(client.leave).toHaveBeenCalledWith('chat:1');
            expect(client.emit).toHaveBeenCalledWith('channel:access:revoked', {channelId: 1});
            expect(mockChatService.emitToReadableChannelUsers).not.toHaveBeenCalled();
        });
    });
});
