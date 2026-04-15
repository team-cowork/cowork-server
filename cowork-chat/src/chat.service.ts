import { Injectable } from '@nestjs/common';

@Injectable()
export class ChatService {
    sendMessage(data: any) {
        return data;
    }
}
