import {
	BadRequestException,
	Controller as controller,
	Get as httpGet,
	Post as httpPost,
	Patch as httpPatch,
	Delete as httpDelete,
	Param as requestParameter,
	Body as requestBody,
	Query as requestQuery,
	HttpCode as httpCode,
	HttpStatus,
	Header as responseHeader,
	ParseIntPipe,
} from '@nestjs/common';
import {
	ApiHeader as apiHeader,
	ApiOperation as apiOperation,
	ApiQuery as apiQuery,
	ApiResponse as apiResponse,
	ApiTags as apiTags,
} from '@nestjs/swagger';
import {
	type CurrentUser,
	currentUser,
	currentUserId,
	currentUserRole,
} from '../common/decorator/user.decorator.js';
import {throttle} from '../common/decorator/throttle.decorator.js';
import {SafePositiveIntPipe} from '../common/pipe/safe-positive-int.pipe.js';
import {ChatService} from './chat.service.js';
import {AttachmentDownloadService} from './service/attachment-download.service.js';
import {AttachmentDownloadResponseDto} from './dto/attachment-download.dto.js';
import {
	SendMessageDto,
	EditMessageDto,
	GetMessagesDto,
	ConfirmFileUploadRequestDto,
	ConfirmFileUploadResponseDto,
	CreateFileUploadUrlRequestDto,
	CreateFileUploadUrlResponseDto,
	DeleteMessageResponseDto,
	MessageResponseDto,
	SendMessageResponseDto,
	CreateGithubIssueDto,
	CreateGithubIssueResponseDto,
	SlashCommandDto,
	SlashCommandResponseDto,
	FileListQueryDto,
	FileListResponseDto,
	AddReactionDto,
	ReadChannelDto,
} from './dto/index.js';
import {EMOJI_REGEX} from './util/index.js';

/**
 채널 채팅 REST 컨트롤러.

 `channels/:channelId` 경로 하위에서 메시지 CRUD, 파일 업로드, 고정, 이모지 반응을 처리한다.
 모든 엔드포인트는 Gateway가 주입한 `X-User-Id` / `X-User-Role` 헤더로 사용자를 식별한다.
 */
@apiTags('Chat')
@apiHeader({name: 'X-User-Id', description: 'Gateway 자동 주입 (서비스 직접 테스트 시만 입력)', required: false})
@apiHeader({name: 'X-User-Role', description: 'Gateway 자동 주입 (ADMIN | MEMBER)', required: false})
@controller('channels/:channelId')
export class ChatController {
	constructor(
		private readonly chatService: ChatService,
		private readonly attachmentDownloadService: AttachmentDownloadService,
	) {}

	@httpGet('messages/:messageId/attachments/:attachmentIndex/download-url')
	@responseHeader('Cache-Control', 'private, no-store')
	@apiOperation({summary: '메시지 첨부파일의 60초 다운로드 URL 발급'})
	@apiResponse({status: 200, type: AttachmentDownloadResponseDto})
	@apiResponse({status: 403, description: '메시지 읽기 권한 없음'})
	@apiResponse({status: 404, description: '메시지 또는 첨부파일 없음'})
	@apiResponse({status: 503, description: '권한 projection 동기화 중'})
	async getAttachmentDownloadUrl(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestParameter('messageId') messageId: string,
		@requestParameter('attachmentIndex', ParseIntPipe) attachmentIndex: number,
		@currentUserId() userId: number,
	): Promise<AttachmentDownloadResponseDto> {
		return this.attachmentDownloadService.execute(channelId, userId, messageId, attachmentIndex);
	}

	/**
     채팅 첨부파일을 업로드하기 위한 오브젝트 스토리지 presigned URL을 발급한다.
     반환된 `uploadUrl`에 PUT 요청을 보낼 때 `headers`를 함께 전송해야 한다.

     @param channelId - 업로드 대상 채널 ID
     @param dto - 파일명, MIME 타입, 크기
     @param userId - Gateway가 주입한 요청자 ID
     @returns presigned URL, 임시 objectKey, 만료 시간(초), 필수 헤더
     */
	@httpPost('files/presigned-url')
	@httpCode(HttpStatus.CREATED)
	@apiOperation({summary: '채팅 첨부파일 업로드용 오브젝트 스토리지 presigned URL 발급'})
	@apiResponse({status: 201, type: CreateFileUploadUrlResponseDto})
	@apiResponse({status: 403, description: '메시지 읽기 또는 file_attach 권한 없음'})
	async createFileUploadUrl(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestBody() dto: CreateFileUploadUrlRequestDto,
		@currentUserId() userId: number,
	): Promise<CreateFileUploadUrlResponseDto> {
		return this.chatService.createFileUploadUrl({channelId, userId}, dto);
	}

