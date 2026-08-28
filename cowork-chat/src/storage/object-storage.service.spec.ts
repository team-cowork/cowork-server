import { BadRequestException, HttpException, PayloadTooLargeException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { PutObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { ObjectStorageService } from './object-storage.service';

jest.mock('@aws-sdk/s3-request-presigner', () => ({
    getSignedUrl: jest.fn(),
}));

const mockS3Client = {
    send: jest.fn(),
};

const mockGetSignedUrl = jest.mocked(getSignedUrl);

const createConfigService = (overrides: Record<string, string> = {}) => ({
    get: jest.fn((key: string) => ({
        'object-storage.endpoint': 'http://localhost:9000',
        'object-storage.accessKey': 'admin',
        'object-storage.secretKey': 'password123',
        'object-storage.bucket': 'cowork-chat',
        ...overrides,
    })[key]),
}) as unknown as ConfigService;

describe('ObjectStorageService', () => {
    beforeEach(() => {
        jest.clearAllMocks();
        mockGetSignedUrl.mockResolvedValue('http://localhost:9000/upload-url');
    });

    it('허용된 파일 타입과 100MB 이하 파일이면 presigned URL을 발급한다', async () => {
        const service = new ObjectStorageService(mockS3Client as unknown as S3Client, createConfigService());

        const result = await service.createPresignedUpload({
            channelId: 1,
            userId: 42,
            filename: 'clip.mp4',
            contentType: 'video/mp4',
            size: 104857600,
        });

        expect(mockGetSignedUrl).toHaveBeenCalledTimes(1);
        const [client, command, options] = mockGetSignedUrl.mock.calls[0];
        const putObjectCommand = command as PutObjectCommand;
        expect(client).toBe(mockS3Client);
        expect(putObjectCommand.input.Bucket).toBe('cowork-chat');
        expect(putObjectCommand.input.Key).toMatch(/^chat-files\/1\/42\/.+\.mp4$/);
        expect(options).toEqual({ expiresIn: 600 });
        expect(result.fileUrl).toContain('/cowork-chat/chat-files/1/42/');
    });

    it('허용되지 않은 파일 타입이면 BadRequestException을 던진다', async () => {
        const service = new ObjectStorageService(mockS3Client as unknown as S3Client, createConfigService());

        await expect(service.createPresignedUpload({
            channelId: 1,
            userId: 42,
            filename: 'script.sh',
            contentType: 'application/x-sh',
            size: 1024,
        })).rejects.toThrow(BadRequestException);
    });

    it('100MB를 초과하면 PayloadTooLargeException을 던진다', async () => {
        const service = new ObjectStorageService(mockS3Client as unknown as S3Client, createConfigService());

        await expect(service.createPresignedUpload({
            channelId: 1,
            userId: 42,
            filename: 'clip.mp4',
            contentType: 'video/mp4',
            size: 104857601,
        })).rejects.toThrow(PayloadTooLargeException);
    });

    it('짧은 시간에 업로드 URL을 너무 많이 발급하면 TooManyRequestsException을 던진다', async () => {
        const service = new ObjectStorageService(mockS3Client as unknown as S3Client, createConfigService({
            'object-storage.chatUploadRateLimitMaxRequests': '2',
            'object-storage.chatUploadRateLimitWindowMs': '60000',
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
