import {
    WebSocketGateway,
    WebSocketServer,
    SubscribeMessage,
    MessageBody,
    ConnectedSocket,
    OnGatewayConnection,
    OnGatewayDisconnect,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
import { ChatService } from './chat.service';
import { MessagePayloadDto } from './dto/message-payload.dto';
import { JoinChannelDto } from './dto/join-channel.dto';

@WebSocketGateway({ namespace: '/chat' })
export class ChatGateway implements OnGatewayConnection, OnGatewayDisconnect {
    @WebSocketServer() server!: Server;

    constructor(private readonly chatService: ChatService) {}

    handleConnection(client: Socket) {
        console.log(`connected: ${client.id}`);
    }

    handleDisconnect(client: Socket) {
        console.log(`disconnected: ${client.id}`);
    }

    @SubscribeMessage('join')
    handleJoin(@ConnectedSocket() client: Socket, @MessageBody() payload: JoinChannelDto) {
        client.join(`channel:${payload.channelId}`);
    }

    @SubscribeMessage('message')
    async handleMessage(
        @ConnectedSocket() client: Socket,
        @MessageBody() payload: MessagePayloadDto,
    ) {
        const saved = await this.chatService.saveMessage(payload);
        if (!saved) {
            client.emit('error', { message: 'Failed to save message' });
            return;
        }
        this.server.to(`channel:${payload.channelId}`).emit('message', saved);
        return { success: true };
    }
}
