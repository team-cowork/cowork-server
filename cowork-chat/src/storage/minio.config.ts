import { ConfigService } from '@nestjs/config';
import { getOptionalConfig, getRequiredConfig } from '../common/config/config.util';

const DEFAULT_PRESIGNED_PUT_EXPIRY_SECONDS = 600;
const DEFAULT_MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024;
const DEFAULT_UPLOAD_RATE_LIMIT_WINDOW_MS = 60 * 1000;
const DEFAULT_UPLOAD_RATE_LIMIT_MAX_REQUESTS = 20;

export interface MinioConfig {
    endPoint: string;
    port?: number;
    useSSL: boolean;
    accessKey: string;
    secretKey: string;
    bucket: string;
    publicBaseUrl: string;
    presignedPutExpirySeconds: number;
    maxFileSizeBytes: number;
    allowedContentTypes: string[];
    uploadRateLimitWindowMs: number;
    uploadRateLimitMaxRequests: number;
}

export function buildMinioConfig(configService: ConfigService): MinioConfig {
    const internalEndpoint = getRequiredConfig(configService, [
        'minio.endpoint',
        'MINIO_ENDPOINT',
        'MINIO_INTERNAL_ENDPOINT',
    ]);
    const endpointUrl = new URL(internalEndpoint);
    const accessKey = getRequiredConfig(configService, ['minio.accessKey', 'MINIO_ACCESS_KEY']);
    const secretKey = getRequiredConfig(configService, ['minio.secretKey', 'MINIO_SECRET_KEY']);
    const bucket = getRequiredConfig(configService, ['minio.bucket', 'MINIO_BUCKET']);

    return {
        endPoint: endpointUrl.hostname,
        port: endpointUrl.port ? Number(endpointUrl.port) : undefined,
        useSSL: (getOptionalConfig(configService, ['minio.useSSL', 'MINIO_USE_SSL']) ?? String(endpointUrl.protocol === 'https:')) === 'true',
        accessKey,
        secretKey,
        bucket,
        publicBaseUrl: (
            getOptionalConfig(configService, ['minio.publicBaseUrl', 'MINIO_PUBLIC_BASE_URL'])
            ?? `${getOptionalConfig(configService, ['minio.publicEndpoint', 'MINIO_PUBLIC_ENDPOINT']) ?? internalEndpoint}/${bucket}`
        ).replace(/\/$/, ''),
        presignedPutExpirySeconds: Number(getOptionalConfig(
            configService,
            ['minio.presignedPutExpirySeconds', 'MINIO_PRESIGNED_PUT_EXPIRY_SECONDS'],
        ) ?? DEFAULT_PRESIGNED_PUT_EXPIRY_SECONDS),
        maxFileSizeBytes: Number(getOptionalConfig(
            configService,
            ['minio.chatMaxFileSizeBytes', 'MINIO_CHAT_MAX_FILE_SIZE_BYTES'],
        ) ?? DEFAULT_MAX_FILE_SIZE_BYTES),
        allowedContentTypes: (
            getOptionalConfig(configService, ['minio.chatAllowedContentTypes', 'MINIO_CHAT_ALLOWED_CONTENT_TYPES'])
            ?? [
                'video/mp4',
                'video/quicktime',
                'video/x-msvideo',
                'audio/mpeg',
                'audio/ogg',
                'audio/wav',
                'audio/mp4',
                'image/jpeg',
                'image/png',
                'image/gif',
                'image/webp',
                'application/pdf',
                'text/plain',
            ].join(',')
        )
            .split(',')
            .map((contentType) => contentType.trim())
            .filter(Boolean),
        uploadRateLimitWindowMs: Number(getOptionalConfig(
            configService,
            ['minio.chatUploadRateLimitWindowMs', 'MINIO_CHAT_UPLOAD_RATE_LIMIT_WINDOW_MS'],
        ) ?? DEFAULT_UPLOAD_RATE_LIMIT_WINDOW_MS),
        uploadRateLimitMaxRequests: Number(getOptionalConfig(
            configService,
            ['minio.chatUploadRateLimitMaxRequests', 'MINIO_CHAT_UPLOAD_RATE_LIMIT_MAX_REQUESTS'],
        ) ?? DEFAULT_UPLOAD_RATE_LIMIT_MAX_REQUESTS),
    };
}
