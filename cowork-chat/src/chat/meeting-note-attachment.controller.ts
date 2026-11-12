import {
	Controller as controller,
	Get as httpGet,
	Header as responseHeader,
	Param as requestParameter,
	Query as requestQuery,
} from '@nestjs/common';
import {
	ApiOperation as apiOperation,
	ApiQuery as apiQuery,
	ApiResponse as apiResponse,
	ApiTags as apiTags,
	ApiExtraModels as apiExtraModels,
	getSchemaPath,
} from '@nestjs/swagger';
import {currentUserId} from '../common/decorator/user.decorator.js';
import {SafePositiveIntPipe} from '../common/pipe/safe-positive-int.pipe.js';
import {ApiResponseMetadataDto} from '../common/dto/api-response-metadata.dto.js';
import {AttachmentDownloadResponseDto} from './dto/attachment-download.dto.js';
import {MeetingNoteAttachmentDownloadService} from './service/meeting-note-attachment-download.service.js';

@apiTags('회의록 첨부파일')
@apiExtraModels(ApiResponseMetadataDto, AttachmentDownloadResponseDto)
@controller('channels/:channelId/meeting-notes/:noteId/attachments')
export class MeetingNoteAttachmentController {
	constructor(private readonly downloads: MeetingNoteAttachmentDownloadService) {}

	@httpGet('download-url')
	@responseHeader('Cache-Control', 'private, no-store')
	@apiOperation({summary: '회의록 첨부파일 다운로드 URL 발급', description: '회의록 attachments에 등록한 objectKey를 사용합니다. URL은 60초 동안 유효합니다.'})
	@apiQuery({name: 'objectKey', required: true, type: String})
	@apiResponse({
		status: 200, description: '다운로드 URL 발급 성공', schema: {
			allOf: [
				{$ref: getSchemaPath(ApiResponseMetadataDto)},
				{type: 'object', required: ['data'], properties: {data: {$ref: getSchemaPath(AttachmentDownloadResponseDto)}}},
			],
		},
	})
	@apiResponse({status: 400, description: '잘못된 식별자 또는 objectKey', type: ApiResponseMetadataDto})
	@apiResponse({status: 401, description: '인증 필요', type: ApiResponseMetadataDto})
	@apiResponse({status: 403, description: '채널 접근 권한 없음', type: ApiResponseMetadataDto})
	@apiResponse({status: 404, description: '회의록 또는 첨부파일 없음', type: ApiResponseMetadataDto})
	@apiResponse({status: 429, description: '요청 한도 초과', type: ApiResponseMetadataDto})
	@apiResponse({status: 503, description: '권한 또는 첨부파일 정보 동기화 중', type: ApiResponseMetadataDto})
	async download(
		@requestParameter('channelId', SafePositiveIntPipe) channelId: number,
		@requestParameter('noteId', SafePositiveIntPipe) noteId: number,
		@currentUserId() userId: number,
		@requestQuery('objectKey') objectKey: string,
	): Promise<AttachmentDownloadResponseDto> {
		return this.downloads.execute(channelId, noteId, userId, objectKey);
	}
}
