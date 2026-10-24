import {
    forwardRef,
    Inject,
    Logger,
    OnModuleDestroy,
    UseFilters,
} from '@nestjs/common';
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
import {ConfigService} from '@nestjs/config';
import {JwtService} from '@nestjs/jwt';
import {Server, Socket, DefaultEventsMap} from 'socket.io';
import {DmParticipantConsumer} from '../dm/dm-participant.consumer';
import {UserRole} from '../common/enum/user-role.enum';
import {getOptionalConfig} from '../common/config/config.util';
import {RedisRateLimiter} from '../common/util/redis-rate-limiter';
import {GlobalExceptionFilter} from '../common/filter/global-exception.filter';
import {ProjectionReadinessService} from '../common/kafka/projection-readiness.service';
import {SocketIoRedisConnection} from '../common/adapter/redis-io.adapter';
import {isSafePositiveInteger} from '../common/util/safe-integer.util';
import {ChatService} from './chat.service';
import {ChatMessageConsumer} from './kafka/chat-message.consumer';
import {GithubIssueResultConsumer} from './kafka/github-issue-result.consumer';
import {ChatGithubIssueResultConsumer} from './kafka/chat-github-issue-result.consumer';
import {GithubRepoEventConsumer} from './kafka/github-repo-event.consumer';
import {ChannelEventConsumer} from './kafka/channel-event.consumer';
import {ProjectEventConsumer} from './kafka/project-event.consumer';
import {TeamRoleEventConsumer} from './kafka/team-role-event.consumer';
import {ChannelRolePolicyEventConsumer} from './kafka/channel-role-policy-event.consumer';
import {TeamMemberEventConsumer} from './kafka/team-member-event.consumer';
import {JoinChannelDto} from './dto';

const TYPING_RATE_LIMIT_KEY_PREFIX = 'chat:typingrate:';
const DEFAULT_TYPING_RATE_LIMIT_WINDOW_MS = 5000;
const DEFAULT_TYPING_RATE_LIMIT_MAX_REQUESTS = 20;
const PROJECTION_NOT_READY_MESSAGE = 'Kafka projections are synchronizing';
const SOCKET_ADAPTER_NOT_READY_MESSAGE = 'Socket.IO Redis adapter is not ready';
const LOCAL_ROOM_SWEEP_INTERVAL_MS = 30_000;

export type ChatSocketData = {
    userId: number;
    userRole: string;
};

export type ChatSocket = Socket<DefaultEventsMap, DefaultEventsMap, DefaultEventsMap, ChatSocketData>;

/**
 `/chat` 네임스페이스의 WebSocket 게이트웨이.

 연결 시 Gateway가 주입한 `X-User-Id`/`X-User-Role` 헤더를 우선 신뢰하고,
 헤더가 없으면 `auth.token`(JWT)을 직접 검증해 `client.data`에 `userId`와 `userRole`을 저장한다.
 인증은 handshake 미들웨어에서 하므로 실패하면 `connect` 없이 `connect_error`만 전달된다.
 `afterInit`에서 Socket.IO `Server` 인스턴스를 Kafka 컨슈머에 주입해
 Kafka 메시지를 Socket.IO 룸으로 브로드캐스트할 수 있게 한다.

 `app.useGlobalFilters()`로 등록한 전역 필터는 WebSocket Gateway 예외를 감지하지 못하므로
 (Nest `@nestjs/websockets`의 `ExceptionFiltersContext`는 전역 필터 메타데이터를 조회하지 않음),
 `GlobalExceptionFilter`를 클래스 레벨에 별도로 바인딩한다.
 */
@UseFilters(GlobalExceptionFilter)
@WebSocketGateway({
    namespace: '/chat',
    path: '/ws/chat',
    connectionStateRecovery: {
        maxDisconnectionDuration: 2 * 60 * 1000,
        skipMiddlewares: false,
    },
    cors: {
        origin: true, // Gateway에서 이미 제어되지만, 필요시 ConfigService로 주입 가능
        methods: ['GET', 'POST'],
        credentials: true,
    },
})
export class ChatGateway implements OnGatewayInit, OnGatewayConnection, OnGatewayDisconnect, OnModuleDestroy {
    private readonly logger = new Logger(ChatGateway.name);

