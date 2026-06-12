import { Controller, Delete, Get, HttpCode, HttpStatus, Param, ParseIntPipe, Post } from '@nestjs/common';
import {
    ApiNoContentResponse,
    ApiOkResponse,
    ApiOperation,
    ApiParam,
    ApiTags,
} from '@nestjs/swagger';
import { BlockService } from './block.service';
import { UserId } from '../common/decorator/user.decorator';

@ApiTags('block')
@Controller('block')
export class BlockController {
    constructor(private readonly blockService: BlockService) {}

    @Post(':targetUserId')
    @HttpCode(HttpStatus.NO_CONTENT)
    @ApiOperation({ summary: '사용자 차단 — DM 메시지 수신 거부' })
    @ApiParam({ name: 'targetUserId', description: '차단할 사용자 ID', type: Number })
    @ApiNoContentResponse({ description: '차단 성공' })
    blockUser(
        @UserId() userId: number,
        @Param('targetUserId', ParseIntPipe) targetUserId: number,
    ) {
        return this.blockService.blockUser(userId, targetUserId);
    }

    @Delete(':targetUserId')
    @HttpCode(HttpStatus.NO_CONTENT)
    @ApiOperation({ summary: '차단 해제' })
    @ApiParam({ name: 'targetUserId', description: '차단 해제할 사용자 ID', type: Number })
    @ApiNoContentResponse({ description: '차단 해제 성공' })
    unblockUser(
        @UserId() userId: number,
        @Param('targetUserId', ParseIntPipe) targetUserId: number,
    ) {
        return this.blockService.unblockUser(userId, targetUserId);
    }

    @Get()
    @ApiOperation({ summary: '차단 목록 조회' })
    @ApiOkResponse({ schema: { type: 'array', items: { type: 'number' }, example: [2, 5, 13] } })
    getBlockedUsers(@UserId() userId: number) {
        return this.blockService.getBlockedUsers(userId);
    }
}
