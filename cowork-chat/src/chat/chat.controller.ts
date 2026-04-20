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
    ParseIntPipe,
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
        @Param('channelId', ParseIntPipe) channelId: number,
        @Body() dto: SendMessageDto,
        @Headers() headers: Record<string, string>,
    ) {
        const userId = RequestContextUtil.getUserId(headers);
        const userRole = RequestContextUtil.getUserRole(headers);

        await this.chatService.checkMembership(channelId, userId);
        await this.producer.sendMessage({ ...dto, channelId }, userId, userRole);

        return { queued: true };
    }

    @Get('messages')
    async getMessages(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Query() query: GetMessagesDto,
        @Headers() headers: Record<string, string>,
    ) {
        const userId = RequestContextUtil.getUserId(headers);
        await this.chatService.checkMembership(channelId, userId);
        return this.chatService.getMessages(channelId, query.before);
    }

    @Patch('messages/:messageId')
    async editMessage(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Param('messageId') messageId: string,
        @Body() dto: EditMessageDto,
        @Headers() headers: Record<string, string>,
    ) {
        const userId = RequestContextUtil.getUserId(headers);
        const userRole = RequestContextUtil.getUserRole(headers);
        await this.chatService.checkMembership(channelId, userId);
        const updated = await this.chatService.editMessage(messageId, userId, dto, userRole);

        this.chatGateway.server
            ?.to(`chat:${channelId}`)
            .emit('message:edited', {
                messageId,
                content: updated.content,
                editedAt: (updated as any).updatedAt?.toISOString() ?? new Date().toISOString(),
            });

        return updated;
    }

    @Delete('messages/:messageId')
    async deleteMessage(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Param('messageId') messageId: string,
        @Headers() headers: Record<string, string>,
    ) {
        const userId = RequestContextUtil.getUserId(headers);
        const userRole = RequestContextUtil.getUserRole(headers);
        await this.chatService.checkMembership(channelId, userId);
        const result = await this.chatService.deleteMessage(messageId, userId, userRole);

        this.chatGateway.server
            ?.to(`chat:${channelId}`)
            .emit('message:deleted', { messageId });

        return result;
    }
}
