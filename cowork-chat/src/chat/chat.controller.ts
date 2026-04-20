import {
    Controller,
    Get,
    Post,
    Patch,
    Delete,
    Param,
    Body,
    Query,
    Headers,
    HttpCode,
    HttpStatus,
} from '@nestjs/common';
import { ChatService } from './chat.service';
import { ChatGateway } from './chat.gateway';
import { SendMessageDto } from './dto/send-message.dto';
import { EditMessageDto } from './dto/edit-message.dto';
import { GetMessagesDto } from './dto/get-messages.dto';
import { RequestContextUtil } from '../common/util/request-context.util';
import { ChatMessageProducer } from './kafka/chat-message.producer';

@Controller('channels/:channelId')
export class ChatController {
    constructor(
        private readonly chatService: ChatService,
        private readonly chatGateway: ChatGateway,
        private readonly producer: ChatMessageProducer,
    ) {}

    @Post('messages')
    @HttpCode(HttpStatus.CREATED)
    async sendMessage(
        @Param('channelId') channelId: string,
        @Body() dto: SendMessageDto,
        @Headers() headers: Record<string, string>,
    ) {
        const userId = RequestContextUtil.getUserId(headers);
        const userRole = RequestContextUtil.getUserRole(headers);

        await this.chatService.checkMembership(Number(channelId), userId);
        await this.producer.sendMessage({ ...dto, channelId: Number(channelId) }, userId, userRole);

        return { queued: true };
    }

    @Get('messages')
    async getMessages(
        @Param('channelId') channelId: string,
        @Query() query: GetMessagesDto,
        @Headers() headers: Record<string, string>,
    ) {
        const userId = RequestContextUtil.getUserId(headers);
        await this.chatService.checkMembership(Number(channelId), userId);
        return this.chatService.getMessages(Number(channelId), query.before);
    }

    @Patch('messages/:messageId')
    async editMessage(
        @Param('channelId') channelId: string,
        @Param('messageId') messageId: string,
        @Body() dto: EditMessageDto,
        @Headers() headers: Record<string, string>,
    ) {
        const userId = RequestContextUtil.getUserId(headers);
        const userRole = RequestContextUtil.getUserRole(headers);
        await this.chatService.checkMembership(Number(channelId), userId);
        const updated = await this.chatService.editMessage(messageId, userId, dto, userRole);

        this.chatGateway.server
            ?.to(`chat:${Number(channelId)}`)
            .emit('message:edited', {
                messageId,
                content: updated.content,
                editedAt: new Date().toISOString(),
            });

        return updated;
    }

    @Delete('messages/:messageId')
    async deleteMessage(
        @Param('channelId') channelId: string,
        @Param('messageId') messageId: string,
        @Headers() headers: Record<string, string>,
    ) {
        const userId = RequestContextUtil.getUserId(headers);
        const userRole = RequestContextUtil.getUserRole(headers);
        await this.chatService.checkMembership(Number(channelId), userId);
        const result = await this.chatService.deleteMessage(messageId, userId, userRole);

        this.chatGateway.server
            ?.to(`chat:${Number(channelId)}`)
            .emit('message:deleted', { messageId });

        return result;
    }
}
