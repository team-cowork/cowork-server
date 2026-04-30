import {
    BadRequestException,
    HttpException,
    HttpStatus,
    Inject,
    Injectable,
    PayloadTooLargeException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as Minio from 'minio';
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
export class MinioService {
    private readonly config: MinioConfig;
    private readonly uploadRateLimitBuckets = new Map<number, number[]>();

    constructor(
        @Inject(MINIO_CLIENT) private readonly minioClient: Minio.Client,
        configService: ConfigService,
    ) {
        this.config = buildMinioConfig(configService);
    }

    async createPresignedUpload(params: {
        channelId: number;
        userId: number;
        filename: string;
        contentType: string;
        size: number;
    }): Promise<PresignedUpload> {
        this.validateCredentials();
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

    async objectExists(objectKey: string): Promise<boolean> {
        try {
            await this.minioClient.statObject(this.config.bucket, objectKey);
            return true;
        } catch {
            return false;
        }
    }

    async removeObject(objectKey: string): Promise<void> {
        await this.minioClient.removeObject(this.config.bucket, objectKey);
    }

    private validateCredentials(): void {
        if (!this.config.accessKey || !this.config.secretKey) {
            throw new BadRequestException('MinIO 접근 키 설정이 필요합니다');
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
        const extensionFromName = filename.split('.').pop()?.toLowerCase();
        if (extensionFromName && /^[a-z0-9]+$/.test(extensionFromName)) {
            return extensionFromName;
        }

        const extensionByContentType: Record<string, string> = {
            'image/jpeg': 'jpg',
            'image/png': 'png',
            'image/webp': 'webp',
            'application/pdf': 'pdf',
            'text/plain': 'txt',
        };
        return extensionByContentType[contentType] ?? 'bin';
    }

    private buildPublicUrl(objectKey: string): string {
        return `${this.config.publicBaseUrl}/${objectKey}`;
    }
}
