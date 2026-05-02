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
import * as Minio from 'minio';
import * as mime from 'mime-types';
import { randomUUID } from 'crypto';
import { MINIO_CLIENT } from './minio.constants';
import { buildMinioConfig, MinioConfig } from './minio.config';

export interface PresignedUpload {
    objectKey: string;
    uploadUrl: string;
    fileUrl: string;
    expiresInSeconds: number;
}

@Injectable()
export class MinioService implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(MinioService.name);
    private readonly config: MinioConfig;
    private readonly uploadRateLimitBuckets = new Map<number, number[]>();
    private cleanupTimer?: ReturnType<typeof setInterval>;
    private isCleaningUpRateLimitEntries = false;

    constructor(
        @Inject(MINIO_CLIENT) private readonly minioClient: Minio.Client,
        configService: ConfigService,
    ) {
        this.config = buildMinioConfig(configService);
        this.validateCredentials();
    }

    onModuleInit(): void {
        this.cleanupTimer = setInterval(async () => {
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
        const uploadUrl = await this.minioClient.presignedPutObject(
            this.config.bucket,
            objectKey,
            this.config.presignedPutExpirySeconds,
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

        let stat: Awaited<ReturnType<Minio.Client['statObject']>>;
        try {
            stat = await this.minioClient.statObject(this.config.bucket, objectKey);
        } catch (error) {
            const code = (error as { code?: string }).code;
            if (code === 'NoSuchKey' || code === 'NotFound') {
                throw new ConflictException('S3에 파일이 없습니다. 업로드를 먼저 완료하세요');
            }
            this.logger.error(`MinIO statObject 실패 [key=${objectKey}]`, error);
            throw new InternalServerErrorException('파일 확인 중 오류가 발생했습니다');
        }

        if (stat.size > this.config.maxFileSizeBytes) {
            await this.minioClient.removeObject(this.config.bucket, objectKey);
            throw new PayloadTooLargeException('파일 크기가 허용 한도를 초과했습니다');
        }

        return this.buildPublicUrl(objectKey);
    }

    async objectExists(objectKey: string): Promise<boolean> {
        try {
            await this.minioClient.statObject(this.config.bucket, objectKey);
            return true;
        } catch (error) {
            const code = (error as { code?: string }).code;
            if (code === 'NoSuchKey' || code === 'NotFound') {
                return false;
            }
            this.logger.error(`MinIO statObject 실패 [bucket=${this.config.bucket}, key=${objectKey}]`, error);
            throw new InternalServerErrorException('파일 존재 여부 확인 중 오류가 발생했습니다');
        }
    }

    async removeObject(objectKey: string): Promise<void> {
        await this.minioClient.removeObject(this.config.bucket, objectKey);
    }

    private validateCredentials(): void {
        if (!this.config.accessKey || !this.config.secretKey) {
            throw new Error('MinIO 접근 키 설정이 필요합니다 (MINIO_ACCESS_KEY, MINIO_SECRET_KEY)');
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
