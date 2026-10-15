import {ApiProperty} from '@nestjs/swagger';

export class AttachmentDownloadResponseDto {
    @ApiProperty({description: '권한 확인 후 발급한 60초 GET URL. 영구 저장하지 않습니다.'})
    downloadUrl!: string;

    @ApiProperty({description: 'URL 만료 시각 (ISO 8601)'})
    expiresAt!: string;

    @ApiProperty({example: 60})
    expiresInSeconds!: number;
}
