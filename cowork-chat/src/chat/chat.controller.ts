import {
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
    BadRequestException,
    ForbiddenException,
} from '@nestjs/common';
import { ApiHeader, ApiOperation, ApiResponse, ApiTags } from '@nestjs/swagger';
import { ChatService } from './chat.service';
import { ChatGateway } from './chat.gateway';
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
import {
    GithubIssueSlashCommandPayloadDto,
    SlashCommand,
    SlashCommandDto,
} from './dto/slash-command.dto';
import { ChatMessageProducer } from './kafka/chat-message.producer';
import { GithubIssueProducer } from './kafka/github-issue.producer';
import { ProjectClient } from './service/project.client';
import { MessageDocument } from './schema/message.schema';
import { UserId, UserRole } from '../common/decorator/user.decorator';
import { MinioService } from '../storage/minio.service';

@ApiTags('Chat')
@ApiHeader({ name: 'X-User-Id', description: 'Gateway 주입 유저 ID', required: true })
@ApiHeader({ name: 'X-User-Role', description: 'Gateway 주입 유저 역할 (ADMIN | MEMBER)', required: true })
@Controller('channels/:channelId')
export class ChatController {
    constructor(
        private readonly chatService: ChatService,
        private readonly chatGateway: ChatGateway,
        private readonly producer: ChatMessageProducer,
        private readonly minioService: MinioService,
        private readonly githubIssueProducer: GithubIssueProducer,
        private readonly projectClient: ProjectClient,
    ) {}

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
        await this.chatService.checkMembership(channelId, userId);
        const upload = await this.minioService.createPresignedUpload({
            channelId,
            userId,
            filename: dto.filename,
            contentType: dto.contentType,
            size: dto.size,
        });

        return {
            ...upload,
            headers: {
                'Content-Type': dto.contentType,
            },
        };
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
        await this.chatService.checkMembership(channelId, userId);
        const fileUrl = await this.minioService.confirmUpload(channelId, userId, dto.objectKey);
        return { fileUrl };
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
        await this.chatService.checkMembership(channelId, userId);
        await this.producer.sendMessage(channelId, dto, userId, userRole);
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
        return this.publishGithubIssueCreateCommand(channelId, dto, userId);
    }

    @Post('slash-commands')
    @HttpCode(HttpStatus.CREATED)
    @ApiOperation({
        summary: '슬래시 커맨드 발행',
        description: '현재 지원 커맨드: github.issue.create',
    })
    @ApiResponse({ status: 201, type: CreateGithubIssueResponseDto })
    @ApiResponse({ status: 400, description: '지원하지 않는 커맨드 또는 유효하지 않은 payload' })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    async createSlashCommand(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Body() dto: SlashCommandDto,
        @UserId() userId: number,
    ): Promise<CreateGithubIssueResponseDto> {
        if (dto.command !== SlashCommand.GITHUB_ISSUE_CREATE) {
            throw new BadRequestException('지원하지 않는 슬래시 커맨드입니다');
        }

        return this.publishGithubIssueCreateCommand(channelId, dto.payload, userId);
    }

    private async publishGithubIssueCreateCommand(
        channelId: number,
        dto: CreateGithubIssueDto | GithubIssueSlashCommandPayloadDto,
        userId: number,
    ): Promise<CreateGithubIssueResponseDto> {
        const channelTeamId = await this.chatService.checkMembershipAndGetTeamId(channelId, userId);
        const repoInfo = await this.projectClient.getGithubRepoInfo(dto.projectId);
        if (!repoInfo) {
            throw new BadRequestException('프로젝트 GitHub 레포지토리 정보를 찾을 수 없습니다');
        }
        if (channelTeamId !== repoInfo.teamId) {
            throw new ForbiddenException('해당 프로젝트는 이 채널의 팀에 속하지 않습니다');
        }

        await this.githubIssueProducer.send({
            channelId,
            teamId: repoInfo.teamId,
            projectId: dto.projectId,
            owner: repoInfo.owner,
            repo: repoInfo.repo,
            title: dto.title,
            body: dto.body,
            requesterId: userId,
        });
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
    ): Promise<MessageResponseDto[]> {
        await this.chatService.checkMembership(channelId, userId);
        return this.chatService.getMessages(channelId, query.before) as any;
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
        await this.chatService.checkMembership(channelId, userId);
        const updated = await this.chatService.editMessage(messageId, userId, dto, userRole);

        this.chatGateway.server
            ?.to(`chat:${channelId}`)
            .emit('message:edited', {
                messageId,
                content: updated.content,
                editedAt: (updated as MessageDocument).updatedAt?.toISOString() ?? new Date().toISOString(),
            });

        return updated;
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
        await this.chatService.checkMembership(channelId, userId);
        const result = await this.chatService.deleteMessage(messageId, userId, userRole);

        this.chatGateway.server
            ?.to(`chat:${channelId}`)
            .emit('message:deleted', { messageId });

        return result;
    }
}
