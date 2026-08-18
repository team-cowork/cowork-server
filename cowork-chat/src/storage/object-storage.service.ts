import {
    BadRequestException,
    ConflictException,
    HttpException,
    HttpStatus,
    Inject,
    Injectable,
    InternalServerErrorException,
    Logger,
    OnModuleDestroy,
    OnModuleInit,
    PayloadTooLargeException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import {
    DeleteObjectCommand,
    HeadObjectCommand,
    PutObjectCommand,
    S3Client,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import * as mime from 'mime-types';
import { randomUUID } from 'crypto';
import { S3_CLIENT } from './object-storage.constants';
import { buildObjectStorageConfig, ObjectStorageConfig } from './object-storage.config';

export interface PresignedUpload {
    objectKey: string;
    uploadUrl: string;
    fileUrl: string;
    expiresInSeconds: number;
}

function isNotFoundError(error: unknown): boolean {
    const err = error as { name?: string; $metadata?: { httpStatusCode?: number } };
    return err.name === 'NotFound' || err.$metadata?.httpStatusCode === 404;
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
        if (this.cleanupTimer) {
            clearInterval(this.cleanupTimer);
            this.cleanupTimer = undefined;
        }
    }

    async createPresignedUpload(params: {
        channelId: number;
        userId: number;
        filename: string;
        contentType: string;
        size: number;
    }): Promise<PresignedUpload> {
        this.checkUploadRateLimit(params.userId);
        this.validateContentType(params.contentType);
        this.validateFileSize(params.size);

        const objectKey = this.buildObjectKey(params.channelId, params.userId, params.filename, params.contentType);
        const uploadUrl = await getSignedUrl(
            this.s3Client,
            new PutObjectCommand({ Bucket: this.config.bucket, Key: objectKey }),
            { expiresIn: this.config.presignedPutExpirySeconds },
        );

        return {
            objectKey,
            uploadUrl,
            fileUrl: this.buildPublicUrl(objectKey),
            expiresInSeconds: this.config.presignedPutExpirySeconds,
        };
    }

    async confirmUpload(channelId: number, userId: number, objectKey: string): Promise<string> {
        const expectedPrefix = `chat-files/${channelId}/${userId}/`;
        if (!objectKey.startsWith(expectedPrefix)) {
            throw new BadRequestException('유효하지 않은 objectKey입니다');
        }

        let contentLength: number | undefined;
        try {
            const stat = await this.s3Client.send(new HeadObjectCommand({ Bucket: this.config.bucket, Key: objectKey }));
            contentLength = stat.ContentLength;
        } catch (error) {
            if (isNotFoundError(error)) {
                throw new ConflictException('S3에 파일이 없습니다. 업로드를 먼저 완료하세요');
            }
            this.logger.error(`S3 HeadObject failed [key=${objectKey}]`, error);
            throw new InternalServerErrorException('파일 확인 중 오류가 발생했습니다');
        }

        if ((contentLength ?? 0) > this.config.maxFileSizeBytes) {
            await this.s3Client.send(new DeleteObjectCommand({ Bucket: this.config.bucket, Key: objectKey }));
            throw new PayloadTooLargeException('파일 크기가 허용 한도를 초과했습니다');
        }

        return this.buildPublicUrl(objectKey);
    }

    async objectExists(objectKey: string): Promise<boolean> {
        try {
            await this.s3Client.send(new HeadObjectCommand({ Bucket: this.config.bucket, Key: objectKey }));
            return true;
        } catch (error) {
            if (isNotFoundError(error)) {
                return false;
            }
            this.logger.error(`S3 HeadObject failed [bucket=${this.config.bucket}, key=${objectKey}]`, error);
            throw new InternalServerErrorException('파일 존재 여부 확인 중 오류가 발생했습니다');
        }
    }

    async removeObject(objectKey: string): Promise<void> {
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
     * 첨부파일 URL이 해당 채널·사용자가 실제로 업로드한 오브젝트를 가리키는지 검증한다.
     * presigned/confirm 단계를 우회해 임의의 objectKey(타 채널·타 사용자 파일)를
     * 메시지 attachments에 삽입하는 것을 차단한다.
     *
     * @param fileUrl - 검증할 첨부파일 URL
     * @param channelId - 메시지가 속한 채널 ID
     * @param userId - 업로더(메시지 작성자) ID
     * @throws BadRequestException URL이 `chat-files/{channelId}/{userId}/` prefix를 벗어난 경우
     */
    assertOwnedAttachmentUrl(fileUrl: string, channelId: number, userId: number): void {
        const objectKey = this.extractObjectKey(fileUrl);
        const expectedPrefix = `chat-files/${channelId}/${userId}/`;
        if (!objectKey.startsWith(expectedPrefix)) {
            throw new BadRequestException('첨부파일 URL이 유효하지 않습니다');
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
            (requestedAt) => requestedAt > windowStart,
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
        if (extensionFromName && /^[a-z0-9]+$/.test(extensionFromName)) {
            return extensionFromName;
        }

        return mime.extension(contentType) || 'bin';
    }

    private buildPublicUrl(objectKey: string): string {
        return `${this.config.publicBaseUrl}/${objectKey}`;
    }
}