	/**
     클라이언트가 presigned URL로 파일 업로드를 완료한 후 오브젝트 스토리지에서 실제 업로드 여부를 검증한다.
     파일이 아직 업로드되지 않았으면 409, 크기 제한 초과이면 413을 반환한다.

     @param channelId - 채널 ID
     @param dto - 검증할 objectKey
     @param userId - Gateway가 주입한 요청자 ID
     @returns 최종 objectKey와 서버가 확인한 파일 메타데이터
     */
	@httpPost('files/confirm')
	@httpCode(HttpStatus.OK)
	@apiOperation({summary: '채팅 첨부파일 업로드 완료 확인 및 검증'})
	@apiResponse({status: 200, type: ConfirmFileUploadResponseDto})
	@apiResponse({status: 400, description: '유효하지 않은 objectKey'})
	@apiResponse({status: 403, description: '메시지 읽기 또는 file_attach 권한 없음'})
	@apiResponse({status: 409, description: '파일이 아직 업로드되지 않음'})
	@apiResponse({status: 413, description: '파일 크기 초과'})
	async confirmFileUpload(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestBody() dto: ConfirmFileUploadRequestDto,
		@currentUserId() userId: number,
	): Promise<ConfirmFileUploadResponseDto> {
		return this.chatService.confirmFileUpload({channelId, userId}, dto);
	}

	/**
     FILE_SHARE 뷰 타입 채널의 첨부파일 목록을 커서 기반 페이지네이션으로 조회한다.
     FILE_SHARE 채널이 아니면 400을 반환한다.

     @param channelId - 채널 ID
     @param query - 커서(`before`), 페이지 크기(`limit`, 기본 20, 최대 100)
     @param userId - Gateway가 주입한 요청자 ID
     @returns 파일 목록과 다음 페이지 커서
     */
	@httpGet('files')
	@apiOperation({summary: 'FILE_SHARE 채널 파일 목록 조회'})
	@apiQuery({name: 'before', required: false, description: '커서: 이전 응답의 nextCursor 값'})
	@apiQuery({name: 'limit', required: false, description: '페이지 크기 (기본 20, 최대 100)'})
	@apiResponse({status: 200, type: FileListResponseDto})
	@apiResponse({status: 400, description: 'FILE_SHARE 채널이 아님 또는 잘못된 커서/파라미터'})
	@apiResponse({status: 403, description: '채널 멤버 아님'})
	async getFiles(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestQuery() query: FileListQueryDto,
		@currentUserId() userId: number,
	): Promise<FileListResponseDto> {
		return this.chatService.getFileList({channelId, userId}, query);
	}

	/**
     메시지를 Kafka `chat.message.v2` 토픽으로 비동기 발행한다.
     실제 저장과 Socket.IO 브로드캐스트는 `ChatMessageConsumer`가 처리한다.

     @param channelId - 채널 ID
     @param dto - 메시지 내용, 타입, 첨부파일, 답장 대상 등
     @param userId - Gateway가 주입한 요청자 ID
     @param userRole - Gateway가 주입한 요청자 역할
     @returns `{ queued: true }` — 큐 등록 여부만 반환, 저장 완료를 보장하지 않음
     */
	@httpPost('messages')
	@httpCode(HttpStatus.CREATED)
	@throttle({
		key: 'chat:msgrate',
		windowMsConfigKey: 'CHAT_MESSAGE_RATE_LIMIT_WINDOW_MS',
		maxRequestsConfigKey: 'CHAT_MESSAGE_RATE_LIMIT_MAX_REQUESTS',
		defaultWindowMs: 10_000,
		defaultMaxRequests: 20,
		message: '짧은 시간에 메시지 전송 요청이 너무 많습니다. 잠시 후 다시 시도하십시오.',
	})
	@apiOperation({summary: '메시지 전송 (Kafka 비동기)'})
	@apiResponse({status: 201, type: SendMessageResponseDto})
	@apiResponse({status: 403, description: '메시지 읽기/작성 권한 없음, 답글의 thread_reply 또는 첨부의 file_attach 권한 없음, DM 수신자가 차단함'})
	@apiResponse({status: 429, description: '메시지 전송 요청이 너무 많음'})
	async sendMessage(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestBody() dto: SendMessageDto,
		@currentUserId() userId: number,
		@currentUserRole() userRole: string,
	): Promise<SendMessageResponseDto> {
		await this.chatService.sendMessage({channelId, userId, userRole}, dto);
		return {queued: true};
	}

