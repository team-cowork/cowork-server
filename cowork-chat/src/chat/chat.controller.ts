import {
    BadRequestException,
    Controller,
    Get,
    Post,
    Patch,
    Delete,
    Param,
    Body,
    Query,
    HttpCode,
    HttpStatus,
    ParseIntPipe,
} from '@nestjs/common';
import { ApiHeader, ApiOperation, ApiQuery, ApiResponse, ApiTags } from '@nestjs/swagger';
import { ChatService } from './chat.service';
import { SendMessageDto } from './dto/send-message.dto';
import { EditMessageDto } from './dto/edit-message.dto';
import { GetMessagesDto } from './dto/get-messages.dto';
import {
    ConfirmFileUploadRequestDto,
    ConfirmFileUploadResponseDto,
    CreateFileUploadUrlRequestDto,
    CreateFileUploadUrlResponseDto,
} from './dto/create-file-upload-url.dto';
import {
    DeleteMessageResponseDto,
    MessageResponseDto,
    SendMessageResponseDto,
} from './dto/message-response.dto';
import { CreateGithubIssueDto, CreateGithubIssueResponseDto } from './dto/create-github-issue.dto';
import { SlashCommandDto, SlashCommandResponseDto } from './dto/slash-command.dto';
import { FileListQueryDto, FileListResponseDto } from './dto/file-list.dto';
import { UserId, UserRole } from '../common/decorator/user.decorator';
import { AddReactionDto } from './dto/add-reaction.dto';
import { ReadChannelDto } from './dto/read-channel.dto';
import { EMOJI_REGEX } from './util/emoji';

/**
 * 채널 채팅 REST 컨트롤러.
 *
 * `channels/:channelId` 경로 하위에서 메시지 CRUD, 파일 업로드, 고정, 이모지 반응을 처리한다.
 * 모든 엔드포인트는 Gateway가 주입한 `X-User-Id` / `X-User-Role` 헤더로 사용자를 식별한다.
 */
@ApiTags('Chat')
@ApiHeader({ name: 'X-User-Id', description: 'Gateway 주입 유저 ID', required: true })
@ApiHeader({ name: 'X-User-Role', description: 'Gateway 주입 유저 역할 (ADMIN | MEMBER)', required: true })
@Controller('channels/:channelId')
export class ChatController {
    constructor(private readonly chatService: ChatService) {}

    /**
     * 채팅 첨부파일을 업로드하기 위한 MinIO presigned URL을 발급한다.
     * 반환된 `uploadUrl`에 PUT 요청을 보낼 때 `headers`를 함께 전송해야 한다.
     *
     * @param channelId - 업로드 대상 채널 ID
     * @param dto - 파일명, MIME 타입, 크기
     * @param userId - Gateway가 주입한 요청자 ID
     * @returns presigned URL, objectKey, fileUrl, 만료 시간(초), 필수 헤더
     */
    @Post('files/presigned-url')
    @HttpCode(HttpStatus.CREATED)
    @ApiOperation({ summary: '채팅 첨부파일 업로드용 MinIO presigned URL 발급' })
    @ApiResponse({ status: 201, type: CreateFileUploadUrlResponseDto })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    async createFileUploadUrl(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Body() dto: CreateFileUploadUrlRequestDto,
        @UserId() userId: number,
    ): Promise<CreateFileUploadUrlResponseDto> {
        return this.chatService.createFileUploadUrl({ channelId, userId }, dto);
    }

    /**
     * 클라이언트가 presigned URL로 파일 업로드를 완료한 후 MinIO에서 실제 업로드 여부를 검증한다.
     * 파일이 아직 업로드되지 않았으면 409, 크기 제한 초과이면 413을 반환한다.
     *
     * @param channelId - 채널 ID
     * @param dto - 검증할 objectKey
     * @param userId - Gateway가 주입한 요청자 ID
     * @returns 메시지 attachments.url에 사용할 파일 URL
     */
    @Post('files/confirm')
    @HttpCode(HttpStatus.OK)
    @ApiOperation({ summary: '채팅 첨부파일 업로드 완료 확인 및 검증' })
    @ApiResponse({ status: 200, type: ConfirmFileUploadResponseDto })
    @ApiResponse({ status: 400, description: '유효하지 않은 objectKey' })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    @ApiResponse({ status: 409, description: '파일이 아직 업로드되지 않음' })
    @ApiResponse({ status: 413, description: '파일 크기 초과' })
    async confirmFileUpload(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Body() dto: ConfirmFileUploadRequestDto,
        @UserId() userId: number,
    ): Promise<ConfirmFileUploadResponseDto> {
        const fileUrl = await this.chatService.confirmFileUpload({ channelId, userId }, dto);
        return { fileUrl };
    }

