import { Test, TestingModule } from '@nestjs/testing';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { ChatGateway, ChatSocket } from './chat.gateway';
import { ChatService } from './chat.service';
import { ChatMessageConsumer } from './kafka/chat-message.consumer';
import { GithubIssueResultConsumer } from './kafka/github-issue-result.consumer';
import { ChannelEventConsumer } from './kafka/channel-event.consumer';
import { ProjectEventConsumer } from './kafka/project-event.consumer';
import { MembershipConsumer } from '../membership/membership.consumer';
import { RedisRateLimiter } from '../common/util/redis-rate-limiter';

const mockConfigService = {
    get: jest.fn().mockReturnValue(''),
};

const mockRateLimiter = {
    tryAcquire: jest.fn().mockResolvedValue(true),
};

const mockJwtService = {
    verifyAsync: jest.fn(),
};

const mockSocket = (authToken?: string) => ({
    id: 'socket-1',
    handshake: { auth: { token: authToken } },
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
        mockJwtService.verifyAsync.mockResolvedValue({ sub: '42', role: 'ROLE_USER' });

        const module: TestingModule = await Test.createTestingModule({
            providers: [
                ChatGateway,
                { provide: ChatService, useValue: mockChatService },
                { provide: ChatMessageConsumer, useValue: mockConsumer },
                { provide: GithubIssueResultConsumer, useValue: mockGithubIssueResultConsumer },
                { provide: ChannelEventConsumer, useValue: mockChannelEventConsumer },
                { provide: ProjectEventConsumer, useValue: mockProjectEventConsumer },
                { provide: MembershipConsumer, useValue: mockMembershipConsumer },
                { provide: ConfigService, useValue: mockConfigService },
                { provide: JwtService, useValue: mockJwtService },
                { provide: RedisRateLimiter, useValue: mockRateLimiter },
            ],
        }).compile();

        gateway = module.get<ChatGateway>(ChatGateway);
    });

    describe('handleConnection', () => {
        it('유효한 JWT 토큰으로 연결 시 client.data에 userId가 저장되고 전용 룸에 join한다', async () => {
            const client = mockSocket('valid-token');
            await gateway.handleConnection(client as unknown as ChatSocket);
            expect(client.data.userId).toBe(42);
            expect(client.data.userRole).toBe('ROLE_USER');
            expect(client.join).toHaveBeenCalledWith('user:42');
            expect(client.disconnect).not.toHaveBeenCalled();
        });

        it('토큰 없이 연결하면 exception 이벤트를 emit하고 disconnect된다', async () => {
            const client = mockSocket(undefined);
            await gateway.handleConnection(client as unknown as ChatSocket);
            expect(client.emit).toHaveBeenCalledWith('exception', { message: '인증 실패: 토큰이 전달되지 않았습니다' });
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
