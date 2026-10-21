import {
    ConflictException,
    Controller,
    Get,
    HttpCode,
    HttpStatus,
    Param,
    Post,
    Query,
} from '@nestjs/common';
import {ApiHeader, ApiOperation, ApiTags} from '@nestjs/swagger';
import {UserRole} from '../common/enum/user-role.enum';
import {Roles} from '../common/guard/roles.decorator';
import {ChatMessageQuarantineService} from './service';
import {ChatMessageQuarantineRecordIdDto, FindChatMessageQuarantineRecordsDto} from './dto';

@ApiTags('Chat Admin')
@ApiHeader({name: 'X-User-Role', description: 'Gateway 자동 주입 (ADMIN)', required: true})
@Roles(UserRole.ADMIN)
@Controller('admin/message-quarantine')
export class ChatMessageQuarantineAdminController {
    constructor(private readonly quarantineService: ChatMessageQuarantineService) {}

    @Get()
    @ApiOperation({summary: '격리된 chat.message 레코드의 상태·원인별 건수 조회'})
    summary() {
        return this.quarantineService.summary();
    }

    @Get('records')
    @ApiOperation({summary: '최근 격리 레코드 조회 (원문 제외, 최대 100건)'})
    records(@Query() query: FindChatMessageQuarantineRecordsDto) {
        return this.quarantineService.findRecent(query.status);
    }

    @Post(':id/reprocess')
    @HttpCode(HttpStatus.NO_CONTENT)
    @ApiOperation({summary: '격리 레코드 재처리 요청'})
    async reprocess(@Param() path: ChatMessageQuarantineRecordIdDto): Promise<void> {
        if (!await this.quarantineService.requestReprocess(path.id)) {
            throw new ConflictException('Quarantine record cannot be reprocessed in its current state');
        }
    }

    @Post(':id/discard')
    @HttpCode(HttpStatus.NO_CONTENT)
    @ApiOperation({summary: '격리 레코드 폐기'})
    async discard(@Param() path: ChatMessageQuarantineRecordIdDto): Promise<void> {
        if (!await this.quarantineService.discard(path.id)) {
            throw new ConflictException('Quarantine record cannot be discarded in its current state');
        }
    }
}
