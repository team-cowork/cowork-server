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
import { Server, Socket } from 'socket.io';
import { ChatService } from './chat.service';
import { ChatMessageConsumer } from './kafka/chat-message.consumer';
import { GithubIssueResultConsumer } from './kafka/github-issue-result.consumer';
import { RequestContextUtil } from '../common/util/request-context.util';
import { JoinChannelDto } from './dto/join-channel.dto';

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
    ) {}

    afterInit(server: Server) {
        this.consumer.setSocketServer(server);
        this.githubIssueResultConsumer.setSocketServer(server);
    }

    async handleConnection(client: Socket) {
        try {
            const userId = RequestContextUtil.getUserId(client.handshake.headers as any);
            client.data.userId = userId;
            client.data.userRole = RequestContextUtil.getUserRole(client.handshake.headers as any);
            this.logger.log(`연결됨: ${client.id} (userId=${userId})`);
        } catch {
            this.logger.warn(`인증 실패로 연결 거부: ${client.id}`);
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
