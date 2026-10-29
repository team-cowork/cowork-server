import {
	Controller as controller,
	Get as httpGet,
	Param as requestParameter,
	Query as requestQuery,
} from '@nestjs/common';
import {
	ApiHeader as apiHeader,
	ApiOperation as apiOperation,
	ApiQuery as apiQuery,
	ApiResponse as apiResponse,
	ApiTags as apiTags,
} from '@nestjs/swagger';
import {currentUserId} from '../common/decorator/user.decorator.js';
import {SafePositiveIntPipe} from '../common/pipe/safe-positive-int.pipe.js';
import {ChatService} from './chat.service.js';
import {SearchMessagesDto, SearchMessagesResponseDto} from './dto/index.js';

/**
 프로젝트 단위 채팅 메시지 검색 컨트롤러.

 요청자가 접근 가능한 채널 범위 내에서 Elasticsearch 전문 검색을 수행한다.
 프로젝트·채널 멤버십은 Kafka 이벤트로 동기화한 MongoDB projection에서 확인한다.
 */
@apiTags('Chat')
@apiHeader({name: 'X-User-Id', description: 'Gateway 자동 주입 (서비스 직접 테스트 시만 입력)', required: false})
@controller('projects/:projectId')
export class ProjectMessageController {
	constructor(private readonly chatService: ChatService) {}

	/**
     프로젝트에 속한 채널 중 요청자가 접근 가능한 채널에서 메시지를 전문 검색한다.
     nori 형태소 분석과 fuzzy matching이 적용되며, 결과는 최신순으로 정렬된다.
     `channelId` 파라미터로 검색 범위를 특정 채널로 좁힐 수 있다.

     @param projectId - 검색 대상 프로젝트 ID
     @param dto - 검색 조건 (키워드, 채널, 작성자, 타입, 파일 포함 여부, 커서, 페이지 크기)
     @param userId - Gateway가 주입한 요청자 ID
     @returns 검색 결과 메시지 목록과 다음 페이지 커서
     */
	@httpGet('messages/search')
	@apiOperation({
		summary: '프로젝트 채팅 메시지 검색',
		description:
            'Elasticsearch 기반 전문 검색. 프로젝트에 속한 채널 중 요청자가 접근 가능한 채널에서 검색.\n\n'
            + '- 한국어 nori 형태소 분석 적용\n'
            + '- fuzzy matching으로 오타 허용\n'
            + '- 검색 키워드는 응답의 `highlight` 필드에 `<em>` 태그로 표시\n'
            + '- 결과는 최신순 정렬 (createdAt DESC)\n'
            + '- 커서 기반 페이지네이션: 응답의 `nextCursor`를 다음 요청의 `before`에 전달',
	})
	@apiQuery({name: 'q', description: '검색 키워드 (최소 1자)', required: true})
	@apiQuery({name: 'channelId', description: '특정 채널로 범위 축소', required: false})
	@apiQuery({name: 'authorId', description: '특정 작성자 ID 필터', required: false})
	@apiQuery({
		name: 'type', enum: ['TEXT', 'FILE', 'SYSTEM'], description: '메시지 타입 필터', required: false,
	})
	@apiQuery({name: 'hasFile', description: '첨부파일 포함 메시지만 조회 (true/false)', required: false})
	@apiQuery({name: 'before', description: '커서: 이전 응답의 nextCursor 값', required: false})
	@apiQuery({name: 'limit', description: '페이지 크기 (기본 50, 최대 100)', required: false})
	@apiResponse({status: 200, type: SearchMessagesResponseDto})
	@apiResponse({status: 403, description: '프로젝트 멤버 아님 또는 채널 접근 권한 없음'})
	async searchMessages(
		@requestParameter('projectId', SafePositiveIntPipe) projectId: number,
		@requestQuery() dto: SearchMessagesDto,
		@currentUserId() userId: number,
	): Promise<SearchMessagesResponseDto> {
		return this.chatService.searchProjectMessages(projectId, dto, {userId});
	}
}
