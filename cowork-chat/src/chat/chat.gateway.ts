import { forwardRef, Inject, Logger } from '@nestjs/common';
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
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { Server, Socket } from 'socket.io';
import { ChatService } from './chat.service';
import { ChatMessageConsumer } from './kafka/chat-message.consumer';
import { GithubIssueResultConsumer } from './kafka/github-issue-result.consumer';
import { ChannelEventConsumer } from './kafka/channel-event.consumer';
import { ProjectEventConsumer } from './kafka/project-event.consumer';
import { MembershipConsumer } from '../membership/membership.consumer';
import { JoinChannelDto } from './dto/join-channel.dto';
import { UserRole } from '../common/enum/user-role.enum';

/**
 * `/chat` 네임스페이스의 WebSocket 게이트웨이.
 *
 * 연결 시 `auth.token`(JWT)을 검증해 `client.data`에 `userId`와 `userRole`을 저장한다.
 * 인증 실패 시 `exception` 이벤트를 emit하고 소켓을 즉시 끊는다.
 * `afterInit`에서 Socket.IO `Server` 인스턴스를 Kafka 컨슈머에 주입해
 * Kafka 메시지를 Socket.IO 룸으로 브로드캐스트할 수 있게 한다.
 */
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
        @Inject(forwardRef(() => ChatService))
        private readonly chatService: ChatService,
        private readonly consumer: ChatMessageConsumer,
        private readonly githubIssueResultConsumer: GithubIssueResultConsumer,
        private readonly channelEventConsumer: ChannelEventConsumer,
        private readonly projectEventConsumer: ProjectEventConsumer,
        private readonly membershipConsumer: MembershipConsumer,
        private readonly configService: ConfigService,
        private readonly jwtService: JwtService,
    ) {}

    /**
     * Socket.IO 서버 초기화 후 호출된다.
     * Kafka 컨슈머에 Socket.IO `Server` 인스턴스를 주입해 룸 브로드캐스트를 활성화한다.
     *
     * @param server - 초기화된 Socket.IO 서버 인스턴스
     */
    afterInit(server: Server) {
        this.consumer.setSocketServer(server);
        this.githubIssueResultConsumer.setSocketServer(server);
        this.channelEventConsumer.setSocketServer(server);
        this.projectEventConsumer.setSocketServer(server);
        this.membershipConsumer.setSocketServer(server);
    }

    /**
     * 클라이언트 연결 시 JWT를 검증하고 `client.data`에 인증 정보를 설정한다.
     * `auth.token`이 없거나 유효하지 않으면 `exception` 이벤트 후 소켓을 끊는다.
     *
     * @param client - 연결된 Socket.IO 소켓
     */
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
            client.join(`user:${userId}`);
            this.logger.log(`연결됨: ${client.id} (userId=${userId})`);
        } catch (err) {
            const message = err instanceof Error ? err.message : '인증 실패';
            this.logger.warn(`인증 실패로 연결 거부: ${client.id} - ${message}`);
            client.emit('exception', { message: '인증 실패: ' + message });
            client.disconnect();
        }
    }

    /**
     * 클라이언트 연결 해제 시 호출된다.
     *
     * @param client - 연결 해제된 Socket.IO 소켓
     */
    handleDisconnect(client: Socket) {
        this.logger.log(`연결 해제: ${client.id}`);
    }

    /**
     * `join` 이벤트 핸들러. 채널 멤버십을 확인한 뒤 `chat:{channelId}` 룸에 참여한다.
     * 멤버가 아니면 `error` 이벤트를 emit하고 룸에 참여하지 않는다.
     *
     * @param client - 요청한 소켓 (data.userId가 설정되어 있어야 함)
     * @param payload - 참여할 채널 ID
     */
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

    /**
     * `leave` 이벤트 핸들러. `chat:{channelId}` 룸에서 나간다.
     *
     * @param client - 요청한 소켓
     * @param payload - 나갈 채널 ID
     */
    @SubscribeMessage('leave')
    handleLeave(@ConnectedSocket() client: Socket, @MessageBody() payload: JoinChannelDto) {
        client.leave(`chat:${payload.channelId}`);
    }

    /**
     * `join:team` 이벤트 핸들러. `team:{teamId}` 룸에 참여한다.
     * 채널/프로젝트 생성·수정·삭제 이벤트 수신에 사용한다.
     */
    @SubscribeMessage('join:team')
    async handleJoinTeam(@ConnectedSocket() client: Socket, @MessageBody() payload: { teamId: number }) {
        const { userId } = client.data;
        const isMember = await this.chatService.isTeamMember(payload.teamId, userId);
        if (!isMember) {
            client.emit('error', { message: '팀 접근 권한이 없습니다' });
            return;
        }
        client.join(`team:${payload.teamId}`);
    }

    /**
     * `leave:team` 이벤트 핸들러. `team:{teamId}` 룸에서 나간다.
     */
    @SubscribeMessage('leave:team')
    handleLeaveTeam(@ConnectedSocket() client: Socket, @MessageBody() payload: { teamId: number }) {
        client.leave(`team:${payload.teamId}`);
    }
}
