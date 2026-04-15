import {
    WebSocketGateway,
    SubscribeMessage,
    MessageBody,
} from '@nestjs/websockets';
import { ChatService } from './chat.service';
import { MessagePayload } from './message-payload.interface';

@WebSocketGateway()
export class ChatGateway {
    constructor(private readonly chatService: ChatService) {}

    @SubscribeMessage('message')
    handleMessage(@MessageBody() data: MessagePayload) {
        return this.chatService.sendMessage(data);
    }
}