    @WebSocketServer() server!: Server;

    private readonly typingRateLimitWindowMs: number;
    private readonly typingRateLimitMaxRequests: number;
    private roomSweepTimer?: ReturnType<typeof setInterval>;
    private roomSweepRunning = false;

    constructor(
        @Inject(forwardRef(() => ChatService))
        private readonly chatService: ChatService,
        private readonly consumer: ChatMessageConsumer,
        private readonly githubIssueResultConsumer: GithubIssueResultConsumer,
        private readonly chatGithubIssueResultConsumer: ChatGithubIssueResultConsumer,
        private readonly githubRepoEventConsumer: GithubRepoEventConsumer,
        private readonly channelEventConsumer: ChannelEventConsumer,
        private readonly projectEventConsumer: ProjectEventConsumer,
        private readonly dmParticipantConsumer: DmParticipantConsumer,
        private readonly teamRoleEventConsumer: TeamRoleEventConsumer,
        private readonly channelRolePolicyEventConsumer: ChannelRolePolicyEventConsumer,
        private readonly teamMemberEventConsumer: TeamMemberEventConsumer,
        configService: ConfigService,
        private readonly jwtService: JwtService,
        private readonly rateLimiter: RedisRateLimiter,
        private readonly projectionReadiness: ProjectionReadinessService,
        private readonly socketIoRedis: SocketIoRedisConnection,
    ) {
        this.typingRateLimitWindowMs = Number(
            getOptionalConfig(configService, 'CHAT_TYPING_RATE_LIMIT_WINDOW_MS') ?? DEFAULT_TYPING_RATE_LIMIT_WINDOW_MS,
        );
        this.typingRateLimitMaxRequests = Number(
            getOptionalConfig(configService, 'CHAT_TYPING_RATE_LIMIT_MAX_REQUESTS') ?? DEFAULT_TYPING_RATE_LIMIT_MAX_REQUESTS,
        );
    }

    /**
     Socket.IO 서버 초기화 후 호출된다.
     Kafka 컨슈머에 Socket.IO `Server` 인스턴스를 주입해 룸 브로드캐스트를 활성화한다.

     @param server - 초기화된 Socket.IO 서버 인스턴스
     */
    afterInit(server: Server) {
        server.use((socket: ChatSocket, next) => {
            const notReady = this.realtimeNotReadyReason();
            if (notReady !== undefined) {
                next(new Error(notReady));
                return;
            }

            socket.use((_packet, packetNext) => {
                const packetNotReady = this.realtimeNotReadyReason();
                if (packetNotReady !== undefined) {
                    packetNext(new Error(packetNotReady));
                    return;
                }

                packetNext();
            });
            void this.authenticate(socket).then(next);
        });
        this.consumer.setSocketServer(server);
        this.githubIssueResultConsumer.setSocketServer(server);
        this.chatGithubIssueResultConsumer.setSocketServer(server);
        this.githubRepoEventConsumer.setSocketServer(server);
        this.channelEventConsumer.setSocketServer(server);
        this.projectEventConsumer.setSocketServer(server);
        this.dmParticipantConsumer.setSocketServer(server);
        this.dmParticipantConsumer.setReadAccessEvaluator(async (channelId, userId) =>
            this.chatService.canReadChannel(channelId, userId));
        this.dmParticipantConsumer.setReadableEmitter(async (channelId, event, payload) =>
            this.chatService.emitToReadableChannelUsers(channelId, event, payload));
        this.teamRoleEventConsumer.setSocketServer(server);
        this.channelRolePolicyEventConsumer.setSocketServer(server);
        this.teamMemberEventConsumer.setSocketServer(server);
        this.roomSweepTimer = setInterval(() => {
            void this.sweepLocalRooms(server);
        }, LOCAL_ROOM_SWEEP_INTERVAL_MS);
    }

