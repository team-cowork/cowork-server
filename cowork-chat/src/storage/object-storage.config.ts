import { type ConfigService } from '@nestjs/config';
import { getOptionalConfig, getRequiredConfig } from '../common/config/config.util';

const DEFAULT_PRESIGNED_PUT_EXPIRY_SECONDS = 600;
const DEFAULT_MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024;
const DEFAULT_UPLOAD_RATE_LIMIT_WINDOW_MS = 60 * 1000;
const DEFAULT_UPLOAD_RATE_LIMIT_MAX_REQUESTS = 20;

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
    const publicApiBaseUrl = getRequiredConfig(configService, 'PUBLIC_API_BASE_URL').replace(/\/$/u, '');
    const profile = getOptionalConfig(configService, ['APP_CONFIG_PROFILE', 'APP_PROFILE', 'SPRING_PROFILES_ACTIVE']) ?? 'local';
    const production = profile === 'prod';
    validateEndpoint(internalEndpoint, false);
    validateEndpoint(publicApiBaseUrl, production);
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

function validateEndpoint(endpoint: string, production: boolean): void {
    const url = new URL(endpoint);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
        || url.pathname !== '/' || url.search || url.hash
        || (production && (url.protocol !== 'https:' || ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)))) {
        throw new Error('Storage and API endpoints must be HTTP(S) origins; production API requires HTTPS and a non-local host');
    }
}