    /**
     * FILE_SHARE 뷰 타입 채널의 첨부파일 목록을 커서 기반 페이지네이션으로 조회한다.
     * FILE_SHARE 채널이 아니면 400을 반환한다.
     *
     * @param channelId - 채널 ID
     * @param query - 커서(`before`), 페이지 크기(`limit`, 기본 20, 최대 100)
     * @param userId - Gateway가 주입한 요청자 ID
     * @returns 파일 목록과 다음 페이지 커서
     */
    @Get('files')
    @ApiOperation({ summary: 'FILE_SHARE 채널 파일 목록 조회' })
    @ApiQuery({ name: 'before', required: false, description: '커서: 이전 응답의 nextCursor 값' })
    @ApiQuery({ name: 'limit', required: false, description: '페이지 크기 (기본 20, 최대 100)' })
    @ApiResponse({ status: 200, type: FileListResponseDto })
    @ApiResponse({ status: 400, description: 'FILE_SHARE 채널이 아님 또는 잘못된 커서/파라미터' })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    async getFiles(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Query() query: FileListQueryDto,
        @UserId() userId: number,
    ): Promise<FileListResponseDto> {
        return this.chatService.getFileList({ channelId, userId }, query);
    }

    /**
     * 메시지를 Kafka `chat.message` 토픽으로 비동기 발행한다.
     * 실제 저장과 Socket.IO 브로드캐스트는 `ChatMessageConsumer`가 처리한다.
     *
     * @param channelId - 채널 ID
     * @param dto - 메시지 내용, 타입, 첨부파일, 답장 대상 등
     * @param userId - Gateway가 주입한 요청자 ID
     * @param userRole - Gateway가 주입한 요청자 역할
     * @returns `{ queued: true }` — 큐 등록 여부만 반환, 저장 완료를 보장하지 않음
     */
    @Post('messages')
    @HttpCode(HttpStatus.CREATED)
    @ApiOperation({ summary: '메시지 전송 (Kafka 비동기)' })
    @ApiResponse({ status: 201, type: SendMessageResponseDto })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    async sendMessage(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Body() dto: SendMessageDto,
        @UserId() userId: number,
        @UserRole() userRole: string,
    ): Promise<SendMessageResponseDto> {
        await this.chatService.sendMessage({ channelId, userId, userRole }, dto);
        return { queued: true };
    }

    /**
     * @deprecated 신규 클라이언트는 `POST /channels/{channelId}/slash-commands`를 사용하세요.
     *
     * GitHub 이슈 생성 커맨드를 Kafka로 비동기 발행한다.
     *
     * @param channelId - 채널 ID
     * @param dto - GitHub 이슈 생성 정보
     * @param userId - Gateway가 주입한 요청자 ID
     * @returns `{ queued: true }`
     */
    @Post('github/issues')
    @HttpCode(HttpStatus.CREATED)
    @ApiOperation({
        summary: '[Deprecated] GitHub 이슈 생성 (클라이언트 슬래시 커맨드 → Kafka 비동기)',
        description: 'Deprecated: 신규 클라이언트는 POST /chat/channels/{channelId}/slash-commands 사용을 권장합니다.',
        deprecated: true,
    })
    @ApiResponse({ status: 201, type: CreateGithubIssueResponseDto })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    async createGithubIssue(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Body() dto: CreateGithubIssueDto,
        @UserId() userId: number,
    ): Promise<CreateGithubIssueResponseDto> {
        await this.chatService.publishGithubIssueCreateCommand({ channelId, userId }, dto);
        return { queued: true };
    }

