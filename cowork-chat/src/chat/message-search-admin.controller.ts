import {
    Body,
    ConflictException,
    Controller,
    Get,
    HttpCode,
    HttpStatus,
    Post,
} from '@nestjs/common';
import {ApiHeader, ApiOperation, ApiTags} from '@nestjs/swagger';
import {UserRole} from '../common/enum/user-role.enum';
import {Roles} from '../common/guard/roles.decorator';
import {MessageSearchAdminService} from './search';
import {StartMessageSearchRebuildDto} from './dto';

@ApiTags('Chat Admin')
@ApiHeader({name: 'X-User-Role', description: 'Gateway 자동 주입 (ADMIN)', required: true})
@Roles(UserRole.ADMIN)
@Controller('admin/message-search')
export class MessageSearchAdminController {
    constructor(private readonly messageSearchAdminService: MessageSearchAdminService) {}

    @Get()
    @ApiOperation({summary: '메시지 검색 색인 아웃박스·alias·재구축 상태 조회'})
    status() {
        return this.messageSearchAdminService.status();
    }

    @Post('retry-failed')
    @HttpCode(HttpStatus.OK)
    @ApiOperation({summary: '영구 실패한 색인·삭제 작업 재시도'})
    retryFailed() {
        return this.messageSearchAdminService.retryFailed();
    }

    @Post('rebuild')
    @HttpCode(HttpStatus.ACCEPTED)
    @ApiOperation({summary: '검색 색인 전체 재구축 시작 (진행 상황은 상태 조회로 확인)'})
    async rebuild(@Body() body: StartMessageSearchRebuildDto): Promise<void> {
        if (!await this.messageSearchAdminService.startRebuild(body.resume === true)) {
            throw new ConflictException('Another search index rebuild is already running');
        }
    }
}