	/**
     GitHub 이슈 생성 커맨드를 Kafka로 비동기 발행한다.

     @deprecated 신규 클라이언트는 `POST /api/chat/chat/channels/{channelId}/slash-commands`를 사용하세요.

     @param channelId - 채널 ID
     @param dto - GitHub 이슈 생성 정보
     @param userId - Gateway가 주입한 요청자 ID
     @returns `{ queued: true }`
     */
	@httpPost('github/issues')
	@httpCode(HttpStatus.CREATED)
	@apiOperation({
		summary: '[Deprecated] GitHub 이슈 생성 (클라이언트 슬래시 커맨드 → Kafka 비동기)',
		description:
            'Deprecated: 신규 클라이언트는 POST /api/chat/chat/channels/{channelId}/slash-commands 사용을 권장합니다.',
		deprecated: true,
	})
	@apiResponse({status: 201, type: CreateGithubIssueResponseDto})
	@apiResponse({status: 403, description: '채널 멤버 아님'})
	async createGithubIssue(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestBody() dto: CreateGithubIssueDto,
		@currentUserId() userId: number,
	): Promise<CreateGithubIssueResponseDto> {
		await this.chatService.publishGithubIssueCreateCommand({channelId, userId}, dto);
		return {queued: true};
	}

	/**
     슬래시 커맨드를 Kafka로 비동기 발행한다.
     현재 지원 커맨드: `github.issue.create`.
     지원하지 않는 커맨드는 400을 반환한다.

     @param channelId - 채널 ID
     @param dto - 커맨드 종류와 커맨드별 payload
     @param userId - Gateway가 주입한 요청자 ID
     @returns `{ queued: true }`
     */
	@httpPost('slash-commands')
	@httpCode(HttpStatus.CREATED)
	@apiOperation({
		summary: '슬래시 커맨드 발행',
		description: '현재 지원 커맨드: github.issue.create',
	})
	@apiResponse({status: 201, type: SlashCommandResponseDto})
	@apiResponse({status: 400, description: '지원하지 않는 커맨드 또는 유효하지 않은 payload'})
	@apiResponse({status: 403, description: '채널 멤버 아님'})
	async createSlashCommand(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestBody() dto: SlashCommandDto,
		@currentUserId() userId: number,
	): Promise<SlashCommandResponseDto> {
		await this.chatService.handleSlashCommand({channelId, userId}, dto);
		return {queued: true};
	}

	/**
     채널 메시지를 최신순으로 커서 기반 페이지네이션 조회한다.
     `before`에 이전 응답의 마지막 `_id`를 전달해 이전 메시지를 가져온다.
     한 번에 최대 100개를 반환한다.

     @param channelId - 채널 ID
     @param dto - 마지막으로 읽은 메시지 ID
     @param userId - Gateway가 주입한 요청자 ID
     @returns 메시지 배열 (reactions는 나의 반응 여부 포함)
     */
	@httpPost('read')
	@httpCode(HttpStatus.NO_CONTENT)
	@apiOperation({summary: '채널 읽음 상태 업데이트'})
	@apiResponse({status: 204})
	@apiResponse({status: 400, description: '유효하지 않은 메시지 ID'})
	@apiResponse({status: 403, description: '채널 멤버 아님'})
	async readChannel(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestBody() dto: ReadChannelDto,
		@currentUserId() userId: number,
	): Promise<void> {
		await this.chatService.readChannel({channelId, userId}, dto.lastReadMessageId);
	}

	@httpGet('messages')
	@apiOperation({summary: '채널 메시지 목록 조회 (커서 기반 페이지네이션)'})
	@apiResponse({status: 200, type: [MessageResponseDto]})
	@apiResponse({status: 403, description: '채널 멤버 아님'})
	async getMessages(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestQuery() query: GetMessagesDto,
		@currentUserId() userId: number,
	) {
		return this.chatService.getMessages({channelId, userId}, query.before, query.parentMessageId);
	}

