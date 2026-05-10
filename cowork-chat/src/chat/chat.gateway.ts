import {
    WebSocketGateway,
    WebSocketServer,
    SubscribeMessage,
    MessageBody,
    ConnectedSocket,
    OnGatewayConnection,
    OnGatewayDisconnect,
    OnGatewayInit,
} from '@nestjs/websockets';
import { Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { Server, Socket } from 'socket.io';
import { ChatService } from './chat.service';
import { ChatMessageConsumer } from './kafka/chat-message.consumer';
import { GithubIssueResultConsumer } from './kafka/github-issue-result.consumer';
import { JoinChannelDto } from './dto/join-channel.dto';
import { UserRole } from '../common/enum/user-role.enum';

@WebSocketGateway({
    namespace: '/chat',
    path: '/chat-ws',
    cors: {
        origin: true, // Gateway에서 이미 제어되지만, 필요시 ConfigService로 주입 가능
        methods: ['GET', 'POST'],
        credentials: true,
    },
})
export class ChatGateway implements OnGatewayInit, OnGatewayConnection, OnGatewayDisconnect {
    private readonly logger = new Logger(ChatGateway.name);

    @WebSocketServer() server!: Server;

    constructor(
        private readonly chatService: ChatService,
        private readonly consumer: ChatMessageConsumer,
        private readonly githubIssueResultConsumer: GithubIssueResultConsumer,
        private readonly configService: ConfigService,
        private readonly jwtService: JwtService,
    ) {}

    afterInit(server: Server) {
        this.consumer.setSocketServer(server);
        this.githubIssueResultConsumer.setSocketServer(server);
    }

    async handleConnection(client: Socket) {
        try {
            const token = client.handshake.auth?.token as string | undefined;
            if (!token) {
                throw new Error('토큰이 전달되지 않았습니다');
            }

            const payload = await this.jwtService.verifyAsync<{ sub: string; role?: string }>(token);
            const userId = Number(payload.sub);
            if (isNaN(userId) || userId <= 0) {
                throw new Error('토큰의 sub 클레임이 유효하지 않습니다');
            }

            client.data.userId = userId;
            client.data.userRole = payload.role ?? UserRole.USER;
            this.logger.log(`연결됨: ${client.id} (userId=${userId})`);
        } catch (err) {
            const message = err instanceof Error ? err.message : '인증 실패';
            this.logger.warn(`인증 실패로 연결 거부: ${client.id} - ${message}`);
            client.emit('exception', { message: '인증 실패: ' + message });
            client.disconnect();
        }
    }

    handleDisconnect(client: Socket) {
        this.logger.log(`연결 해제: ${client.id}`);
    }

    @SubscribeMessage('join')
    async handleJoin(@ConnectedSocket() client: Socket, @MessageBody() payload: JoinChannelDto) {
        const { userId } = client.data;
        const isMember = await this.chatService.isMember(payload.channelId, userId);
        if (!isMember) {
            client.emit('error', { message: '채널 접근 권한이 없습니다' });
            return;
        }
        client.join(`chat:${payload.channelId}`);
        this.logger.log(`userId=${userId} joined chat:${payload.channelId}`);
    }

    @SubscribeMessage('leave')
    handleLeave(@ConnectedSocket() client: Socket, @MessageBody() payload: JoinChannelDto) {
        client.leave(`chat:${payload.channelId}`);
    }
}
