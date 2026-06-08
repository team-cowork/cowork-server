import { Body, Controller, HttpCode, HttpStatus, Param, Post } from '@nestjs/common';
import {
    ApiCreatedResponse,
    ApiForbiddenResponse,
    ApiNotFoundResponse,
    ApiOperation,
    ApiParam,
    ApiProperty,
    ApiTags,
} from '@nestjs/swagger';
import { IsNumber, IsPositive, IsString } from 'class-validator';
import { MinioService } from './minio.service';
import { UserId } from '../common/decorator/user.decorator';

class PresignedUploadRequestDto {
    @ApiProperty({ example: 'photo.png', description: '업로드할 파일 이름' })
    @IsString()
    filename!: string;

    @ApiProperty({ example: 'image/png', description: 'MIME 타입' })
    @IsString()
    contentType!: string;

    @ApiProperty({ example: 204800, description: '파일 크기 (bytes, 최대 100MB)' })
    @IsNumber()
    @IsPositive()
    size!: number;
}

class PresignedUploadResponseDto {
    @ApiProperty({ example: 'https://minio.example.com/dm/uuid.png?X-Amz-Signature=...', description: 'PUT 요청용 presigned URL' })
    url!: string;

    @ApiProperty({ example: 'dm/conversations/conv-id/uuid.png', description: '업로드 완료 확인용 object key' })
    key!: string;
}

@ApiTags('storage')
@Controller('conversations/:conversationId/upload')
export class StorageController {
    constructor(private readonly minioService: MinioService) {}

    @Post()
    @HttpCode(HttpStatus.CREATED)
    @ApiOperation({ summary: 'DM 파일 업로드용 presigned URL 발급 (분당 최대 20회)' })
    @ApiParam({ name: 'conversationId', description: '대화방 ObjectId' })
    @ApiCreatedResponse({ type: PresignedUploadResponseDto, description: 'Presigned URL 및 object key 반환' })
    @ApiNotFoundResponse({ description: '대화방 없음' })
    @ApiForbiddenResponse({ description: '대화방 참여자 아님' })
    createPresignedUpload(
        @UserId() userId: number,
        @Param('conversationId') conversationId: string,
        @Body() dto: PresignedUploadRequestDto,
    ) {
        return this.minioService.createPresignedUpload({
            conversationId,
            userId,
            filename: dto.filename,
            contentType: dto.contentType,
            size: dto.size,
        });
    }
}
