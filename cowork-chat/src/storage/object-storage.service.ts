import { randomUUID } from 'node:crypto';
import {
    BadRequestException,
    ForbiddenException,
    ConflictException,
    HttpException,
    HttpStatus,
    Inject,
    Injectable,
    Logger,
    OnModuleDestroy,
    OnModuleInit,
    PayloadTooLargeException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import {
    CopyObjectCommand,
    DeleteObjectCommand,
    HeadObjectCommand,
    GetObjectCommand,
    PutObjectCommand,
    S3Client,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import * as mime from 'mime-types';
import { AttachmentUploadRepository } from './attachment-upload.repository';
import { AttachmentUpload, ConfirmedAttachment } from './attachment-upload.schema';
import { S3_CLIENT, S3_SIGNING_CLIENT } from './object-storage.constants';
import { buildObjectStorageConfig, ObjectStorageConfig } from './object-storage.config';

export type PresignedUpload = {
    objectKey: string;
    uploadUrl: string;
    expiresInSeconds: number;
};

function isNotFoundError(error: unknown): boolean {
    const error_ = error as { name?: string; $metadata?: { httpStatusCode?: number } };
    return error_.name === 'NotFound' || error_.$metadata?.httpStatusCode === 404;
}

@Injectable()
export class ObjectStorageService implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ObjectStorageService.name);
    private readonly config: ObjectStorageConfig;
    private readonly uploadRateLimitBuckets = new Map<number, number[]>();
    private cleanupTimer?: ReturnType<typeof setInterval>;
    private isCleaningUpRateLimitEntries = false;

    constructor(
        @Inject(S3_CLIENT) private readonly s3Client: S3Client,
        configService: ConfigService,
        @Inject(S3_SIGNING_CLIENT) private readonly signingClient: S3Client,
        private readonly uploads: AttachmentUploadRepository,
    ) {
        this.config = buildObjectStorageConfig(configService);
        this.validateCredentials();
    }

    onModuleInit(): void {
        this.cleanupTimer = setInterval(() => {
            if (this.isCleaningUpRateLimitEntries) {
                return;
            }

            this.isCleaningUpRateLimitEntries = true;
            try {
                this.cleanupStaleRateLimitEntries();
            } finally {
                this.isCleaningUpRateLimitEntries = false;
            }
        }, this.config.uploadRateLimitWindowMs);
    }

    onModuleDestroy(): void {
        if (!this.cleanupTimer) {
            return;
        }

        clearInterval(this.cleanupTimer);
        this.cleanupTimer = undefined;
    }

    async createPresignedUpload(parameters: {
        channelId: number;
        userId: number;
        filename: string;
        contentType: string;
        size: number;
    }): Promise<PresignedUpload> {
        this.checkUploadRateLimit(parameters.userId);
        this.validateContentType(parameters.contentType);
        this.validateFileSize(parameters.size);

        const objectKey = this.buildObjectKey(parameters.channelId, parameters.userId, parameters.filename, parameters.contentType)
            .replace('chat-files/', 'chat-uploads/');
        await this.uploads.create({
            uploadKey: objectKey, channelId: parameters.channelId, userId: parameters.userId,
            name: parameters.filename, mimeType: parameters.contentType, declaredSize: parameters.size,
        });
        const uploadUrl = await getSignedUrl(
            this.signingClient,
            new PutObjectCommand({ Bucket: this.config.bucket, Key: objectKey, ContentType: parameters.contentType }),
            { expiresIn: this.config.presignedPutExpirySeconds, signableHeaders: new Set(['content-type']) },
        );

        return {
            objectKey,
            uploadUrl: this.relayUrl(uploadUrl),
            expiresInSeconds: this.config.presignedPutExpirySeconds,
        };
    }

    async confirmUpload(channelId: number, userId: number, uploadKey: string): Promise<ConfirmedAttachment> {
        this.validateOwnedKey(uploadKey.replace('chat-uploads/', 'chat-files/'), channelId, userId);
        const upload = await this.uploads.find(uploadKey);
        if (upload?.channelId !== channelId || upload.userId !== userId) {
            throw new ForbiddenException('직접 발급한 업로드만 확인할 수 있습니다');
        }

        if (upload.objectKey) {
            return this.confirmedAttachment(upload);
        }

        // Each attempt gets its own immutable destination. A delayed copy cannot overwrite a CAS winner.
        const objectKey = this.buildObjectKey(channelId, userId, upload.name, upload.mimeType);
        try {
            const stat = await this.s3Client.send(new HeadObjectCommand({ Bucket: this.config.bucket, Key: uploadKey }));
            this.validateFileSize(stat.ContentLength ?? NaN);
            this.validateContentType(stat.ContentType ?? '');
            if (stat.ContentType !== upload.mimeType || stat.ContentLength !== upload.declaredSize || !stat.ETag) {
                throw new BadRequestException('발급한 크기·형식과 업로드한 파일 정보가 다릅니다');
            }

            await this.s3Client.send(new CopyObjectCommand({
                Bucket: this.config.bucket, Key: objectKey,
                CopySource: `${this.config.bucket}/${uploadKey}`, CopySourceIfMatch: stat.ETag,
                MetadataDirective: 'REPLACE', ContentType: upload.mimeType, CacheControl: 'private, no-store',
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
            if (winner?.objectKey) {
                return this.confirmedAttachment(winner);
            }

            throw new ConflictException('업로드 완료를 다시 요청해야 합니다');
        } catch (error) {
            // Another successful confirmation may already have removed the temporary object.
            const winner = await this.uploads.find(uploadKey);
            if (winner?.objectKey) {
                return this.confirmedAttachment(winner);
            }

            if (isNotFoundError(error) || (error as { $metadata?: { httpStatusCode?: number } }).$metadata?.httpStatusCode === 412) {
                throw new ConflictException('업로드한 파일이 없거나 변경되었습니다. 다시 업로드하세요');
            }

            throw error;
        }
    }

    async claimAttachments(
        attachments: Array<{ objectKey: string }>, channelId: number, userId: number, clientMessageId: string,
    ): Promise<ConfirmedAttachment[]> {
        if (new Set(attachments.map(item => item.objectKey)).size !== attachments.length) {
            throw new BadRequestException('동일한 첨부파일을 중복해서 연결할 수 없습니다');
        }

        const confirmed: ConfirmedAttachment[] = [];
        for (const attachment of attachments) {
            this.validateOwnedKey(attachment.objectKey, channelId, userId);
            const upload = await this.uploads.claim(attachment.objectKey, channelId, userId, clientMessageId);
            if (!upload) {
                throw new ForbiddenException('확인된 본인 업로드만 한 메시지에 연결할 수 있습니다');
            }

            confirmed.push(this.confirmedAttachment(upload));
        }

        return confirmed;
    }

    private confirmedAttachment(upload: AttachmentUpload): ConfirmedAttachment {
        if (!upload.objectKey || !Number.isSafeInteger(upload.size) || upload.size! <= 0) {
            throw new ConflictException('확인된 첨부파일 정보가 없습니다');
        }

        return {
            objectKey: upload.objectKey, name: upload.name, size: upload.size!, mimeType: upload.mimeType,
        };
    }

    async createPresignedDownload(objectKey: string, channelId: number, authorId: number): Promise<{
        downloadUrl: string;
        expiresAt: string;
        expiresInSeconds: number;
    }> {
        this.validateOwnedKey(objectKey, channelId, authorId);
        const signingDate = new Date();
        const expiresInSeconds = 60;
        const downloadUrl = await getSignedUrl(this.signingClient, new GetObjectCommand({
            Bucket: this.config.bucket,
            Key: objectKey,
            ResponseCacheControl: 'private, no-store',
        }), { expiresIn: expiresInSeconds, signingDate });
        return { downloadUrl: this.relayUrl(downloadUrl), expiresInSeconds, expiresAt: new Date(signingDate.getTime() + (expiresInSeconds * 1000)).toISOString() };
    }

    private relayUrl(signedUrl: string): string {
        const signed = new URL(signedUrl);
        // Gateway restores the signed API Host and removes only /api/storage.
        return `${this.config.publicApiBaseUrl}/api/storage${signed.pathname}${signed.search}`;
    }

    async removeObject(objectKey: string): Promise<void> {
        if (!/^chat-(?:files|uploads)\/[1-9]\d*\/[1-9]\d*\/[\w-]+\.[\da-z]+$/iu.test(objectKey)) {
            throw new BadRequestException('삭제할 첨부파일 경로가 유효하지 않습니다');
        }

        await this.s3Client.send(new DeleteObjectCommand({ Bucket: this.config.bucket, Key: objectKey }));
    }

    extractObjectKey(fileUrl: string): string {
        const prefix = this.config.publicBaseUrl + '/';
        if (!fileUrl.startsWith(prefix)) {
            throw new BadRequestException('유효하지 않은 파일 URL입니다');
        }

        return fileUrl.slice(prefix.length);
    }

    /**
     * 첨부파일 URL이 해당 채널·사용자에게 할당된 경로인지 검증한다.
     * 타 채널·타 사용자 경로를 메시지 attachments에 삽입하는 것을 차단한다.
     * 실제 객체 존재 여부와 업로드 완료 이력은 이 검사로 증명되지 않는다.
     *
     * @param fileUrl - 검증할 첨부파일 URL
     * @param channelId - 메시지가 속한 채널 ID
     * @param userId - 업로더(메시지 작성자) ID
     * @throws BadRequestException URL이 `chat-files/{channelId}/{userId}/` prefix를 벗어난 경우
     */
    assertOwnedAttachmentUrl(fileUrl: string, channelId: number, userId: number): void {
        const objectKey = this.extractObjectKey(fileUrl);
        this.validateOwnedKey(objectKey, channelId, userId);
    }

    private validateOwnedKey(objectKey: string, channelId: number, userId: number): void {
        if (!objectKey.startsWith(`chat-files/${channelId}/${userId}/`)
            || !/^chat-files\/[1-9]\d*\/[1-9]\d*\/[\w-]+\.[\da-z]+$/iu.test(objectKey)) {
            throw new BadRequestException('첨부파일 경로가 유효하지 않습니다');
        }
    }

    private validateCredentials(): void {
        if (!this.config.accessKey || !this.config.secretKey) {
            throw new Error('오브젝트 스토리지 접근 키 설정이 필요합니다 (S3_ACCESS_KEY, S3_SECRET_KEY)');
        }
    }

    private validateContentType(contentType: string): void {
        if (!this.config.allowedContentTypes.includes(contentType)) {
            throw new BadRequestException(`허용되지 않은 파일 형식입니다. 허용 형식: ${this.config.allowedContentTypes.join(', ')}`);
        }
    }

    private checkUploadRateLimit(userId: number): void {
        const now = Date.now();
        const windowStart = now - this.config.uploadRateLimitWindowMs;
        const recentRequests = (this.uploadRateLimitBuckets.get(userId) ?? []).filter(
            requestedAt => requestedAt > windowStart,
        );

        if (recentRequests.length >= this.config.uploadRateLimitMaxRequests) {
            this.uploadRateLimitBuckets.set(userId, recentRequests);
            throw new HttpException(
                '짧은 시간에 업로드 요청이 너무 많습니다. 잠시 후 다시 시도하세요',
                HttpStatus.TOO_MANY_REQUESTS,
            );
        }

        recentRequests.push(now);
        this.uploadRateLimitBuckets.set(userId, recentRequests);
    }

    private cleanupStaleRateLimitEntries(): void {
        const windowStart = Date.now() - this.config.uploadRateLimitWindowMs;
        for (const [userId, timestamps] of this.uploadRateLimitBuckets) {
            if (timestamps.every(t => t <= windowStart)) {
                this.uploadRateLimitBuckets.delete(userId);
            }
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
        if (extensionFromName !== undefined && extensionFromName !== '' && /^[0-9a-z]+$/u.test(extensionFromName)) {
            return extensionFromName;
        }

        const extensionFromMime = mime.extension(contentType);
        return extensionFromMime === false || extensionFromMime === '' ? 'bin' : extensionFromMime;
    }
}