    onModuleDestroy(): void {
        clearInterval(this.roomSweepTimer);
    }

    /**
     회수 이벤트의 room 해제는 누락될 수 있다. `socketsLeave`는 Redis adapter로 요청만 발행하고 결과를 기다리지 않으며,
     재시도가 소진된 회수 이벤트는 readiness가 닫힌 재처리에서 회수를 건너뛴다. 그래서 이 인스턴스에 연결된 소켓의
     room을 주기적으로 현재 권한으로 다시 검증해 수렴시킨다. `local` 조회라 Redis 장애와 무관하게 동작한다.
     */
    private async sweepLocalRooms(server: Server): Promise<void> {
        if (this.roomSweepRunning) {
            return;
        }

        this.roomSweepRunning = true;
        try {
            // Ponytail: 로컬 소켓 전체를 한 번에 평가함, 접속자가 많아 조회가 무거워지면 소켓 묶음 단위로 나눠 평가
            const evicted = await this.chatService.evictUnauthorizedRooms(await server.local.fetchSockets());
            if (evicted > 0) {
                this.logger.warn(`Local room sweep evicted ${evicted} unauthorized room subscriptions`);
            }
        } catch (error) {
            this.logger.error('Local room sweep failed', error);
        } finally {
            this.roomSweepRunning = false;
        }
    }

    /**
     Projection 동기화가 끝나지 않았거나 Socket.IO Redis adapter가 준비되지 않았으면 그 이유를 반환한다.
     adapter가 준비되지 않은 동안 받은 가입은 다른 replica의 브로드캐스트·room 해제를 받지 못하므로 막는다.
     */
    private realtimeNotReadyReason(): string | undefined {
        if (!this.projectionReadiness.isReady()) {
            return PROJECTION_NOT_READY_MESSAGE;
        }

        return this.socketIoRedis.isReady() ? undefined : SOCKET_ADAPTER_NOT_READY_MESSAGE;
    }

    /**
     Handshake 단계에서 인증 정보를 확인해 `client.data`에 설정한다.
     실패하면 연결을 수락하지 않으므로 클라이언트는 `connect` 없이 `connect_error`만 받는다.

     @param client - handshake 중인 Socket.IO 소켓
     @returns 인증 실패 시 `connect_error`로 전달할 오류
     */
    private async authenticate(client: ChatSocket): Promise<Error | undefined> {
        try {
            const {userId, userRole} = await this.resolveIdentity(client);
            client.data.userId = userId;
            client.data.userRole = userRole;
        } catch (error) {
            const message = error instanceof Error ? error.message : '인증 실패';
            this.logger.warn(`Auth failed, rejecting connection: ${client.id} - ${message}`);
            return new Error('인증 실패: ' + message);
        }

        return undefined;
    }

    /**
     Handshake 미들웨어에서 인증된 소켓을 개인 room에 넣는다.

     @param client - 인증을 마친 Socket.IO 소켓
     */
    async handleConnection(client: ChatSocket) {
        const {userId} = client.data;
        this.joinRoom(client, `user:${userId}`);
        // Connection state recovery는 끊기기 전 room을 그대로 복원하므로, 끊긴 동안 회수된 room을 다시 검증한다.
        // 재검증 실패는 인증 실패가 아니므로 연결을 유지하고, 남은 room은 주기 sweep이 정리한다.
        if (client.recovered) {
            await this.chatService.evictUnauthorizedRooms([client])
                .catch((error: unknown) => {
                    this.logger.error(`Recovered room re-check failed: ${client.id}`, error);
                });
        }

        this.logger.log(`Connected: ${client.id} (userId=${userId})`);
    }