    /**
     * 슬래시 커맨드를 Kafka로 비동기 발행한다.
     * 현재 지원 커맨드: `github.issue.create`.
     * 지원하지 않는 커맨드는 400을 반환한다.
     *
     * @param channelId - 채널 ID
     * @param dto - 커맨드 종류와 커맨드별 payload
     * @param userId - Gateway가 주입한 요청자 ID
     * @returns `{ queued: true }`
     */
    @Post('slash-commands')
    @HttpCode(HttpStatus.CREATED)
    @ApiOperation({
        summary: '슬래시 커맨드 발행',
        description: '현재 지원 커맨드: github.issue.create',
    })
    @ApiResponse({ status: 201, type: SlashCommandResponseDto })
    @ApiResponse({ status: 400, description: '지원하지 않는 커맨드 또는 유효하지 않은 payload' })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    async createSlashCommand(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Body() dto: SlashCommandDto,
        @UserId() userId: number,
    ): Promise<SlashCommandResponseDto> {
        await this.chatService.handleSlashCommand({ channelId, userId }, dto);
        return { queued: true };
    }

    /**
     * 채널 메시지를 최신순으로 커서 기반 페이지네이션 조회한다.
     * `before`에 이전 응답의 마지막 `_id`를 전달해 이전 메시지를 가져온다.
     * 한 번에 최대 100개를 반환한다.
     *
     * @param channelId - 채널 ID
     * @param query - 커서(`before`: MongoDB ObjectId 문자열)
     * @param userId - Gateway가 주입한 요청자 ID
     * @returns 메시지 배열 (reactions는 나의 반응 여부 포함)
     */
    @Post('read')
    @HttpCode(HttpStatus.NO_CONTENT)
    @ApiOperation({ summary: '채널 읽음 상태 업데이트' })
    @ApiResponse({ status: 204 })
    @ApiResponse({ status: 400, description: '유효하지 않은 메시지 ID' })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    async readChannel(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Body() dto: ReadChannelDto,
        @UserId() userId: number,
    ): Promise<void> {
        await this.chatService.readChannel({ channelId, userId }, dto.lastReadMessageId);
    }

    @Get('messages')
    @ApiOperation({ summary: '채널 메시지 목록 조회 (커서 기반 페이지네이션)' })
    @ApiResponse({ status: 200, type: [MessageResponseDto] })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    async getMessages(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Query() query: GetMessagesDto,
        @UserId() userId: number,
    ) {
        return this.chatService.getMessages({ channelId, userId }, query.before, query.parentMessageId);
    }

    /**
     * 메시지 내용을 수정한다. 내용이 동일하면 DB 업데이트 없이 현재 값을 반환한다.
     * 수정 성공 시 `chat:{channelId}` 룸에 `message:edited` 이벤트를 브로드캐스트한다.
     * ADMIN은 타인 메시지도 수정할 수 있다.
     *
     * @param channelId - 채널 ID
     * @param messageId - 수정할 메시지의 MongoDB ObjectId
     * @param dto - 새 메시지 내용
     * @param userId - Gateway가 주입한 요청자 ID
     * @param userRole - Gateway가 주입한 요청자 역할
     * @returns 수정된 메시지 도큐먼트
     */
    @Patch('messages/:messageId')
    @ApiOperation({ summary: '메시지 수정' })
    @ApiResponse({ status: 200, type: MessageResponseDto })
    @ApiResponse({ status: 403, description: '채널 멤버 아님 또는 본인 메시지 아님' })
    @ApiResponse({ status: 404, description: '메시지 없음' })
    async editMessage(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Param('messageId') messageId: string,
        @Body() dto: EditMessageDto,
        @UserId() userId: number,
        @UserRole() userRole: string,
    ) {
        return this.chatService.editMessage({ channelId, messageId, userId, userRole }, dto);
    }

