import { Injectable } from '@nestjs/common';
import { MessagePayload } from './message-payload.interface';

@Injectable()
export class ChatService {
    sendMessage(data: MessagePayload) {
        return data;
    }
}
