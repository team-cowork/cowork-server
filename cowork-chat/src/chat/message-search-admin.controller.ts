import {
	Body as requestBody,
	ConflictException,
	Controller as controller,
	Get as httpGet,
	HttpCode as httpCode,
	HttpStatus,
	Post as httpPost,
} from '@nestjs/common';
import {ApiHeader as apiHeader, ApiOperation as apiOperation, ApiTags as apiTags} from '@nestjs/swagger';
import {UserRole} from '../common/enum/user-role.enum.js';
import {requireRoles} from '../common/guard/roles.decorator.js';
import {MessageSearchAdminService} from './search/index.js';
import {StartMessageSearchRebuildDto} from './dto/index.js';

@apiTags('Chat Admin')
@apiHeader({name: 'X-User-Role', description: 'Gateway 자동 주입 (ADMIN)', required: true})
@requireRoles(UserRole.ADMIN)
@controller('admin/message-search')
export class MessageSearchAdminController {
	constructor(private readonly messageSearchAdminService: MessageSearchAdminService) {}

	@httpGet()
	@apiOperation({summary: '메시지 검색 색인 아웃박스·alias·재구축 상태 조회'})
	async status() {
		return this.messageSearchAdminService.status();
	}

	@httpPost('retry-failed')
	@httpCode(HttpStatus.OK)
	@apiOperation({summary: '영구 실패한 색인·삭제 작업 재시도'})
	async retryFailed() {
		return this.messageSearchAdminService.retryFailed();
	}

	@httpPost('rebuild')
	@httpCode(HttpStatus.ACCEPTED)
	@apiOperation({summary: '검색 색인 전체 재구축 시작 (진행 상황은 상태 조회로 확인)'})
	async rebuild(@requestBody() body: StartMessageSearchRebuildDto): Promise<void> {
		if (!await this.messageSearchAdminService.startRebuild(body.resume === true)) {
			throw new ConflictException('Another search index rebuild is already running');
		}
	}
}
