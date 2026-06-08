import {
    Body,
    Controller,
    Delete,
    Get,
    HttpCode,
    HttpStatus,
    Param,
    Patch,
    Post,
    Query,
} from '@nestjs/common';
import {
    ApiCreatedResponse,
    ApiForbiddenResponse,
    ApiNoContentResponse,
    ApiNotFoundResponse,
    ApiOkResponse,
    ApiOperation,
    ApiParam,
    ApiTags,
} from '@nestjs/swagger';
import { MessageService } from './message.service';
import { SendMessageDto } from './dto/send-message.dto';
import { EditMessageDto } from './dto/edit-message.dto';
import { GetMessagesDto } from './dto/get-messages.dto';
import { ReactMessageDto } from './dto/react-message.dto';
import { MessageResponseDto } from './dto/message-response.dto';
import { UserId } from '../common/decorator/user.decorator';

@ApiTags('messages')
@Controller('conversations/:conversationId/messages')
export class MessageController {
    constructor(private readonly messageService: MessageService) {}

    @Get()
    @ApiOperation({ summary: '메시지 목록 조회 (커서 페이지네이션, 최신순 최대 100건)' })
    @ApiParam({ name: 'conversationId', description: '대화방 ObjectId' })
    @ApiOkResponse({ type: [MessageResponseDto] })
    @ApiNotFoundResponse({ description: '대화방 없음' })
    @ApiForbiddenResponse({ description: '대화방 참여자 아님' })
    getMessages(
        @UserId() userId: number,
        @Param('conversationId') conversationId: string,
        @Query() query: GetMessagesDto,
    ) {
        return this.messageService.getMessages(conversationId, userId, query.before);
    }

    @Post()
    @ApiOperation({ summary: '메시지 전송' })
    @ApiParam({ name: 'conversationId', description: '대화방 ObjectId' })
    @ApiCreatedResponse({ type: MessageResponseDto })
    @ApiNotFoundResponse({ description: '대화방 없음' })
    @ApiForbiddenResponse({ description: '대화방 참여자 아님 또는 수신자에게 차단됨' })
    sendMessage(
        @UserId() userId: number,
        @Param('conversationId') conversationId: string,
        @Body() dto: SendMessageDto,
    ) {
        return this.messageService.sendMessage(conversationId, userId, dto);
    }

    @Patch(':messageId')
    @ApiOperation({ summary: '메시지 수정 — 편집 이력이 기록되며 isEdited=true 로 설정됨' })
    @ApiParam({ name: 'conversationId', description: '대화방 ObjectId' })
    @ApiParam({ name: 'messageId', description: '수정할 메시지 ObjectId' })
    @ApiOkResponse({ type: MessageResponseDto })
    @ApiNotFoundResponse({ description: '메시지 없음' })
    @ApiForbiddenResponse({ description: '본인 메시지 아님' })
    editMessage(
        @UserId() userId: number,
        @Param('conversationId') conversationId: string,
        @Param('messageId') messageId: string,
        @Body() dto: EditMessageDto,
    ) {
        return this.messageService.editMessage(conversationId, messageId, userId, dto.content);
    }

    @Delete(':messageId')
    @HttpCode(HttpStatus.NO_CONTENT)
    @ApiOperation({ summary: '메시지 삭제' })
    @ApiParam({ name: 'conversationId', description: '대화방 ObjectId' })
    @ApiParam({ name: 'messageId', description: '삭제할 메시지 ObjectId' })
    @ApiNoContentResponse({ description: '삭제 성공' })
    @ApiNotFoundResponse({ description: '메시지 없음' })
    @ApiForbiddenResponse({ description: '본인 메시지 아님' })
    deleteMessage(
        @UserId() userId: number,
        @Param('conversationId') conversationId: string,
        @Param('messageId') messageId: string,
    ) {
        return this.messageService.deleteMessage(conversationId, messageId, userId);
    }

    @Post(':messageId/reactions')
    @HttpCode(HttpStatus.NO_CONTENT)
    @ApiOperation({ summary: '이모지 리액션 추가 또는 제거' })
    @ApiParam({ name: 'conversationId', description: '대화방 ObjectId' })
    @ApiParam({ name: 'messageId', description: '리액션 대상 메시지 ObjectId' })
    @ApiNoContentResponse({ description: '처리 성공' })
    @ApiNotFoundResponse({ description: '대화방 또는 메시지 없음' })
    @ApiForbiddenResponse({ description: '대화방 참여자 아님' })
    reactMessage(
        @UserId() userId: number,
        @Param('conversationId') conversationId: string,
        @Param('messageId') messageId: string,
        @Body() dto: ReactMessageDto,
    ) {
        return this.messageService.reactMessage(conversationId, messageId, userId, dto.emoji, dto.action);
    }

    @Post(':messageId/read')
    @HttpCode(HttpStatus.NO_CONTENT)
    @ApiOperation({ summary: '읽음 처리 — unreadCount 초기화 및 lastReadMessageId 갱신' })
    @ApiParam({ name: 'conversationId', description: '대화방 ObjectId' })
    @ApiParam({ name: 'messageId', description: '읽은 마지막 메시지 ObjectId' })
    @ApiNoContentResponse({ description: '처리 성공' })
    @ApiNotFoundResponse({ description: '대화방 없음' })
    @ApiForbiddenResponse({ description: '대화방 참여자 아님' })
    markRead(
        @UserId() userId: number,
        @Param('conversationId') conversationId: string,
        @Param('messageId') messageId: string,
    ) {
        return this.messageService.markRead(conversationId, userId, messageId);
    }
}
