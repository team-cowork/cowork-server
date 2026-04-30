import { BadRequestException, HttpException, PayloadTooLargeException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { MinioService } from './minio.service';

const mockMinioClient = {
    presignedPutObject: jest.fn(),
    statObject: jest.fn(),
    removeObject: jest.fn(),
};

const createConfigService = (overrides: Record<string, string> = {}) => ({
    get: jest.fn((key: string) => ({
        'minio.endpoint': 'http://localhost:9000',
        'minio.accessKey': 'admin',
        'minio.secretKey': 'password123',
        'minio.bucket': 'cowork-chat',
        ...overrides,
    })[key]),
}) as unknown as ConfigService;

describe('MinioService', () => {
    beforeEach(() => {
        jest.clearAllMocks();
        mockMinioClient.presignedPutObject.mockResolvedValue('http://localhost:9000/upload-url');
    });

    it('허용된 파일 타입과 100MB 이하 파일이면 presigned URL을 발급한다', async () => {
        const service = new MinioService(mockMinioClient as any, createConfigService());

        const result = await service.createPresignedUpload({
            channelId: 1,
            userId: 42,
            filename: 'clip.mp4',
            contentType: 'video/mp4',
            size: 104857600,
        });

        expect(mockMinioClient.presignedPutObject).toHaveBeenCalledWith(
            'cowork-chat',
            expect.stringMatching(/^chat-files\/1\/42\/.+\.mp4$/),
            600,
        );
        expect(result.fileUrl).toContain('/cowork-chat/chat-files/1/42/');
    });

    it('허용되지 않은 파일 타입이면 BadRequestException을 던진다', async () => {
        const service = new MinioService(mockMinioClient as any, createConfigService());

        await expect(service.createPresignedUpload({
            channelId: 1,
            userId: 42,
            filename: 'script.sh',
            contentType: 'application/x-sh',
            size: 1024,
        })).rejects.toThrow(BadRequestException);
    });

    it('100MB를 초과하면 PayloadTooLargeException을 던진다', async () => {
        const service = new MinioService(mockMinioClient as any, createConfigService());

        await expect(service.createPresignedUpload({
            channelId: 1,
            userId: 42,
            filename: 'clip.mp4',
            contentType: 'video/mp4',
            size: 104857601,
        })).rejects.toThrow(PayloadTooLargeException);
    });

    it('짧은 시간에 업로드 URL을 너무 많이 발급하면 TooManyRequestsException을 던진다', async () => {
        const service = new MinioService(mockMinioClient as any, createConfigService({
            'minio.chatUploadRateLimitMaxRequests': '2',
            'minio.chatUploadRateLimitWindowMs': '60000',
        }));

        const request = {
            channelId: 1,
            userId: 42,
            filename: 'clip.mp4',
            contentType: 'video/mp4',
            size: 1024,
        };

        await service.createPresignedUpload(request);
        await service.createPresignedUpload(request);
        await expect(service.createPresignedUpload(request)).rejects.toThrow(HttpException);
        await expect(service.createPresignedUpload(request)).rejects.toMatchObject({ status: 429 });
    });
});
