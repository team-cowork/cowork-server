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
import { MessageRow } from './repository/message.repository';
import { AddReactionDto } from './dto/add-reaction.dto';

@ApiTags('Chat')
@ApiHeader({ name: 'X-User-Id', description: 'Gateway 주입 유저 ID', required: true })
@ApiHeader({ name: 'X-User-Role', description: 'Gateway 주입 유저 역할 (ADMIN | MEMBER)', required: true })
@Controller('channels/:channelId')
export class ChatController {
    constructor(private readonly chatService: ChatService) {}

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

    @Get('messages')
    @ApiOperation({ summary: '채널 메시지 목록 조회 (커서 기반 페이지네이션)' })
    @ApiResponse({ status: 200, type: [MessageResponseDto] })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    async getMessages(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Query() query: GetMessagesDto,
        @UserId() userId: number,
    ): Promise<MessageRow[]> {
        return this.chatService.getMessages({ channelId, userId }, query.before);
    }

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
        if (!/^(\p{Emoji_Presentation}|\p{Extended_Pictographic})+$/u.test(emoji)) {
            throw new BadRequestException('Invalid emoji format');
        }
        await this.chatService.removeReaction({ channelId, userId }, messageId, emoji);
    }

    @Get('pins')
    @ApiOperation({ summary: '채널 고정 메시지 목록 조회' })
    @ApiResponse({ status: 200, type: [MessageResponseDto] })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    async getPinnedMessages(
        @Param('channelId', ParseIntPipe) channelId: number,
        @UserId() userId: number,
    ): Promise<MessageRow[]> {
        return this.chatService.getPinnedMessages({ channelId, userId });
    }
}
