import { Injectable, Logger } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model } from 'mongoose';
import { Message, MessageDocument } from './schema/message.schema';
import { MessagePayloadDto } from './dto/message-payload.dto';

@Injectable()
export class ChatService {
    private readonly logger = new Logger(ChatService.name);

    constructor(@InjectModel(Message.name) private messageModel: Model<Message>) {}

    async saveMessage(payload: MessagePayloadDto): Promise<MessageDocument | null> {
        try {
            return await this.messageModel.create(payload);
        } catch (e) {
            this.logger.error('메시지 저장 실패', e);
            return null;
        }
    }
}
