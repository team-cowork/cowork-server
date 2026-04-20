import { Test, TestingModule } from '@nestjs/testing';
import { ChatGateway } from './chat.gateway';
import { ChatService } from './chat.service';
import { ChatMessageConsumer } from './kafka/chat-message.consumer';

const mockSocket = (headers: Record<string, string> = { 'x-user-id': '42', 'x-user-role': 'ROLE_USER' }) => ({
    id: 'socket-1',
    handshake: { headers },
    data: {} as Record<string, unknown>,
    join: jest.fn(),
    leave: jest.fn(),
    emit: jest.fn(),
    disconnect: jest.fn(),
});

const mockChatService = {
    isMember: jest.fn(),
};

const mockConsumer = {
    setSocketServer: jest.fn(),
};

describe('ChatGateway', () => {
    let gateway: ChatGateway;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            providers: [
                ChatGateway,
                { provide: ChatService, useValue: mockChatService },
                { provide: ChatMessageConsumer, useValue: mockConsumer },
            ],
        }).compile();

        gateway = module.get<ChatGateway>(ChatGateway);
        jest.clearAllMocks();
    });

    describe('handleConnection', () => {
        it('유효한 x-user-id 헤더로 연결 시 client.data에 userId가 저장된다', async () => {
            const client = mockSocket();
            await gateway.handleConnection(client as any);
            expect(client.data.userId).toBe(42);
            expect(client.data.userRole).toBe('ROLE_USER');
            expect(client.disconnect).not.toHaveBeenCalled();
        });

        it('x-user-id 헤더 없이 연결하면 disconnect된다', async () => {
            const client = mockSocket({});
            await gateway.handleConnection(client as any);
            expect(client.disconnect).toHaveBeenCalled();
        });
    });

    describe('handleJoin', () => {
        it('채널 멤버이면 room에 참가한다', async () => {
            mockChatService.isMember.mockResolvedValue(true);
            const client = mockSocket();
            client.data.userId = 42;

            await gateway.handleJoin(client as any, { channelId: 1 });

            expect(client.join).toHaveBeenCalledWith('chat:1');
            expect(client.emit).not.toHaveBeenCalledWith('error', expect.anything());
        });

        it('채널 멤버가 아니면 error 이벤트를 emit하고 join하지 않는다', async () => {
            mockChatService.isMember.mockResolvedValue(false);
            const client = mockSocket();
            client.data.userId = 42;

            await gateway.handleJoin(client as any, { channelId: 1 });

            expect(client.join).not.toHaveBeenCalled();
            expect(client.emit).toHaveBeenCalledWith('error', { message: '채널 접근 권한이 없습니다' });
        });
    });

    describe('handleLeave', () => {
        it('채널 room에서 퇴장한다', () => {
            const client = mockSocket();
            gateway.handleLeave(client as any, { channelId: 1 });
            expect(client.leave).toHaveBeenCalledWith('chat:1');
        });
    });
});
