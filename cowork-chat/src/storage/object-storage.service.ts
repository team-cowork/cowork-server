import {randomUUID} from 'node:crypto';
import {
	BadRequestException,
	ForbiddenException,
	ConflictException,
	HttpException,
	HttpStatus,
	Inject as inject,
	Injectable as injectable,
	Logger,
	PayloadTooLargeException,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {
	CopyObjectCommand,
	DeleteObjectCommand,
	HeadObjectCommand,
	GetObjectCommand,
	PutObjectCommand,
	S3Client,
} from '@aws-sdk/client-s3';
import {getSignedUrl} from '@aws-sdk/s3-request-presigner';
import * as mime from 'mime-types';
import {RedisRateLimiter} from '../common/util/redis-rate-limiter.js';
import {AttachmentUploadRepository} from './attachment-upload.repository.js';
import {AttachmentUpload, ConfirmedAttachment} from './attachment-upload.schema.js';
import {S3_CLIENT, S3_SIGNING_CLIENT} from './object-storage.constants.js';
import {buildObjectStorageConfig, ObjectStorageConfig} from './object-storage.config.js';

/**
AWS S3 API가 정한 PascalCase 입력 필드 이름이다.
*/
const s3Field = {
	bucket: 'Bucket',
	key: 'Key',
	contentType: 'ContentType',
	copySource: 'CopySource',
	copySourceIfMatch: 'CopySourceIfMatch',
	metadataDirective: 'MetadataDirective',
	cacheControl: 'CacheControl',
	responseCacheControl: 'ResponseCacheControl',
	responseContentDisposition: 'ResponseContentDisposition',
} as const;

export type PresignedUpload = {
	objectKey: string;
	uploadUrl: string;
	expiresInSeconds: number;
	headers: {'Content-Type': string};
};

const fallbackContentType = 'application/octet-stream';
// RFC 6838 restricted-name. 값이 그대로 서명된 Content-Type 헤더가 된다.
const mimeTypePattern = /^[\da-z][\w!#$\u{26}+\-.^]{0,126}\/[\da-z][\w!#$\u{26}+\-.^]{0,126}$/iv;
// 브라우저가 열면 스크립트를 실행할 수 있는 형식이다. 업로드는 받되 octet-stream으로 저장한다.
const activeContentTypes = new Set([
	'text/html',
	'application/xhtml+xml',
	'image/svg+xml',
	'text/xml',
	'application/xml',
	'text/javascript',
	'application/javascript',
	'text/ecmascript',
	'application/ecmascript',
]);

function isNotFoundError(error: unknown): boolean {
	const error_ = error as {name?: string; $metadata?: {httpStatusCode?: number}};
	return error_.name === 'NotFound' || error_.$metadata?.httpStatusCode === 404;
}

function attachmentDisposition(filename: string): string {
	const fallback = filename.replaceAll(/["\\[^\u{20}-\u{7E}]]/gv, '_');
	const encoded = encodeURIComponent(filename).replaceAll(/['\(\)*]/gv, c => `%${c.codePointAt(0)!.toString(16).toUpperCase()}`);
	return `attachment; filename="${fallback}"; filename*=UTF-8''${encoded}`;
}

@injectable()
export class ObjectStorageService {
	private readonly logger = new Logger(ObjectStorageService.name);
	private readonly config: ObjectStorageConfig;

	@inject()
	private readonly rateLimiter!: RedisRateLimiter;

	constructor(
		@inject(S3_CLIENT) private readonly s3Client: S3Client,
		configService: ConfigService,
		@inject(S3_SIGNING_CLIENT) private readonly signingClient: S3Client,
		private readonly uploads: AttachmentUploadRepository,
	) {
		this.config = buildObjectStorageConfig(configService);
		this.validateCredentials();
	}

	private confirmedAttachment(upload: AttachmentUpload): ConfirmedAttachment {
		if (upload.objectKey === undefined || !Number.isSafeInteger(upload.size) || upload.size! <= 0) {
			throw new ConflictException('확인된 첨부파일 정보가 없습니다');
		}

		return {
			objectKey: upload.objectKey, name: upload.name, size: upload.size!, mimeType: upload.mimeType,
		};
	}

	private relayUrl(signedUrl: string): string {
		const signed = new URL(signedUrl);
		// Gateway restores the signed API Host and removes only /api/storage.
		return `${this.config.publicApiBaseUrl}/api/storage${signed.pathname}${signed.search}`;
	}

	private validateOwnedKey(objectKey: string, channelId: number, userId: number): void {
		if (!objectKey.startsWith(`chat-files/${channelId}/${userId}/`)
			|| !/^chat-files\/[1-9]\d*\/[1-9]\d*\/[\w\-]+\.[\da-z]+$/iv.test(objectKey)) {
			throw new BadRequestException('첨부파일 경로가 유효하지 않습니다');
		}
	}

	private validateCredentials(): void {
		if (this.config.accessKey === '' || this.config.secretKey === '') {
			throw new Error('오브젝트 스토리지 접근 키 설정이 필요합니다 (S3_ACCESS_KEY, S3_SECRET_KEY)');
		}
	}

	// 파일 형식은 제한하지 않는다. 브라우저가 형식을 모르면 빈 값을 보내므로 octet-stream으로 받는다.
	private normalizeContentType(contentType: string): string {
		const trimmed = contentType.trim();
		if (trimmed === '') {
			return fallbackContentType;
		}

		if (!mimeTypePattern.test(trimmed)) {
			throw new BadRequestException('파일 형식(MIME 타입)이 유효하지 않습니다');
		}

		return activeContentTypes.has(trimmed.toLowerCase()) ? fallbackContentType : trimmed;
	}

	private async checkUploadRateLimit(userId: number): Promise<void> {
		const isAllowed = await this.rateLimiter.tryAcquire(
			`object-storage:upload:${userId}`,
			this.config.uploadRateLimitWindowMs,
			this.config.uploadRateLimitMaxRequests,
		);
		if (!isAllowed) {
			throw new HttpException(
				'짧은 시간에 업로드 요청이 너무 많습니다. 잠시 후 다시 시도하세요',
				HttpStatus.TOO_MANY_REQUESTS,
			);
		}
	}

	private validateFileSize(size: number): void {
		if (!Number.isSafeInteger(size) || size <= 0) {
			throw new BadRequestException('파일 크기 메타데이터가 유효하지 않습니다');
		}

		if (size > this.config.maxFileSizeBytes) {
			throw new PayloadTooLargeException('파일 크기가 허용 한도를 초과했습니다');
		}
	}

	private buildObjectKey(channelId: number, userId: number, filename: string, contentType: string): string {
		const extension = this.resolveExtension(filename, contentType);
		return `chat-files/${channelId}/${userId}/${randomUUID()}.${extension}`;
	}

	private resolveExtension(filename: string, contentType: string): string {
		const parts = filename.split('.');
		const extensionFromName = parts.length > 1 ? parts.pop()?.toLowerCase() : undefined;
		if (extensionFromName !== undefined && extensionFromName !== '' && /^[0-9a-z]+$/v.test(extensionFromName)) {
			return extensionFromName;
		}

		const extensionFromMime = mime.extension(contentType);
		return extensionFromMime === false || extensionFromMime === '' ? 'bin' : extensionFromMime;
	}

	async createPresignedUpload(parameters: {
		channelId: number;
		userId: number;
		filename: string;
		contentType: string;
		size: number;
	}): Promise<PresignedUpload> {
		const contentType = this.normalizeContentType(parameters.contentType);
		this.validateFileSize(parameters.size);
		await this.checkUploadRateLimit(parameters.userId);

		const objectKey = this.buildObjectKey(parameters.channelId, parameters.userId, parameters.filename, contentType)
			.replace('chat-files/', 'chat-uploads/');
		await this.uploads.create({
			uploadKey: objectKey, channelId: parameters.channelId, userId: parameters.userId,
			name: parameters.filename, mimeType: contentType, declaredSize: parameters.size,
		});
		const uploadUrl = await getSignedUrl(
			this.signingClient,
			new PutObjectCommand({[s3Field.bucket]: this.config.bucket, [s3Field.key]: objectKey, [s3Field.contentType]: contentType}),
			{expiresIn: this.config.presignedPutExpirySeconds, signableHeaders: new Set(['content-type'])},
		);

		return {
			objectKey,
			uploadUrl: this.relayUrl(uploadUrl),
			expiresInSeconds: this.config.presignedPutExpirySeconds,
			headers: {'Content-Type': contentType},
		};
	}

	async confirmUpload(channelId: number, userId: number, uploadKey: string): Promise<ConfirmedAttachment> {
		this.validateOwnedKey(uploadKey.replace('chat-uploads/', 'chat-files/'), channelId, userId);
		const upload = await this.uploads.find(uploadKey);
		if (upload?.channelId !== channelId || upload.userId !== userId) {
			throw new ForbiddenException('직접 발급한 업로드만 확인할 수 있습니다');
		}

		if (upload.objectKey !== undefined) {
			return this.confirmedAttachment(upload);
		}

		// Each attempt gets its own immutable destination. A delayed copy cannot overwrite a CAS winner.
		const objectKey = this.buildObjectKey(channelId, userId, upload.name, upload.mimeType);
		try {
			const stat = await this.s3Client.send(new HeadObjectCommand({[s3Field.bucket]: this.config.bucket, [s3Field.key]: uploadKey}));
			this.validateFileSize(stat.ContentLength ?? NaN);
			if (stat.ContentType !== upload.mimeType || stat.ContentLength !== upload.declaredSize || stat.ETag === undefined) {
				throw new BadRequestException('발급한 크기·형식과 업로드한 파일 정보가 다릅니다');
			}

			await this.s3Client.send(new CopyObjectCommand({
				[s3Field.bucket]: this.config.bucket, [s3Field.key]: objectKey,
				[s3Field.copySource]: `${this.config.bucket}/${uploadKey}`, [s3Field.copySourceIfMatch]: stat.ETag,
				[s3Field.metadataDirective]: 'REPLACE', [s3Field.contentType]: upload.mimeType, [s3Field.cacheControl]: 'private, no-store',
			}));
			const confirmed = await this.uploads.confirm(uploadKey, {
				objectKey, name: upload.name, size: stat.ContentLength, mimeType: upload.mimeType,
			});
			if (confirmed) {
				await this.removeObject(uploadKey).catch((error: unknown) => {
					this.logger.warn('Failed to remove confirmed temporary upload', error);
				});
				return this.confirmedAttachment(confirmed);
			}

			await this.removeObject(objectKey);
			const winner = await this.uploads.find(uploadKey);
			if (winner?.objectKey !== undefined) {
				return this.confirmedAttachment(winner);
			}

			throw new ConflictException('업로드 완료를 다시 요청해야 합니다');
		} catch (error) {
			// Another successful confirmation may already have removed the temporary object.
			const winner = await this.uploads.find(uploadKey);
			if (winner?.objectKey !== undefined) {
				return this.confirmedAttachment(winner);
			}

			if (isNotFoundError(error) || (error as {$metadata?: {httpStatusCode?: number}}).$metadata?.httpStatusCode === 412) {
				throw new ConflictException('업로드한 파일이 없거나 변경되었습니다. 다시 업로드하세요');
			}

			throw error;
		}
	}

	async releaseUploads(objectKeys: string[]): Promise<void> {
		await this.uploads.removeByObjectKeys(objectKeys);
	}

	async claimAttachments(
		attachments: Array<{objectKey: string; spoiler?: boolean}>, channelId: number, userId: number, clientMessageId: string,
	): Promise<ConfirmedAttachment[]> {
		if (new Set(attachments.map(item => item.objectKey)).size !== attachments.length) {
			throw new BadRequestException('동일한 첨부파일을 중복해서 연결할 수 없습니다');
		}

		for (const attachment of attachments) {
			this.validateOwnedKey(attachment.objectKey, channelId, userId);
		}

		const uploads = await Promise.all(
			attachments.map(async attachment => this.uploads.claim(attachment.objectKey, channelId, userId, clientMessageId)),
		);
		return uploads.map((upload, index) => {
			if (!upload) {
				throw new ForbiddenException('확인된 본인 업로드만 한 메시지에 연결할 수 있습니다');
			}

			return {...this.confirmedAttachment(upload), spoiler: attachments[index].spoiler ?? false};
		});
	}

	async createPresignedDownload(objectKey: string, channelId: number, authorId: number, filename: string): Promise<{
		downloadUrl: string;
		expiresAt: string;
		expiresInSeconds: number;
	}> {
		this.validateOwnedKey(objectKey, channelId, authorId);
		const signingDate = new Date();
		const expiresInSeconds = 60;
		const downloadUrl = await getSignedUrl(this.signingClient, new GetObjectCommand({
			[s3Field.bucket]: this.config.bucket,
			[s3Field.key]: objectKey,
			[s3Field.responseCacheControl]: 'private, no-store',
			[s3Field.responseContentDisposition]: attachmentDisposition(filename),
		}), {expiresIn: expiresInSeconds, signingDate});
		return {downloadUrl: this.relayUrl(downloadUrl), expiresInSeconds, expiresAt: new Date(signingDate.getTime() + (expiresInSeconds * 1000)).toISOString()};
	}

	async removeObject(objectKey: string): Promise<void> {
		if (!/^chat-(?:files|uploads)\/[1-9]\d*\/[1-9]\d*\/[\w\-]+\.[\da-z]+$/iv.test(objectKey)) {
			throw new BadRequestException('삭제할 첨부파일 경로가 유효하지 않습니다');
		}

		try {
			await this.s3Client.send(new DeleteObjectCommand({[s3Field.bucket]: this.config.bucket, [s3Field.key]: objectKey}));
		} catch (error) {
			// 이미 없는 객체는 삭제가 끝난 것이다. 버킷이 없는 404(NoSuchBucket)는 재시도하도록 오류로 남긴다.
			if (!['NoSuchKey', 'NotFound'].includes((error as {name?: string}).name ?? '')) {
				throw error;
			}
		}
	}

	extractObjectKey(fileUrl: string): string {
		const prefix = this.config.publicBaseUrl + '/';
		if (!fileUrl.startsWith(prefix)) {
			throw new BadRequestException('유효하지 않은 파일 URL입니다');
		}

		return fileUrl.slice(prefix.length);
	}
}
