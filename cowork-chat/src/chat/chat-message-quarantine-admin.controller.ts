import {
	ConflictException,
	Controller as controller,
	Get as httpGet,
	HttpCode as httpCode,
	HttpStatus,
	Param as requestParameter,
	Post as httpPost,
	Query as requestQuery,
} from '@nestjs/common';
import {ApiHeader as apiHeader, ApiOperation as apiOperation, ApiTags as apiTags} from '@nestjs/swagger';
import {UserRole} from '../common/enum/user-role.enum.js';
import {requireRoles} from '../common/guard/roles.decorator.js';
import {ChatMessageQuarantineService} from './service/index.js';
import {ChatMessageQuarantineRecordIdDto, FindChatMessageQuarantineRecordsDto} from './dto/index.js';

@apiTags('Chat Admin')
@apiHeader({name: 'X-User-Role', description: 'Gateway 자동 주입 (ADMIN)', required: true})
@requireRoles(UserRole.ADMIN)
@controller('admin/message-quarantine')
export class ChatMessageQuarantineAdminController {
	constructor(private readonly quarantineService: ChatMessageQuarantineService) {}

	@httpGet()
	@apiOperation({summary: '격리된 chat.message 레코드의 상태·원인별 건수 조회'})
	async summary() {
		return this.quarantineService.summary();
	}

	@httpGet('records')
	@apiOperation({summary: '최근 격리 레코드 조회 (원문 제외, 최대 100건)'})
	async records(@requestQuery() query: FindChatMessageQuarantineRecordsDto) {
		return this.quarantineService.findRecent(query.status);
	}

	@httpPost(':id/reprocess')
	@httpCode(HttpStatus.NO_CONTENT)
	@apiOperation({summary: '격리 레코드 재처리 요청'})
	async reprocess(@requestParameter() path: ChatMessageQuarantineRecordIdDto): Promise<void> {
		if (!await this.quarantineService.requestReprocess(path.id)) {
			throw new ConflictException('Quarantine record cannot be reprocessed in its current state');
		}
	}

	@httpPost(':id/discard')
	@httpCode(HttpStatus.NO_CONTENT)
	@apiOperation({summary: '격리 레코드 폐기'})
	async discard(@requestParameter() path: ChatMessageQuarantineRecordIdDto): Promise<void> {
		if (!await this.quarantineService.discard(path.id)) {
			throw new ConflictException('Quarantine record cannot be discarded in its current state');
		}
	}
}