    /**
     Gateway가 주입한 `X-User-Id`/`X-User-Role` 헤더를 우선 신뢰하고,
     없으면 `auth.token`의 JWT를 직접 검증해 사용자 정보를 확인한다.
     */
    private async resolveIdentity(client: ChatSocket): Promise<{userId: number; userRole: string}> {
        const rawUserId = client.handshake.headers['x-user-id'];
        const headerUserId = Array.isArray(rawUserId) ? rawUserId[0] : rawUserId;
        if (typeof headerUserId === 'string') {
            const userId = Number(headerUserId);
            if (!isSafePositiveInteger(userId)) {
                throw new Error('X-User-Id 헤더가 유효하지 않습니다');
            }

            const rawUserRole = client.handshake.headers['x-user-role'];
            const headerUserRole = Array.isArray(rawUserRole) ? rawUserRole[0] : rawUserRole;
            const userRole = typeof headerUserRole === 'string' && headerUserRole !== '' ? headerUserRole : UserRole.USER;
            return {userId, userRole};
        }

        const token = client.handshake.auth?.token as string | undefined;
        if (token === undefined || token === '') {
            throw new Error('인증 정보가 전달되지 않았습니다');
        }

        const payload = await this.jwtService.verifyAsync<{sub: string; role?: string}>(token);
        const userId = Number(payload.sub);
        if (!isSafePositiveInteger(userId)) {
            throw new Error('토큰의 sub 클레임이 유효하지 않습니다');
        }

        return {userId, userRole: payload.role ?? UserRole.USER};
    }

    /**
     클라이언트 연결 해제 시 호출된다.

     @param client - 연결 해제된 Socket.IO 소켓
     */
    handleDisconnect(client: ChatSocket) {
        this.logger.log(`Disconnected: ${client.id}`);
    }

    /**
     `join` 이벤트 핸들러. 채널 멤버십을 확인한 뒤 `chat:{channelId}` 룸에 참여한다.
     멤버가 아니면 `error` 이벤트를 emit하고 룸에 참여하지 않는다.

     @param client - 요청한 소켓 (data.userId가 설정되어 있어야 함)
     @param payload - 참여할 채널 ID
     */
    @SubscribeMessage('join')
    async handleJoin(@ConnectedSocket() client: ChatSocket, @MessageBody() payload: JoinChannelDto) {
        if (payload == null || !isSafePositiveInteger(payload.channelId)) {
            client.emit('error', {message: '올바르지 않은 요청 형식입니다'});
            return;
        }

        const {userId} = client.data;
        const room = `chat:${payload.channelId}`;
        // 채팅방 멤버십이 곧 message_read 판정 결과이므로(브로드캐스트는 전송 시점에 다시 인가하지 않는다)
        // 인가를 통과하기 전에는 room에 넣지 않는다.
        const canRead = await this.chatService.canReadChannel(payload.channelId, userId);
        if (!canRead) {
            client.emit('error', {message: '채널 접근 권한이 없습니다'});
            return;
        }

        this.joinRoom(client, room);
        // 가입 직전에 처리된 회수 이벤트는 아직 room에 없던 이 소켓을 해제 대상으로 삼지 못했다.
        // 가입 뒤 한 번 더 확인해 그 구간에 회수된 권한이 room 멤버십으로 남지 않게 한다.
        const isStillAllowed = await this.chatService.canReadChannel(payload.channelId, userId).catch((error: unknown) => {
            this.leaveRoom(client, room);
            throw error;
        });
        if (!isStillAllowed) {
            this.leaveRoom(client, room);
            client.emit('error', {message: '채널 접근 권한이 없습니다'});
            return;
        }

        this.logger.log(`userId=${userId} joined ${room}`);
    }

    /**
     `leave` 이벤트 핸들러. `chat:{channelId}` 룸에서 나간다.

     @param client - 요청한 소켓
     @param payload - 나갈 채널 ID
     */
    @SubscribeMessage('leave')
    handleLeave(@ConnectedSocket() client: ChatSocket, @MessageBody() payload: JoinChannelDto) {
        if (payload == null || !isSafePositiveInteger(payload.channelId)) {
            client.emit('error', {message: '올바르지 않은 요청 형식입니다'});
            return;
        }

        this.leaveRoom(client, `chat:${payload.channelId}`);
    }

