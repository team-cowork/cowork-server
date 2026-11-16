import {type ConfigService} from '@nestjs/config';
import {getOptionalConfig, getRequiredConfig} from '../common/config/config.util.js';

const DEFAULT_PRESIGNED_PUT_EXPIRY_SECONDS = 600;
const DEFAULT_MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024;
const DEFAULT_UPLOAD_RATE_LIMIT_WINDOW_MS = 60 * 1000;
const DEFAULT_UPLOAD_RATE_LIMIT_MAX_REQUESTS = 20;

// 다운로드는 항상 attachment로 내려간다. 그래도 브라우저가 실행할 수 있는 HTML·SVG·스크립트와 실행 파일은 넣지 않는다.
const DEFAULT_CHAT_ALLOWED_CONTENT_TYPES = [
	// 이미지
	'image/jpeg',
	'image/png',
	'image/gif',
	'image/webp',
	'image/heic',
	'image/heif',
	'image/avif',
	'image/bmp',
	'image/tiff',
	// 영상
	'video/mp4',
	'video/quicktime',
	'video/x-msvideo',
	'video/webm',
	'video/x-matroska',
	'video/mpeg',
	'video/3gpp',
	// 음성
	'audio/mpeg',
	'audio/ogg',
	'audio/wav',
	'audio/x-wav',
	'audio/mp4',
	'audio/x-m4a',
	'audio/aac',
	'audio/flac',
	'audio/webm',
	// 텍스트
	'application/pdf',
	'text/plain',
	'text/csv',
	'text/markdown',
	'application/rtf',
	'application/json',
	// Microsoft Office
	'application/msword',
	'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
	'application/vnd.ms-excel',
	'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
	'application/vnd.ms-powerpoint',
	'application/vnd.openxmlformats-officedocument.presentationml.presentation',
	// OpenDocument
	'application/vnd.oasis.opendocument.text',
	'application/vnd.oasis.opendocument.spreadsheet',
	'application/vnd.oasis.opendocument.presentation',
	// Apple iWork
	'application/vnd.apple.pages',
	'application/vnd.apple.numbers',
	'application/vnd.apple.keynote',
	// 한글(HWP·HWPX)은 OS와 브라우저마다 다른 MIME 타입을 보낸다.
	'application/x-hwp',
	'application/haansofthwp',
	'application/vnd.hancom.hwp',
	'application/haansofthwpx',
	'application/vnd.hancom.hwpx',
	// 압축
	'application/zip',
	'application/x-zip-compressed',
	'application/x-7z-compressed',
	'application/vnd.rar',
	'application/x-rar-compressed',
	'application/x-tar',
	'application/gzip',
	'application/x-gzip',
];

export type ObjectStorageConfig = {
	internalEndpoint: string;
	publicApiBaseUrl: string;
	region: string;
	accessKey: string;
	secretKey: string;
	bucket: string;
	publicBaseUrl: string;
	presignedPutExpirySeconds: number;
	maxFileSizeBytes: number;
	allowedContentTypes: string[];
	uploadRateLimitWindowMs: number;
	uploadRateLimitMaxRequests: number;
};

export function buildObjectStorageConfig(configService: ConfigService): ObjectStorageConfig {
	const internalEndpoint = getRequiredConfig(configService, [
		'object-storage.endpoint',
		'S3_ENDPOINT',
		'S3_INTERNAL_ENDPOINT',
	]);
	const publicApiBaseUrl = getRequiredConfig(configService, 'PUBLIC_API_BASE_URL').replace(/\/$/v, '');
	const profile = getOptionalConfig(configService, 'APP_CONFIG_PROFILE') ?? 'local';
	const isProduction = profile === 'prod';
	validateEndpoint(internalEndpoint, false);
	validateEndpoint(publicApiBaseUrl, isProduction);
	const accessKey = getRequiredConfig(configService, ['object-storage.accessKey', 'S3_ACCESS_KEY']);
	const secretKey = getRequiredConfig(configService, ['object-storage.secretKey', 'S3_SECRET_KEY']);
	const bucket = getRequiredConfig(configService, ['object-storage.bucket', 'S3_BUCKET']);

	return {
		internalEndpoint,
		publicApiBaseUrl,
		region: getOptionalConfig(configService, ['object-storage.region', 'S3_REGION']) ?? 'us-east-1',
		accessKey,
		secretKey,
		bucket,
		publicBaseUrl: `${publicApiBaseUrl}/api/storage/${bucket}`,
		presignedPutExpirySeconds: Number(getOptionalConfig(
			configService,
			['object-storage.presignedPutExpirySeconds', 'S3_PRESIGNED_PUT_EXPIRY_SECONDS'],
		) ?? DEFAULT_PRESIGNED_PUT_EXPIRY_SECONDS),
		maxFileSizeBytes: Number(getOptionalConfig(
			configService,
			['object-storage.chatMaxFileSizeBytes', 'S3_CHAT_MAX_FILE_SIZE_BYTES'],
		) ?? DEFAULT_MAX_FILE_SIZE_BYTES),
		allowedContentTypes: (
			getOptionalConfig(configService, ['object-storage.chatAllowedContentTypes', 'S3_CHAT_ALLOWED_CONTENT_TYPES'])
			?? DEFAULT_CHAT_ALLOWED_CONTENT_TYPES.join(',')
		)
			.split(',')
			.map(contentType => contentType.trim())
			.filter(Boolean),
		uploadRateLimitWindowMs: Number(getOptionalConfig(
			configService,
			['object-storage.chatUploadRateLimitWindowMs', 'S3_CHAT_UPLOAD_RATE_LIMIT_WINDOW_MS'],
		) ?? DEFAULT_UPLOAD_RATE_LIMIT_WINDOW_MS),
		uploadRateLimitMaxRequests: Number(getOptionalConfig(
			configService,
			['object-storage.chatUploadRateLimitMaxRequests', 'S3_CHAT_UPLOAD_RATE_LIMIT_MAX_REQUESTS'],
		) ?? DEFAULT_UPLOAD_RATE_LIMIT_MAX_REQUESTS),
	};
}

function validateEndpoint(endpoint: string, isProduction: boolean): void {
	const url = new URL(endpoint);
	if (!['http:', 'https:'].includes(url.protocol) || url.username !== '' || url.password !== ''
		|| url.pathname !== '/' || url.search !== '' || url.hash !== ''
		|| (isProduction && (url.protocol !== 'https:' || ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)))) {
		throw new Error('Storage and API endpoints must be HTTP(S) origins; production API requires HTTPS and a non-local host');
	}
}
