import { Controller, Delete, Get, HttpCode, HttpStatus, Param, ParseIntPipe } from '@nestjs/common';
import { ApiHeader, ApiNoContentResponse, ApiOkResponse, ApiOperation, ApiParam, ApiTags } from '@nestjs/swagger';
import { ChatService } from './chat.service';
import { UserId } from '../common/decorator/user.decorator';

@ApiTags('DM')
@ApiHeader({ name: 'X-User-Id', description: 'Gateway 주입 유저 ID', required: true })
@Controller('dms')
export class DmController {
    constructor(private readonly chatService: ChatService) {}

    @Get()
    @ApiOperation({ summary: '내 DM 대화 목록 조회 — 숨긴 대화 제외, 마지막 메시지 시각 내림차순' })
    @ApiOkResponse({ description: '채널 ID, 상대 사용자 ID, 안읽음 수, 마지막 메시지 요약 목록' })
    getMyDms(@UserId() userId: number) {
        return this.chatService.getMyDms(userId);
    }

    @Delete(':channelId')
    @HttpCode(HttpStatus.NO_CONTENT)
    @ApiOperation({ summary: 'DM 대화 숨기기 — 상대방 메시지 수신 시 자동 복구' })
    @ApiParam({ name: 'channelId', description: '숨길 DM 채널 ID', type: Number })
    @ApiNoContentResponse({ description: '숨김 성공' })
    hideDm(
        @UserId() userId: number,
        @Param('channelId', ParseIntPipe) channelId: number,
    ) {
        return this.chatService.hideDm(channelId, userId);
    }
}