	/**
     메시지 내용을 수정한다. 내용이 동일하면 DB 업데이트 없이 현재 값을 반환한다.
     수정 성공 시 `chat:{channelId}` 룸에 `message:edited` 이벤트를 브로드캐스트한다.
     ADMIN은 타인 메시지도 수정할 수 있다.

     @param channelId - 채널 ID
     @param messageId - 수정할 메시지의 MongoDB ObjectId
     @param dto - 새 메시지 내용
     @param user - Gateway가 주입한 요청자 ID와 역할
     @returns 수정된 메시지 도큐먼트
     */
	@httpPatch('messages/:messageId')
	@apiOperation({summary: '메시지 수정'})
	@apiResponse({status: 200, type: MessageResponseDto})
	@apiResponse({status: 403, description: '채널 멤버 아님 또는 본인 메시지 아님'})
	@apiResponse({status: 404, description: '메시지 없음'})
	async editMessage(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestParameter('messageId') messageId: string,
		@requestBody() dto: EditMessageDto,
		@currentUser() user: CurrentUser,
	) {
		return this.chatService.editMessage({
			channelId, messageId, userId: user.userId, userRole: user.userRole,
		}, dto);
	}

	/**
     파일이 포함된 메시지와 오브젝트 스토리지 오브젝트를 함께 삭제한다.
     오브젝트 스토리지 삭제 실패는 경고 로그만 남기고 메시지 삭제는 계속 진행한다.
     삭제 성공 시 `chat:{channelId}` 룸에 `message:deleted` 이벤트를 브로드캐스트한다.
     ADMIN은 타인 파일도 삭제할 수 있다.

     @param channelId - 채널 ID
     @param fileId - base64 인코딩된 파일 식별자 (messageId를 포함한 JSON)
     @param userId - Gateway가 주입한 요청자 ID
     @param userRole - Gateway가 주입한 요청자 역할
     @returns 삭제된 채널 ID와 메시지 ID
     */
	@httpDelete('files/:fileId')
	@apiOperation({summary: '채팅 첨부파일 삭제 (파일이 포함된 메시지 전체 삭제)'})
	@apiResponse({status: 200, type: DeleteMessageResponseDto})
	@apiResponse({status: 400, description: '유효하지 않은 fileId'})
	@apiResponse({status: 403, description: '채널 멤버 아님 또는 권한 없음'})
	@apiResponse({status: 404, description: '파일(메시지)을 찾을 수 없음'})
	async deleteFile(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestParameter('fileId') fileId: string,
		@currentUserId() userId: number,
		@currentUserRole() userRole: string,
	): Promise<DeleteMessageResponseDto> {
		return this.chatService.deleteFile({channelId, userId, userRole}, fileId);
	}

	/**
     메시지를 삭제한다.
     삭제 성공 시 `chat:{channelId}` 룸에 `message:deleted` 이벤트를 브로드캐스트한다.
     ADMIN은 타인 메시지도 삭제할 수 있다.

     @param channelId - 채널 ID
     @param messageId - 삭제할 메시지의 MongoDB ObjectId
     @param userId - Gateway가 주입한 요청자 ID
     @param userRole - Gateway가 주입한 요청자 역할
     @returns 삭제된 채널 ID와 메시지 ID
     */
	@httpDelete('messages/:messageId')
	@apiOperation({summary: '메시지 삭제'})
	@apiResponse({status: 200, type: DeleteMessageResponseDto})
	@apiResponse({status: 403, description: '채널 멤버 아님 또는 본인 메시지 아님'})
	@apiResponse({status: 404, description: '메시지 없음'})
	async deleteMessage(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestParameter('messageId') messageId: string,
		@currentUserId() userId: number,
		@currentUserRole() userRole: string,
	) {
		return this.chatService.deleteMessage({
			channelId, messageId, userId, userRole,
		});
	}

	/**
     메시지를 고정한다. 이미 고정된 메시지이면 400을 반환한다.
     고정 성공 시 `chat:{channelId}` 룸에 `message:pinned` 이벤트를 브로드캐스트한다.
     ADMIN은 타인 메시지도 고정할 수 있다.

     @param channelId - 채널 ID
     @param messageId - 고정할 메시지의 MongoDB ObjectId
     @param userId - Gateway가 주입한 요청자 ID
     @param userRole - Gateway가 주입한 요청자 역할
     @returns 업데이트된 메시지 도큐먼트
     */
	@httpPost('pins/:messageId')
	@httpCode(HttpStatus.OK)
	@apiOperation({summary: '메시지 고정'})
	@apiResponse({status: 200, type: MessageResponseDto})
	@apiResponse({status: 400, description: '이미 고정된 메시지'})
	@apiResponse({status: 403, description: '채널 멤버 아님 또는 권한 없음'})
	@apiResponse({status: 404, description: '메시지 없음'})
	async pinMessage(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestParameter('messageId') messageId: string,
		@currentUserId() userId: number,
		@currentUserRole() userRole: string,
	) {
		return this.chatService.pinMessage({
			channelId, messageId, userId, userRole,
		});
	}

