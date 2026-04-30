import { ConfigService } from '@nestjs/config';

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

function getConfigValue(configService: ConfigService, ...keys: string[]): string | undefined {
    for (const key of keys) {
        const value = configService.get<string>(key) ?? process.env[key];
        if (value !== undefined && value !== '') {
            return value;
        }
    }
    return undefined;
}

export function buildMinioConfig(configService: ConfigService): MinioConfig {
    const internalEndpoint = getConfigValue(
        configService,
        'minio.endpoint',
        'MINIO_ENDPOINT',
        'MINIO_INTERNAL_ENDPOINT',
    ) ?? 'http://localhost:9000';
    const endpointUrl = new URL(internalEndpoint);
    const accessKey = getConfigValue(configService, 'minio.accessKey', 'MINIO_ACCESS_KEY') ?? '';
    const secretKey = getConfigValue(configService, 'minio.secretKey', 'MINIO_SECRET_KEY') ?? '';
    const bucket = getConfigValue(configService, 'minio.bucket', 'MINIO_BUCKET') ?? 'cowork-bucket';

    return {
        endPoint: endpointUrl.hostname,
        port: endpointUrl.port ? Number(endpointUrl.port) : undefined,
        useSSL: (getConfigValue(configService, 'minio.useSSL', 'MINIO_USE_SSL') ?? String(endpointUrl.protocol === 'https:')) === 'true',
        accessKey,
        secretKey,
        bucket,
        publicBaseUrl: (
            getConfigValue(configService, 'minio.publicBaseUrl', 'MINIO_PUBLIC_BASE_URL')
            ?? `${getConfigValue(configService, 'minio.publicEndpoint', 'MINIO_PUBLIC_ENDPOINT') ?? internalEndpoint}/${bucket}`
        ).replace(/\/$/, ''),
        presignedPutExpirySeconds: Number(getConfigValue(
            configService,
            'minio.presignedPutExpirySeconds',
            'MINIO_PRESIGNED_PUT_EXPIRY_SECONDS',
        ) ?? 600),
        maxFileSizeBytes: Number(getConfigValue(
            configService,
            'minio.chatMaxFileSizeBytes',
            'MINIO_CHAT_MAX_FILE_SIZE_BYTES',
        ) ?? 104857600),
        allowedContentTypes: (
            getConfigValue(configService, 'minio.chatAllowedContentTypes', 'MINIO_CHAT_ALLOWED_CONTENT_TYPES')
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
        uploadRateLimitWindowMs: Number(getConfigValue(
            configService,
            'minio.chatUploadRateLimitWindowMs',
            'MINIO_CHAT_UPLOAD_RATE_LIMIT_WINDOW_MS',
        ) ?? 60000),
        uploadRateLimitMaxRequests: Number(getConfigValue(
            configService,
            'minio.chatUploadRateLimitMaxRequests',
            'MINIO_CHAT_UPLOAD_RATE_LIMIT_MAX_REQUESTS',
        ) ?? 20),
    };
}
