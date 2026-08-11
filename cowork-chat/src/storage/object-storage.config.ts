import { ConfigService } from '@nestjs/config';
import { getOptionalConfig, getRequiredConfig } from '../common/config/config.util';

const DEFAULT_PRESIGNED_PUT_EXPIRY_SECONDS = 600;
const DEFAULT_MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024;
const DEFAULT_UPLOAD_RATE_LIMIT_WINDOW_MS = 60 * 1000;
const DEFAULT_UPLOAD_RATE_LIMIT_MAX_REQUESTS = 20;

export interface ObjectStorageConfig {
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

export function buildObjectStorageConfig(configService: ConfigService): ObjectStorageConfig {
    const internalEndpoint = getRequiredConfig(configService, [
        'object-storage.endpoint',
        'S3_ENDPOINT',
        'S3_INTERNAL_ENDPOINT',
    ]);
    const endpointUrl = new URL(internalEndpoint);
    const accessKey = getRequiredConfig(configService, ['object-storage.accessKey', 'S3_ACCESS_KEY']);
    const secretKey = getRequiredConfig(configService, ['object-storage.secretKey', 'S3_SECRET_KEY']);
    const bucket = getRequiredConfig(configService, ['object-storage.bucket', 'S3_BUCKET']);

    return {
        endPoint: endpointUrl.hostname,
        port: endpointUrl.port ? Number(endpointUrl.port) : undefined,
        useSSL: (getOptionalConfig(configService, ['object-storage.useSSL', 'S3_USE_SSL']) ?? String(endpointUrl.protocol === 'https:')) === 'true',
        accessKey,
        secretKey,
        bucket,
        publicBaseUrl: (
            getOptionalConfig(configService, ['object-storage.publicBaseUrl', 'S3_PUBLIC_BASE_URL'])
            ?? `${getOptionalConfig(configService, ['object-storage.publicEndpoint', 'S3_PUBLIC_ENDPOINT']) ?? internalEndpoint}/${bucket}`
        ).replace(/\/$/, ''),
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
            ['object-storage.chatUploadRateLimitWindowMs', 'S3_CHAT_UPLOAD_RATE_LIMIT_WINDOW_MS'],
        ) ?? DEFAULT_UPLOAD_RATE_LIMIT_WINDOW_MS),
        uploadRateLimitMaxRequests: Number(getOptionalConfig(
            configService,
            ['object-storage.chatUploadRateLimitMaxRequests', 'S3_CHAT_UPLOAD_RATE_LIMIT_MAX_REQUESTS'],
        ) ?? DEFAULT_UPLOAD_RATE_LIMIT_MAX_REQUESTS),
    };
}