    /**
     * 파일이 포함된 메시지와 MinIO 오브젝트를 함께 삭제한다.
     * MinIO 삭제 실패는 경고 로그만 남기고 메시지 삭제는 계속 진행한다.
     * 삭제 성공 시 `chat:{channelId}` 룸에 `message:deleted` 이벤트를 브로드캐스트한다.
     * ADMIN은 타인 파일도 삭제할 수 있다.
     *
     * @param channelId - 채널 ID
     * @param fileId - base64 인코딩된 파일 식별자 (messageId를 포함한 JSON)
     * @param userId - Gateway가 주입한 요청자 ID
     * @param userRole - Gateway가 주입한 요청자 역할
     * @returns 삭제된 채널 ID와 메시지 ID
     */
    @Delete('files/:fileId')
    @ApiOperation({ summary: '채팅 첨부파일 삭제 (파일이 포함된 메시지 전체 삭제)' })
    @ApiResponse({ status: 200, type: DeleteMessageResponseDto })
    @ApiResponse({ status: 400, description: '유효하지 않은 fileId' })
    @ApiResponse({ status: 403, description: '채널 멤버 아님 또는 권한 없음' })
    @ApiResponse({ status: 404, description: '파일(메시지)을 찾을 수 없음' })
    async deleteFile(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Param('fileId') fileId: string,
        @UserId() userId: number,
        @UserRole() userRole: string,
    ): Promise<DeleteMessageResponseDto> {
        return this.chatService.deleteFile({ channelId, userId, userRole }, fileId);
    }

    /**
     * 메시지를 삭제한다.
     * 삭제 성공 시 `chat:{channelId}` 룸에 `message:deleted` 이벤트를 브로드캐스트한다.
     * ADMIN은 타인 메시지도 삭제할 수 있다.
     *
     * @param channelId - 채널 ID
     * @param messageId - 삭제할 메시지의 MongoDB ObjectId
     * @param userId - Gateway가 주입한 요청자 ID
     * @param userRole - Gateway가 주입한 요청자 역할
     * @returns 삭제된 채널 ID와 메시지 ID
     */
    @Delete('messages/:messageId')
    @ApiOperation({ summary: '메시지 삭제' })
    @ApiResponse({ status: 200, type: DeleteMessageResponseDto })
    @ApiResponse({ status: 403, description: '채널 멤버 아님 또는 본인 메시지 아님' })
    @ApiResponse({ status: 404, description: '메시지 없음' })
    async deleteMessage(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Param('messageId') messageId: string,
        @UserId() userId: number,
        @UserRole() userRole: string,
    ) {
        return this.chatService.deleteMessage({ channelId, messageId, userId, userRole });
    }

    /**
     * 메시지를 고정한다. 이미 고정된 메시지이면 400을 반환한다.
     * 고정 성공 시 `chat:{channelId}` 룸에 `message:pinned` 이벤트를 브로드캐스트한다.
     * ADMIN은 타인 메시지도 고정할 수 있다.
     *
     * @param channelId - 채널 ID
     * @param messageId - 고정할 메시지의 MongoDB ObjectId
     * @param userId - Gateway가 주입한 요청자 ID
     * @param userRole - Gateway가 주입한 요청자 역할
     * @returns 업데이트된 메시지 도큐먼트
     */
    @Post('pins/:messageId')
    @HttpCode(HttpStatus.OK)
    @ApiOperation({ summary: '메시지 고정' })
    @ApiResponse({ status: 200, type: MessageResponseDto })
    @ApiResponse({ status: 400, description: '이미 고정된 메시지' })
    @ApiResponse({ status: 403, description: '채널 멤버 아님 또는 권한 없음' })
    @ApiResponse({ status: 404, description: '메시지 없음' })
    async pinMessage(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Param('messageId') messageId: string,
        @UserId() userId: number,
        @UserRole() userRole: string,
    ) {
        return this.chatService.pinMessage({ channelId, messageId, userId, userRole });
    }