	/**
     메시지 고정을 해제한다. 고정되지 않은 메시지이면 400을 반환한다.
     해제 성공 시 `chat:{channelId}` 룸에 `message:unpinned` 이벤트를 브로드캐스트한다.
     ADMIN은 타인 메시지도 고정 해제할 수 있다.

     @param channelId - 채널 ID
     @param messageId - 고정 해제할 메시지의 MongoDB ObjectId
     @param userId - Gateway가 주입한 요청자 ID
     @param userRole - Gateway가 주입한 요청자 역할
     */
	@httpDelete('pins/:messageId')
	@httpCode(HttpStatus.NO_CONTENT)
	@apiOperation({summary: '메시지 고정 해제'})
	@apiResponse({status: 204})
	@apiResponse({status: 400, description: '고정되지 않은 메시지'})
	@apiResponse({status: 403, description: '채널 멤버 아님 또는 권한 없음'})
	@apiResponse({status: 404, description: '메시지 없음'})
	async unpinMessage(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestParameter('messageId') messageId: string,
		@currentUserId() userId: number,
		@currentUserRole() userRole: string,
	) {
		await this.chatService.unpinMessage({
			channelId, messageId, userId, userRole,
		});
	}

	/**
     메시지에 이모지 반응을 추가한다.
     이미 같은 이모지로 반응한 경우 무시한다(중복 반응 허용하지 않음).
     추가 성공 시 `chat:{channelId}` 룸에 `message:reaction:added` 이벤트를 브로드캐스트한다.
     `emoji`는 단일 RGI 이모지여야 한다.

     @param channelId - 채널 ID
     @param messageId - 반응을 추가할 메시지의 MongoDB ObjectId
     @param dto - 이모지 문자
     @param userId - Gateway가 주입한 요청자 ID
     */
	@httpPost('messages/:messageId/reactions')
	@httpCode(HttpStatus.OK)
	@apiOperation({summary: '메시지 이모지 반응 추가'})
	@apiResponse({status: 200})
	@apiResponse({status: 400, description: '유효하지 않은 이모지'})
	@apiResponse({status: 403, description: '메시지 읽기 또는 reaction_add 권한 없음'})
	@apiResponse({status: 404, description: '메시지 없음'})
	async addReaction(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestParameter('messageId') messageId: string,
		@requestBody() dto: AddReactionDto,
		@currentUserId() userId: number,
	): Promise<void> {
		await this.chatService.addReaction({channelId, userId}, messageId, dto.emoji);
	}

	/**
     메시지에서 이모지 반응을 제거한다.
     반응이 없었던 경우 무시한다.
     제거 성공 시 `chat:{channelId}` 룸에 `message:reaction:removed` 이벤트를 브로드캐스트한다.
     URL 경로의 `emoji` 파라미터는 단일 RGI 이모지여야 한다.

     @param channelId - 채널 ID
     @param messageId - 반응을 제거할 메시지의 MongoDB ObjectId
     @param emoji - URL 디코딩된 이모지 문자
     @param userId - Gateway가 주입한 요청자 ID
     @throws BadRequestException `emoji`가 유효한 이모지 형식이 아닐 때
     */
	@httpDelete('messages/:messageId/reactions/:emoji')
	@httpCode(HttpStatus.OK)
	@apiOperation({summary: '메시지 이모지 반응 제거'})
	@apiResponse({status: 200})
	@apiResponse({status: 400, description: '유효하지 않은 이모지'})
	@apiResponse({status: 403, description: '채널 멤버 아님'})
	@apiResponse({status: 404, description: '메시지 없음'})
	async removeReaction(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestParameter('messageId') messageId: string,
		@requestParameter('emoji') emoji: string,
		@currentUserId() userId: number,
	): Promise<void> {
		if (!EMOJI_REGEX.test(emoji)) {
			throw new BadRequestException('Invalid emoji format');
		}

		await this.chatService.removeReaction({channelId, userId}, messageId, emoji);
	}

	/**
     채널에 고정된 메시지 목록을 최신순으로 최대 100개 조회한다.

     @param channelId - 채널 ID
     @param userId - Gateway가 주입한 요청자 ID
     @returns 고정 메시지 배열 (reactions는 나의 반응 여부 포함)
     */
	@httpGet('pins')
	@apiOperation({summary: '채널 고정 메시지 목록 조회'})
	@apiResponse({status: 200, type: [MessageResponseDto]})
	@apiResponse({status: 403, description: '채널 멤버 아님'})
	async getPinnedMessages(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@currentUserId() userId: number,
	) {
		return this.chatService.getPinnedMessages({channelId, userId});
	}
}
