import {
    Body,
    Controller,
    Delete,
    Get,
    HttpCode,
    HttpStatus,
    Param,
    Post,
} from '@nestjs/common';
import {
    ApiBadRequestResponse,
    ApiCreatedResponse,
    ApiForbiddenResponse,
    ApiNoContentResponse,
    ApiNotFoundResponse,
    ApiOkResponse,
    ApiOperation,
    ApiParam,
    ApiTags,
} from '@nestjs/swagger';
import { ConversationService } from './conversation.service';
import { OpenConversationDto } from './dto/open-conversation.dto';
import { ConversationResponseDto } from './dto/conversation-response.dto';
import { UserId } from '../common/decorator/user.decorator';

@ApiTags('conversations')
@Controller('conversations')
export class ConversationController {
    constructor(private readonly conversationService: ConversationService) {}

    @Post()
    @ApiOperation({ summary: 'DM 대화방 열기 (없으면 생성, 있으면 기존 반환)' })
    @ApiCreatedResponse({ type: ConversationResponseDto })
    @ApiBadRequestResponse({ description: '자기 자신과 DM 시도' })
    openConversation(
        @UserId() userId: number,
        @Body() dto: OpenConversationDto,
    ) {
        return this.conversationService.openConversation(userId, dto.targetUserId);
    }

    @Get()
    @ApiOperation({ summary: '내 DM 목록 조회 — 숨기지 않은 대화방만 (lastMessageAt 내림차순)' })
    @ApiOkResponse({ type: [ConversationResponseDto] })
    getMyConversations(@UserId() userId: number) {
        return this.conversationService.getMyConversations(userId);
    }

    @Delete(':conversationId')
    @HttpCode(HttpStatus.NO_CONTENT)
    @ApiOperation({ summary: '대화방 숨기기 — 상대방 메시지 수신 시 자동 복원 (Discord 닫기)' })
    @ApiParam({ name: 'conversationId', description: '대화방 ObjectId' })
    @ApiNoContentResponse({ description: '숨기기 성공' })
    @ApiNotFoundResponse({ description: '대화방 없음' })
    @ApiForbiddenResponse({ description: '대화방 참여자 아님' })
    hideConversation(
        @UserId() userId: number,
        @Param('conversationId') conversationId: string,
    ) {
        return this.conversationService.hideConversation(conversationId, userId);
    }
}
