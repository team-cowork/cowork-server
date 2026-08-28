import { Test, TestingModule } from '@nestjs/testing';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { ChatGateway, ChatSocket } from './chat.gateway';
import { ChatService } from './chat.service';
import { ChatMessageConsumer } from './kafka/chat-message.consumer';
import { GithubIssueResultConsumer } from './kafka/github-issue-result.consumer';
import { GithubRepoEventConsumer } from './kafka/github-repo-event.consumer';
import { ChannelEventConsumer } from './kafka/channel-event.consumer';
import { ProjectEventConsumer } from './kafka/project-event.consumer';
import { MembershipConsumer } from '../membership/membership.consumer';
import { RedisRateLimiter } from '../common/util/redis-rate-limiter';
import { ProjectionReadinessService } from '../common/kafka/projection-readiness.service';

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
    handshake: { auth: { token: authToken }, headers },
    data: {} as Record<string, unknown>,
    rooms: new Set<string>(),
    join: jest.fn(),
    leave: jest.fn(),
    emit: jest.fn(),
    to: jest.fn(() => ({ emit: jest.fn() })),
    disconnect: jest.fn(),
});

const mockChatService = {
    isMember: jest.fn(),
    isTeamMember: jest.fn(),
};

const mockConsumer = {
    setSocketServer: jest.fn(),
};

const mockGithubIssueResultConsumer = {
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
};

