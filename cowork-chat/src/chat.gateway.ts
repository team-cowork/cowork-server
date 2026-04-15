import {
    WebSocketGateway,
    SubscribeMessage,
    MessageBody,
} from '@nestjs/websockets';
import { ChatService } from './chat.service';

@WebSocketGateway()
export class ChatGateway {
    constructor(private readonly chatService: ChatService) {}

    @SubscribeMessage('message')
    handleMessage(@MessageBody() data: any) {
        return this.chatService.sendMessage(data);
    }
}
