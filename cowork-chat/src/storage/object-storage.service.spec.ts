import { BadRequestException, HttpException, PayloadTooLargeException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { S3Client } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { ObjectStorageService } from './object-storage.service';

jest.mock('@aws-sdk/s3-request-presigner', () => ({
    getSignedUrl: jest.fn(),
}));

const s3Client = { send: jest.fn() };
const mockedGetSignedUrl = jest.mocked(getSignedUrl);

const configService = (overrides: Record<string, string> = {}): ConfigService => ({
    get: jest.fn((key: string) => ({
        'object-storage.endpoint': 'http://storage.internal:9000',
        'object-storage.publicBaseUrl': 'https://cdn.example.com/chat',
        'object-storage.accessKey': 'unit-test-access',
        'object-storage.secretKey': 'unit-test-secret',
        'object-storage.bucket': 'cowork-chat',
        ...overrides,
    })[key]),
}) as unknown as ConfigService;

describe('ObjectStorageService', () => {
    beforeEach(() => {
        jest.clearAllMocks();
        mockedGetSignedUrl.mockResolvedValue('https://storage.internal/upload');
    });

    describe('createPresignedUpload', () => {
        it('허용되지 않은 파일 형식은 거부한다', async () => {
            const service = new ObjectStorageService(s3Client as unknown as S3Client, configService());

            await expect(service.createPresignedUpload({
                channelId: 1,
                userId: 42,
                filename: 'payload.sh',
                contentType: 'application/x-sh',
                size: 1_024,
            })).rejects.toBeInstanceOf(BadRequestException);
            expect(mockedGetSignedUrl).not.toHaveBeenCalled();
        });

        it('허용 크기를 초과한 파일은 거부한다', async () => {
            const service = new ObjectStorageService(s3Client as unknown as S3Client, configService({
                'object-storage.chatMaxFileSizeBytes': '1024',
            }));

            await expect(service.createPresignedUpload({
                channelId: 1,
                userId: 42,
                filename: 'large.png',
                contentType: 'image/png',
                size: 1_025,
            })).rejects.toBeInstanceOf(PayloadTooLargeException);
            expect(mockedGetSignedUrl).not.toHaveBeenCalled();
        });

        it('같은 사용자가 요청 한도를 초과하면 429로 거부한다', async () => {
            const service = new ObjectStorageService(s3Client as unknown as S3Client, configService({
                'object-storage.chatUploadRateLimitMaxRequests': '2',
                'object-storage.chatUploadRateLimitWindowMs': '60000',
            }));
            const request = {
                channelId: 1,
                userId: 42,
                filename: 'image.png',
                contentType: 'image/png',
                size: 1_024,
            };
            await service.createPresignedUpload(request);
            await service.createPresignedUpload(request);

            try {
                await service.createPresignedUpload(request);
                throw new Error('expected upload request to be rate limited');
            } catch (error) {
                expect(error).toBeInstanceOf(HttpException);
                expect((error as HttpException).getStatus()).toBe(429);
            }
        });
    });

    describe('assertOwnedAttachmentUrl', () => {
        it('현재 채널에서 요청자가 업로드한 URL만 허용한다', () => {
            const service = new ObjectStorageService(s3Client as unknown as S3Client, configService());

            expect(() => service.assertOwnedAttachmentUrl(
                'https://cdn.example.com/chat/chat-files/1/42/file.png',
                1,
                42,
            )).not.toThrow();
        });

        it('다른 채널이나 다른 사용자의 URL은 거부한다', () => {
            const service = new ObjectStorageService(s3Client as unknown as S3Client, configService());

            expect(() => service.assertOwnedAttachmentUrl(
                'https://cdn.example.com/chat/chat-files/2/99/file.png',
                1,
                42,
            )).toThrow(BadRequestException);
        });

        it('관리 대상 외부 URL은 거부한다', () => {
            const service = new ObjectStorageService(s3Client as unknown as S3Client, configService());

            expect(() => service.assertOwnedAttachmentUrl(
                'https://attacker.example/chat-files/1/42/file.png',
                1,
                42,
            )).toThrow(BadRequestException);
        });
    });

    describe('confirmUpload', () => {
        it('다른 채널이나 사용자의 objectKey는 저장소 조회 전에 거부한다', async () => {
            const service = new ObjectStorageService(s3Client as unknown as S3Client, configService());

            await expect(service.confirmUpload(1, 42, 'chat-files/2/99/file.png'))
                .rejects.toBeInstanceOf(BadRequestException);
            expect(s3Client.send).not.toHaveBeenCalled();
        });

        it('실제 업로드 용량이 허용 한도를 넘으면 거부한다', async () => {
            const service = new ObjectStorageService(s3Client as unknown as S3Client, configService({
                'object-storage.chatMaxFileSizeBytes': '1024',
            }));
            s3Client.send
                .mockResolvedValueOnce({ ContentLength: 1_025 })
                .mockResolvedValueOnce({});

            await expect(service.confirmUpload(1, 42, 'chat-files/1/42/file.png'))
                .rejects.toBeInstanceOf(PayloadTooLargeException);
        });
    });
});
