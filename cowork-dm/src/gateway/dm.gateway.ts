import { forwardRef, Inject, Logger } from '@nestjs/common';
import {
    WebSocketGateway,
    WebSocketServer,
    SubscribeMessage,
    MessageBody,
    ConnectedSocket,
    OnGatewayConnection,
    OnGatewayDisconnect,
} from '@nestjs/websockets';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { Server, Socket } from 'socket.io';
import { ConversationRepository } from '../conversation/conversation.repository';
import { MessageService } from '../message/message.service';
import { UserRole } from '../common/enum/user-role.enum';
import { MessageResponseDto } from '../message/dto/message-response.dto';

@WebSocketGateway({
    namespace: '/dm',
    path: '/dm-ws',
    cors: {
        origin: true,
        methods: ['GET', 'POST'],
        credentials: true,
    },
})
export class DmGateway implements OnGatewayConnection, OnGatewayDisconnect {
    private readonly logger = new Logger(DmGateway.name);

    @WebSocketServer() server!: Server;

    constructor(
        private readonly conversationRepository: ConversationRepository,
        @Inject(forwardRef(() => MessageService))
        private readonly messageService: MessageService,
        private readonly configService: ConfigService,
        private readonly jwtService: JwtService,
    ) {}

    async handleConnection(client: Socket) {
        try {
            const token = client.handshake.auth?.token as string | undefined;
            if (!token) throw new Error('토큰이 전달되지 않았습니다');

            const payload = await this.jwtService.verifyAsync<{ sub: string; role?: string }>(token);
            const userId = Number(payload.sub);
            if (isNaN(userId) || userId <= 0) throw new Error('토큰의 sub 클레임이 유효하지 않습니다');

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

    handleDisconnect(client: Socket) {
        this.logger.log(`연결 해제: ${client.id}`);
    }

    @SubscribeMessage('join')
    async handleJoin(
        @ConnectedSocket() client: Socket,
        @MessageBody() payload?: { conversationId: string },
    ) {
        const conversationId = payload?.conversationId ?? '';
        if (!conversationId) {
            client.emit('error', { message: '대화방 ID가 필요합니다' });
            return;
        }
        const { userId } = client.data;
        const conversation = await this.conversationRepository.findById(conversationId);
        if (!conversation) {
            client.emit('error', { message: '대화방을 찾을 수 없습니다' });
            return;
        }

        const isParticipant = conversation.participants.some((p) => p.userId === userId);
        if (!isParticipant) {
            client.emit('error', { message: '접근 권한이 없습니다' });
            return;
        }

        client.join(`dm:${conversationId}`);
        this.logger.log(`userId=${userId} joined dm:${conversationId}`);
    }

    @SubscribeMessage('leave')
    handleLeave(
        @ConnectedSocket() client: Socket,
        @MessageBody() payload?: { conversationId: string },
    ) {
        const conversationId = payload?.conversationId ?? '';
        if (!conversationId) return;
        client.leave(`dm:${conversationId}`);
    }

    @SubscribeMessage('typing:start')
    handleTypingStart(
        @ConnectedSocket() client: Socket,
        @MessageBody() payload?: { conversationId: string },
    ) {
        const conversationId = payload?.conversationId ?? '';
        if (!conversationId) return;
        const room = `dm:${conversationId}`;
        if (!client.rooms.has(room)) return;

        client.to(room).emit('typing', {
            conversationId,
            userId: client.data.userId,
            isTyping: true,
        });
    }

    @SubscribeMessage('typing:stop')
    handleTypingStop(
        @ConnectedSocket() client: Socket,
        @MessageBody() payload?: { conversationId: string },
    ) {
        const conversationId = payload?.conversationId ?? '';
        if (!conversationId) return;
        const room = `dm:${conversationId}`;
        if (!client.rooms.has(room)) return;

        client.to(room).emit('typing', {
            conversationId,
            userId: client.data.userId,
            isTyping: false,
        });
    }

    broadcastNewMessage(conversationId: string, message: MessageResponseDto, receiverId: number | null) {
        this.server.to(`dm:${conversationId}`).emit('message:new', message);
        // 송신자·수신자 개인 룸에 emit (오프라인 상태에서 목록 실시간 갱신용)
        this.server.to(`user:${message.authorId}`).emit('conversation:updated', {
            conversationId,
            lastMessageAt: message.createdAt,
        });
        if (receiverId !== null) {
            this.server.to(`user:${receiverId}`).emit('conversation:updated', {
                conversationId,
                lastMessageAt: message.createdAt,
            });
        }
    }

    broadcastMessageUpdated(conversationId: string, message: MessageResponseDto) {
        this.server.to(`dm:${conversationId}`).emit('message:updated', message);
    }

    broadcastMessageDeleted(conversationId: string, messageId: string) {
        this.server.to(`dm:${conversationId}`).emit('message:deleted', { messageId });
    }

    broadcastReactionUpdated(
        conversationId: string,
        messageId: string,
        reactions: Array<{ emoji: string; count: number; myReaction: boolean }>,
    ) {
        this.server.to(`dm:${conversationId}`).emit('reaction:updated', { messageId, reactions });
    }

    broadcastReadUpdated(conversationId: string, userId: number, messageId: string) {
        this.server.to(`dm:${conversationId}`).emit('read:updated', { conversationId, userId, messageId });
    }
}