    /**
     * 메시지 고정을 해제한다. 고정되지 않은 메시지이면 400을 반환한다.
     * 해제 성공 시 `chat:{channelId}` 룸에 `message:unpinned` 이벤트를 브로드캐스트한다.
     * ADMIN은 타인 메시지도 고정 해제할 수 있다.
     *
     * @param channelId - 채널 ID
     * @param messageId - 고정 해제할 메시지의 MongoDB ObjectId
     * @param userId - Gateway가 주입한 요청자 ID
     * @param userRole - Gateway가 주입한 요청자 역할
     */
    @Delete('pins/:messageId')
    @HttpCode(HttpStatus.NO_CONTENT)
    @ApiOperation({ summary: '메시지 고정 해제' })
    @ApiResponse({ status: 204 })
    @ApiResponse({ status: 400, description: '고정되지 않은 메시지' })
    @ApiResponse({ status: 403, description: '채널 멤버 아님 또는 권한 없음' })
    @ApiResponse({ status: 404, description: '메시지 없음' })
    async unpinMessage(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Param('messageId') messageId: string,
        @UserId() userId: number,
        @UserRole() userRole: string,
    ) {
        await this.chatService.unpinMessage({ channelId, messageId, userId, userRole });
    }

    /**
     * 메시지에 이모지 반응을 추가한다.
     * 이미 같은 이모지로 반응한 경우 무시한다(중복 반응 허용하지 않음).
     * 추가 성공 시 `chat:{channelId}` 룸에 `message:reaction:added` 이벤트를 브로드캐스트한다.
     * `emoji`는 단일 RGI 이모지여야 한다.
     *
     * @param channelId - 채널 ID
     * @param messageId - 반응을 추가할 메시지의 MongoDB ObjectId
     * @param dto - 이모지 문자
     * @param userId - Gateway가 주입한 요청자 ID
     */
    @Post('messages/:messageId/reactions')
    @HttpCode(HttpStatus.OK)
    @ApiOperation({ summary: '메시지 이모지 반응 추가' })
    @ApiResponse({ status: 200 })
    @ApiResponse({ status: 400, description: '유효하지 않은 이모지' })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    @ApiResponse({ status: 404, description: '메시지 없음' })
    async addReaction(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Param('messageId') messageId: string,
        @Body() dto: AddReactionDto,
        @UserId() userId: number,
    ): Promise<void> {
        await this.chatService.addReaction({ channelId, userId }, messageId, dto.emoji);
    }

    /**
     * 메시지에서 이모지 반응을 제거한다.
     * 반응이 없었던 경우 무시한다.
     * 제거 성공 시 `chat:{channelId}` 룸에 `message:reaction:removed` 이벤트를 브로드캐스트한다.
     * URL 경로의 `emoji` 파라미터는 단일 RGI 이모지여야 한다.
     *
     * @param channelId - 채널 ID
     * @param messageId - 반응을 제거할 메시지의 MongoDB ObjectId
     * @param emoji - URL 디코딩된 이모지 문자
     * @param userId - Gateway가 주입한 요청자 ID
     * @throws BadRequestException `emoji`가 유효한 이모지 형식이 아닐 때
     */
    @Delete('messages/:messageId/reactions/:emoji')
    @HttpCode(HttpStatus.OK)
    @ApiOperation({ summary: '메시지 이모지 반응 제거' })
    @ApiResponse({ status: 200 })
    @ApiResponse({ status: 400, description: '유효하지 않은 이모지' })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    @ApiResponse({ status: 404, description: '메시지 없음' })
    async removeReaction(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Param('messageId') messageId: string,
        @Param('emoji') emoji: string,
        @UserId() userId: number,
    ): Promise<void> {
        if (!EMOJI_REGEX.test(emoji)) {
            throw new BadRequestException('Invalid emoji format');
        }
        await this.chatService.removeReaction({ channelId, userId }, messageId, emoji);
    }

    /**
     * 채널에 고정된 메시지 목록을 최신순으로 최대 100개 조회한다.
     *
     * @param channelId - 채널 ID
     * @param userId - Gateway가 주입한 요청자 ID
     * @returns 고정 메시지 배열 (reactions는 나의 반응 여부 포함)
     */
    @Get('pins')
    @ApiOperation({ summary: '채널 고정 메시지 목록 조회' })
    @ApiResponse({ status: 200, type: [MessageResponseDto] })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    async getPinnedMessages(
        @Param('channelId', ParseIntPipe) channelId: number,
        @UserId() userId: number,
    ) {
        return this.chatService.getPinnedMessages({ channelId, userId });
    }
}
