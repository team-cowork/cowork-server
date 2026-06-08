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
import Redis from 'ioredis';
import { MINIO_CLIENT } from './minio.constants';
import { buildMinioConfig, MinioConfig } from './minio.config';
import { getOptionalConfig, getRequiredConfig } from '../common/config/config.util';

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
    private redisClient!: Redis;

    constructor(
        @Inject(MINIO_CLIENT) private readonly minioClient: Minio.Client,
        private readonly configService: ConfigService,
    ) {
        this.config = buildMinioConfig(configService);
        this.validateCredentials();
    }

    onModuleInit(): void {
        const host = getRequiredConfig(this.configService, ['REDIS_HOST', 'redis.host']);
        const port = Number(getOptionalConfig(this.configService, ['REDIS_PORT', 'redis.port']) ?? 6379);
        this.redisClient = new Redis({ host, port, lazyConnect: true });
        void this.redisClient.connect().catch((err: unknown) => {
            this.logger.warn(`Redis 초기 연결 실패: ${err instanceof Error ? err.message : String(err)}`);
        });
    }

    onModuleDestroy(): void {
        this.redisClient.disconnect();
    }

    async createPresignedUpload(params: {
        conversationId: string;
        userId: number;
        filename: string;
        contentType: string;
        size: number;
    }): Promise<PresignedUpload> {
        await this.checkUploadRateLimit(params.userId);
        this.validateContentType(params.contentType);
        this.validateFileSize(params.size);

        const objectKey = this.buildObjectKey(params.conversationId, params.userId, params.filename, params.contentType);
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

    async confirmUpload(conversationId: string, userId: number, objectKey: string): Promise<string> {
        const expectedPrefix = `dm-files/${conversationId}/${userId}/`;
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

    extractObjectKey(fileUrl: string): string {
        const prefix = this.config.publicBaseUrl + '/';
        if (!fileUrl.startsWith(prefix)) {
            throw new BadRequestException('유효하지 않은 파일 URL입니다');
        }
        return fileUrl.slice(prefix.length);
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

    private async checkUploadRateLimit(userId: number): Promise<void> {
        const key = `dm:upload-ratelimit:${userId}`;
        const now = Date.now();
        const windowStart = now - this.config.uploadRateLimitWindowMs;

        await this.redisClient.zremrangebyscore(key, '-inf', String(windowStart));
        const count = await this.redisClient.zcard(key);

        if (count >= this.config.uploadRateLimitMaxRequests) {
            throw new HttpException(
                '짧은 시간에 업로드 요청이 너무 많습니다. 잠시 후 다시 시도하세요',
                HttpStatus.TOO_MANY_REQUESTS,
            );
        }

        await this.redisClient.zadd(key, now, `${now}-${randomUUID()}`);
        await this.redisClient.pexpire(key, this.config.uploadRateLimitWindowMs);
    }

    private validateFileSize(size: number): void {
        if (size > this.config.maxFileSizeBytes) {
            throw new PayloadTooLargeException('파일 크기가 허용 한도를 초과했습니다');
        }
    }

    private buildObjectKey(conversationId: string, userId: number, filename: string, contentType: string): string {
        const extension = this.resolveExtension(filename, contentType);
        return `dm-files/${conversationId}/${userId}/${randomUUID()}.${extension}`;
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
