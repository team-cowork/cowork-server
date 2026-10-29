import {ApiProperty as apiProperty} from '@nestjs/swagger';

export class AttachmentDownloadResponseDto {
	@apiProperty({description: '권한 확인 후 발급한 60초 GET URL. 영구 저장하지 않습니다.'})
	downloadUrl!: string;

	@apiProperty({description: 'URL 만료 시각 (ISO 8601)'})
	expiresAt!: string;

	@apiProperty({example: 60})
	expiresInSeconds!: number;
}
