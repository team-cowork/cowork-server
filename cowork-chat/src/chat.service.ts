import { Injectable } from '@nestjs/common';
import { MessagePayload } from './message-payload.dto';

@Injectable()
export class ChatService {
    sendMessage(data: MessagePayload) {
        return data;
    }
}