describe('ChatGateway', () => {
    let gateway: ChatGateway;

    beforeEach(async () => {
        jest.clearAllMocks();
        mockJwtService.verifyAsync.mockResolvedValue({ sub: '42', role: 'MEMBER' });
        mockProjectionReadiness.isReady.mockReturnValue(true);

        const module: TestingModule = await Test.createTestingModule({
            providers: [
                ChatGateway,
                { provide: ChatService, useValue: mockChatService },
                { provide: ChatMessageConsumer, useValue: mockConsumer },
                { provide: GithubIssueResultConsumer, useValue: mockGithubIssueResultConsumer },
                { provide: GithubRepoEventConsumer, useValue: mockGithubRepoEventConsumer },
                { provide: ChannelEventConsumer, useValue: mockChannelEventConsumer },
                { provide: ProjectEventConsumer, useValue: mockProjectEventConsumer },
                { provide: MembershipConsumer, useValue: mockMembershipConsumer },
                { provide: ConfigService, useValue: mockConfigService },
                { provide: JwtService, useValue: mockJwtService },
                { provide: RedisRateLimiter, useValue: mockRateLimiter },
                { provide: ProjectionReadinessService, useValue: mockProjectionReadiness },
            ],
        }).compile();

        gateway = module.get<ChatGateway>(ChatGateway);
    });

    describe('afterInit', () => {
        it('projection readiness가 닫혀 있으면 Socket.IO handshake middleware가 연결을 거부한다', () => {
            type ConnectionMiddleware = (
                socket: { use: jest.Mock },
                callback: (error?: Error) => void,
            ) => void;
            let middleware: ConnectionMiddleware | undefined;
            const server = {
                use: jest.fn((candidate: ConnectionMiddleware) => {
                    middleware = candidate;
                }),
            } as unknown as Parameters<ChatGateway['afterInit']>[0];
            gateway.afterInit(server);
            mockProjectionReadiness.isReady.mockReturnValue(false);
            const next = jest.fn();
            if (!middleware) throw new Error('Socket.IO middleware was not registered');

            middleware({ use: jest.fn() }, next);

            expect(next).toHaveBeenCalledWith(expect.objectContaining({ message: 'Kafka projections are synchronizing' }));
        });

        it('연결 후 readiness가 닫히면 기존 socket의 inbound event도 packet middleware가 거부한다', () => {
            type PacketMiddleware = (packet: unknown[], callback: (error?: Error) => void) => void;
            type ConnectionMiddleware = (
                socket: { use: (candidate: PacketMiddleware) => void },
                callback: (error?: Error) => void,
            ) => void;
            let connectionMiddleware: ConnectionMiddleware | undefined;
            const server = {
                use: jest.fn((candidate: ConnectionMiddleware) => {
                    connectionMiddleware = candidate;
                }),
            } as unknown as Parameters<ChatGateway['afterInit']>[0];
            gateway.afterInit(server);
            if (!connectionMiddleware) throw new Error('Socket.IO middleware was not registered');
            let packetMiddleware: PacketMiddleware | undefined;
            const socket = {
                use: jest.fn((candidate: PacketMiddleware) => {
                    packetMiddleware = candidate;
                }),
            };
            const connectionNext = jest.fn();

            connectionMiddleware(socket, connectionNext);

            expect(connectionNext).toHaveBeenCalledWith();
            if (!packetMiddleware) throw new Error('Socket.IO packet middleware was not registered');
            mockProjectionReadiness.isReady.mockReturnValue(false);
            const packetNext = jest.fn();
            packetMiddleware(['join', { channelId: 1 }], packetNext);

            expect(packetNext).toHaveBeenCalledWith(
                expect.objectContaining({ message: 'Kafka projections are synchronizing' }),
            );
        });
    });

    describe('handleConnection', () => {
        it('projection readiness가 닫혀 있으면 인증 전에 연결을 거부한다', async () => {
            mockProjectionReadiness.isReady.mockReturnValue(false);
            const client = mockSocket(undefined, { 'x-user-id': '7', 'x-user-role': 'ADMIN' });

            await gateway.handleConnection(client as unknown as ChatSocket);

            expect(client.emit).toHaveBeenCalledWith('exception', { message: 'Kafka projections are synchronizing' });
            expect(client.disconnect).toHaveBeenCalled();
            expect(client.join).not.toHaveBeenCalled();
        });
        it('Gateway가 주입한 X-User-Id/X-User-Role 헤더가 있으면 이를 우선 신뢰한다', async () => {
            const client = mockSocket(undefined, { 'x-user-id': '7', 'x-user-role': 'ADMIN' });
            await gateway.handleConnection(client as unknown as ChatSocket);
            expect(client.data.userId).toBe(7);
            expect(client.data.userRole).toBe('ADMIN');
            expect(client.join).toHaveBeenCalledWith('user:7');
            expect(mockJwtService.verifyAsync).not.toHaveBeenCalled();
            expect(client.disconnect).not.toHaveBeenCalled();
        });

        it('X-User-Id 헤더가 유효하지 않으면 exception 이벤트를 emit하고 disconnect된다', async () => {
            const client = mockSocket(undefined, { 'x-user-id': 'not-a-number' });
            await gateway.handleConnection(client as unknown as ChatSocket);
            expect(client.emit).toHaveBeenCalledWith('exception', { message: '인증 실패: X-User-Id 헤더가 유효하지 않습니다' });
            expect(client.disconnect).toHaveBeenCalled();
        });

        it('헤더가 없으면 auth.token의 JWT를 직접 검증해 연결한다', async () => {
            const client = mockSocket('valid-token');
            await gateway.handleConnection(client as unknown as ChatSocket);
            expect(client.data.userId).toBe(42);
            expect(client.data.userRole).toBe('MEMBER');
            expect(client.join).toHaveBeenCalledWith('user:42');
            expect(client.disconnect).not.toHaveBeenCalled();
        });

        it('헤더도 토큰도 없이 연결하면 exception 이벤트를 emit하고 disconnect된다', async () => {
            const client = mockSocket(undefined);
            await gateway.handleConnection(client as unknown as ChatSocket);
            expect(client.emit).toHaveBeenCalledWith('exception', { message: '인증 실패: 인증 정보가 전달되지 않았습니다' });
            expect(client.disconnect).toHaveBeenCalled();
        });

        it('유효하지 않은 토큰으로 연결하면 exception 이벤트를 emit하고 disconnect된다', async () => {
            mockJwtService.verifyAsync.mockRejectedValue(new Error('Invalid token'));
            const client = mockSocket('invalid-token');
            await gateway.handleConnection(client as unknown as ChatSocket);
            expect(client.emit).toHaveBeenCalledWith('exception', { message: '인증 실패: Invalid token' });
            expect(client.disconnect).toHaveBeenCalled();
        });
    });

    describe('handleJoin', () => {
        it('채널 멤버이면 room에 참가한다', async () => {
            mockChatService.isMember.mockResolvedValue(true);
            const client = mockSocket('valid-token');
            client.data.userId = 42;

            await gateway.handleJoin(client as unknown as ChatSocket, { channelId: 1 });

            expect(client.join).toHaveBeenCalledWith('chat:1');
            expect(client.emit).not.toHaveBeenCalledWith('error', expect.anything());
        });

        it('채널 멤버가 아니면 error 이벤트를 emit하고 join하지 않는다', async () => {
            mockChatService.isMember.mockResolvedValue(false);
            const client = mockSocket('valid-token');
            client.data.userId = 42;

            await gateway.handleJoin(client as unknown as ChatSocket, { channelId: 1 });

            expect(client.join).not.toHaveBeenCalled();
            expect(client.emit).toHaveBeenCalledWith('error', { message: '채널 접근 권한이 없습니다' });
        });
    });

    describe('handleLeave', () => {
        it('채널 room에서 퇴장한다', () => {
            const client = mockSocket('valid-token');
            gateway.handleLeave(client as unknown as ChatSocket, { channelId: 1 });
            expect(client.leave).toHaveBeenCalledWith('chat:1');
        });
    });

    describe('typing rate limit', () => {
        it('룸에 참여한 상태에서 typing:start를 호출하면 같은 룸에 typing 이벤트를 릴레이한다', async () => {
            const client = mockSocket('valid-token');
            client.data.userId = 42;
            client.rooms.add('chat:1');
            const emit = jest.fn();
            client.to = jest.fn(() => ({ emit }));

            await gateway.handleTypingStart(client as unknown as ChatSocket, { channelId: 1 });

            expect(mockRateLimiter.tryAcquire).toHaveBeenCalledWith('chat:typingrate:42', expect.any(Number), expect.any(Number));
            expect(client.to).toHaveBeenCalledWith('chat:1');
            expect(emit).toHaveBeenCalledWith('typing', { channelId: 1, userId: 42, isTyping: true });
        });

        it('rate limiter가 한도 초과를 반환하면 typing 이벤트를 조용히 무시한다', async () => {
            mockRateLimiter.tryAcquire.mockResolvedValueOnce(false);
            const client = mockSocket('valid-token');
            client.data.userId = 42;
            client.rooms.add('chat:1');
            const emit = jest.fn();
            client.to = jest.fn(() => ({ emit }));

            await gateway.handleTypingStart(client as unknown as ChatSocket, { channelId: 1 });

            expect(emit).not.toHaveBeenCalled();
        });
    });
});