    /**
     `typing:start` / `typing:stop` 이벤트 핸들러.
     같은 채널 룸의 다른 참여자에게 `typing` 이벤트를 릴레이한다.
     사용자별 시간창 내 호출 한도를 초과하면 조용히 무시한다(별도 에러 emit 없음).
     */
    @SubscribeMessage('typing:start')
    async handleTypingStart(@ConnectedSocket() client: ChatSocket, @MessageBody() payload: JoinChannelDto) {
        await this.relayTyping(client, payload, true);
    }

    @SubscribeMessage('typing:stop')
    async handleTypingStop(@ConnectedSocket() client: ChatSocket, @MessageBody() payload: JoinChannelDto) {
        await this.relayTyping(client, payload, false);
    }

    private async relayTyping(client: ChatSocket, payload: JoinChannelDto, isTyping: boolean) {
        if (payload == null || !isSafePositiveInteger(payload.channelId)) {
            return;
        }

        const room = `chat:${payload.channelId}`;
        if (!client.rooms.has(room)) {
            return;
        }

        if (!(await this.chatService.canReadChannel(payload.channelId, client.data.userId))) {
            await client.leave(room);
            client.emit('channel:access:revoked', {channelId: payload.channelId});
            return;
        }

        const isAllowed = await this.rateLimiter.tryAcquire(
            `${TYPING_RATE_LIMIT_KEY_PREFIX}${client.data.userId}`,
            this.typingRateLimitWindowMs,
            this.typingRateLimitMaxRequests,
        );
        if (!isAllowed) {
            return;
        }

        await this.chatService.emitToReadableChannelUsers(
            payload.channelId,
            'typing',
            {
                channelId: payload.channelId,
                userId: client.data.userId,
                isTyping,
            },
            client.id,
        );
    }

    /**
     `join:team` 이벤트 핸들러. `team:{teamId}` 룸에 참여한다.
     채널/프로젝트 생성·수정·삭제 이벤트 수신에 사용한다.
     */
    @SubscribeMessage('join:team')
    async handleJoinTeam(@ConnectedSocket() client: ChatSocket, @MessageBody() payload: {teamId: number}) {
        if (payload == null || !isSafePositiveInteger(payload.teamId)) {
            client.emit('error', {message: '올바르지 않은 요청 형식입니다'});
            return;
        }

        const {userId} = client.data;
        const room = `team:${payload.teamId}`;
        // HandleJoin과 같은 이유로 권한 확인보다 먼저 가입한다. 팀 room 전달도 전송 시점에 다시 인가한다.
        this.joinRoom(client, room);
        const isMember = await this.chatService.isTeamMember(payload.teamId, userId).catch((error: unknown) => {
            this.leaveRoom(client, room);
            throw error;
        });
        if (isMember) {
            return;
        }

        this.leaveRoom(client, room);
        client.emit('error', {message: '팀 접근 권한이 없습니다'});
    }

    /**
     `leave:team` 이벤트 핸들러. `team:{teamId}` 룸에서 나간다.
     */
    @SubscribeMessage('leave:team')
    handleLeaveTeam(@ConnectedSocket() client: ChatSocket, @MessageBody() payload: {teamId: number}) {
        if (payload == null || !isSafePositiveInteger(payload.teamId)) {
            client.emit('error', {message: '올바르지 않은 요청 형식입니다'});
            return;
        }

        this.leaveRoom(client, `team:${payload.teamId}`);
    }

    private joinRoom(client: ChatSocket, room: string): void {
        Promise.resolve(client.join(room)).catch((error: unknown) => {
            this.logger.error(`Failed to join room (room=${room}): ${String(error)}`);
        });
    }

    private leaveRoom(client: ChatSocket, room: string): void {
        Promise.resolve(client.leave(room)).catch((error: unknown) => {
            this.logger.error(`Failed to leave room (room=${room}): ${String(error)}`);
        });
    }
}
