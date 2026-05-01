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
} from '@nestjs/common';
import { ApiOperation, ApiResponse, ApiTags } from '@nestjs/swagger';
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
import { ChatMessageProducer } from './kafka/chat-message.producer';
import { MessageDocument } from './schema/message.schema';
import { UserId, UserRole } from '../common/decorator/user.decorator';
import { MinioService } from '../storage/minio.service';

@ApiTags('Chat')
@Controller('channels/:channelId')
export class ChatController {
    constructor(
        private readonly chatService: ChatService,
        private readonly chatGateway: ChatGateway,
        private readonly producer: ChatMessageProducer,
        private readonly minioService: MinioService,
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
    @ApiResponse({ status: 201, description: '메시지 큐 적재 성공' })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    async sendMessage(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Body() dto: SendMessageDto,
        @UserId() userId: number,
        @UserRole() userRole: string,
    ) {
        await this.chatService.checkMembership(channelId, userId);
        await this.producer.sendMessage({ ...dto, channelId }, userId, userRole);

        return { queued: true };
    }

    @Get('messages')
    @ApiOperation({ summary: '채널 메시지 목록 조회 (커서 기반 페이지네이션)' })
    @ApiResponse({ status: 200, description: '메시지 목록 반환' })
    @ApiResponse({ status: 403, description: '채널 멤버 아님' })
    async getMessages(
        @Param('channelId', ParseIntPipe) channelId: number,
        @Query() query: GetMessagesDto,
        @UserId() userId: number,
    ) {
        await this.chatService.checkMembership(channelId, userId);
        return this.chatService.getMessages(channelId, query.before);
    }

    @Patch('messages/:messageId')
    @ApiOperation({ summary: '메시지 수정' })
    @ApiResponse({ status: 200, description: '수정된 메시지 반환' })
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
    @ApiResponse({ status: 200, description: '삭제 완료' })
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
