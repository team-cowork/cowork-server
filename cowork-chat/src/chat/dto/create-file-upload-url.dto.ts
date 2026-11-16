import {ApiProperty as apiProperty} from '@nestjs/swagger';
import {
	IsNotEmpty as isNotEmpty,
	IsNumber as isNumber,
	IsString as isString,
	MaxLength as maxLength,
	Min as min,
} from 'class-validator';

export class CreateFileUploadUrlRequestDto {
	@apiProperty({description: '원본 파일명', example: 'screenshot.png'})
	@isString()
	@isNotEmpty()
	@maxLength(255)
	filename!: string;

	@apiProperty({
		description: 'MIME 타입. 모든 형식을 받는다. 빈 값은 `application/octet-stream`으로 처리하고, HTML·SVG·XML·JavaScript는 `application/octet-stream`으로 저장한다. PUT 업로드에는 응답의 `headers`를 그대로 보낸다.',
		example: 'image/png',
	})
	@isString()
	@maxLength(255)
	contentType!: string;

	@apiProperty({description: '파일 크기(bytes)', example: 102_400})
	@isNumber()
	@min(1)
	size!: number;
}

export class CreateFileUploadUrlResponseDto {
	@apiProperty({description: 'S3 object key'})
	objectKey!: string;

	@apiProperty({description: '클라이언트가 PUT 업로드에 사용할 presigned URL'})
	uploadUrl!: string;

	@apiProperty({description: 'presigned URL 만료 시간(초)'})
	expiresInSeconds!: number;

	@apiProperty({description: 'PUT 요청 시 같이 보낼 헤더'})
	headers!: Record<string, string>;
}

export class ConfirmFileUploadRequestDto {
	@apiProperty({description: '업로드한 파일의 object key'})
	@isString()
	@isNotEmpty()
	objectKey!: string;
}

export class ConfirmFileUploadResponseDto {
	@apiProperty({description: '확정된 파일 key. 메시지 attachments.objectKey에 사용한다.'})
	objectKey!: string;

	@apiProperty() name!: string;
	@apiProperty() size!: number;
	@apiProperty() mimeType!: string;
}

