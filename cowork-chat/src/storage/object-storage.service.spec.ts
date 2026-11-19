import {BadRequestException, HttpException, PayloadTooLargeException} from '@nestjs/common';
import {type ConfigService} from '@nestjs/config';
import {type S3Client} from '@aws-sdk/client-s3';
import {getSignedUrl} from '@aws-sdk/s3-request-presigner';
import {ObjectStorageService} from './object-storage.service.js';

jest.mock('@aws-sdk/s3-request-presigner', () => ({
	getSignedUrl: jest.fn(),
}));

const uploads = {
	create: jest.fn(), find: jest.fn(), confirm: jest.fn(), claim: jest.fn(),
};
const s3Client = {send: jest.fn()};
const rateLimiter = {tryAcquire: jest.fn()};
// RedisRateLimiter는 속성 주입이므로 테스트에서 직접 넣는다.
const withRateLimiter = (service: ObjectStorageService) => Object.assign<ObjectStorageService, Record<string, unknown>>(service, {rateLimiter});
const mockedGetSignedUrl = jest.mocked(getSignedUrl);

// 환경 변수 이름은 배포 설정이 정한다.
const publicApiBaseUrlKey = 'PUBLIC_API_BASE_URL';

const configService = (overrides: Record<string, string> = {}): ConfigService => ({
	get: jest.fn((key: string) => ({
		'object-storage.endpoint': 'https://storage.internal:9000',
		[publicApiBaseUrlKey]: 'https://api.example.com',
		'object-storage.accessKey': 'unit-test-access',
		'object-storage.secretKey': 'unit-test-secret',
		'object-storage.bucket': 'cowork-chat',
		...overrides,
	})[key]),
}) as unknown as ConfigService;

describe('ObjectStorageService', () => {
	beforeEach(() => {
		jest.resetAllMocks();
		uploads.find.mockResolvedValue({
			uploadKey: 'chat-uploads/1/42/file.png', channelId: 1, userId: 42,
			name: 'file.png', mimeType: 'image/png', declaredSize: 100,
		});
		mockedGetSignedUrl.mockResolvedValue('https://storage.internal/upload');
		rateLimiter.tryAcquire.mockResolvedValue(true);
	});

	describe('createPresignedUpload', () => {
		const newService = () => withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));
		const signedContentType = () => (mockedGetSignedUrl.mock.calls[0][1] as {input: {ContentType: string}}).input.ContentType;

		it.each([
			['report.hwp', 'application/x-hwp'],
			['report.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
			['archive.zip', 'application/x-zip-compressed'],
			['setup.exe', 'application/x-msdownload'],
			['unknown.bin', 'application/octet-stream'],
			['data.foo', 'application/x-foo!bar#v1$&^'],
		])('%s(%s)을 형식 제한 없이 그 형식으로 서명한다', async (filename, contentType) => {
			const result = await newService().createPresignedUpload({
				channelId: 1, userId: 42, filename, contentType, size: 1024,
			});

			expect(signedContentType()).toBe(contentType);
			expect(result.headers).toEqual({'Content-Type': contentType});
		});

		it.each(['', ' '.repeat(3)])('빈 MIME 값 %j은 application/octet-stream으로 받는다', async contentType => {
			const result = await newService().createPresignedUpload({
				channelId: 1, userId: 42, filename: 'report.hwp', contentType, size: 1024,
			});

			expect(signedContentType()).toBe('application/octet-stream');
			expect(result.headers).toEqual({'Content-Type': 'application/octet-stream'});
			expect(uploads.create).toHaveBeenCalledWith(expect.objectContaining({mimeType: 'application/octet-stream'}));
		});

		it.each(['text/html', 'Text/HTML', 'application/xhtml+xml', 'image/svg+xml', 'application/xml', 'text/javascript'])('능동 콘텐츠 %s는 업로드를 받되 application/octet-stream으로 저장한다', async contentType => {
			const result = await newService().createPresignedUpload({
				channelId: 1, userId: 42, filename: 'page.html', contentType, size: 1024,
			});

			expect(signedContentType()).toBe('application/octet-stream');
			expect(result.headers).toEqual({'Content-Type': 'application/octet-stream'});
			expect(result.objectKey).toMatch(/\.html$/v);
		});

		it.each(['png', 'image/', '../..', '+/.', '_/-', 'image/png; charset=utf-8', 'text/html\r\nX-Injected: 1'])('MIME 형식이 아닌 값 %j은 업로드 한도를 쓰지 않고 거부한다', async contentType => {
			await expect(newService().createPresignedUpload({
				channelId: 1, userId: 42, filename: 'file.png', contentType, size: 1024,
			})).rejects.toBeInstanceOf(BadRequestException);
			expect(rateLimiter.tryAcquire).not.toHaveBeenCalled();
			expect(mockedGetSignedUrl).not.toHaveBeenCalled();
		});

		it('허용 크기를 초과한 파일은 거부한다', async () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService({
				'object-storage.chatMaxFileSizeBytes': '1024',
			}), s3Client as unknown as S3Client, uploads as never));

			await expect(service.createPresignedUpload({
				channelId: 1,
				userId: 42,
				filename: 'large.png',
				contentType: 'image/png',
				size: 1025,
			})).rejects.toBeInstanceOf(PayloadTooLargeException);
			expect(mockedGetSignedUrl).not.toHaveBeenCalled();
		});

		it('Redis 한도 검사가 거부하면 429로 거부한다', async () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService({
				'object-storage.chatUploadRateLimitMaxRequests': '2',
				'object-storage.chatUploadRateLimitWindowMs': '60000',
			}), s3Client as unknown as S3Client, uploads as never));
			rateLimiter.tryAcquire.mockResolvedValueOnce(false);

			const error = await service.createPresignedUpload({
				channelId: 1,
				userId: 42,
				filename: 'image.png',
				contentType: 'image/png',
				size: 1024,
			}).catch((error_: unknown) => error_);
			expect(error).toBeInstanceOf(HttpException);
			expect((error as HttpException).getStatus()).toBe(429);
			expect(rateLimiter.tryAcquire).toHaveBeenCalledWith('object-storage:upload:42', 60_000, 2);
			expect(mockedGetSignedUrl).not.toHaveBeenCalled();
		});
	});

	describe('assertOwnedAttachmentUrl', () => {
		it('현재 채널에서 요청자가 업로드한 URL만 허용한다', () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));

			expect(() => {
				service.assertOwnedAttachmentUrl(
					'https://api.example.com/api/storage/cowork-chat/chat-files/1/42/file.png',
					1,
					42,
				);
			}).not.toThrow();
		});

		it('다른 채널이나 다른 사용자의 URL은 거부한다', () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));

			expect(() => {
				service.assertOwnedAttachmentUrl(
					'https://api.example.com/api/storage/cowork-chat/chat-files/2/99/file.png',
					1,
					42,
				);
			}).toThrow(BadRequestException);
		});

		it('관리 대상 외부 URL은 거부한다', () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));

			expect(() => {
				service.assertOwnedAttachmentUrl(
					'https://attacker.example/chat-files/1/42/file.png',
					1,
					42,
				);
			}).toThrow(BadRequestException);
		});
	});

	describe('첨부 경로 검증', () => {
		it.each([
			'chat-files/1/42/../secret.png',
			'chat-files/1/42/%2e%2e/secret.png',
			'chat-files/1/42/file.png?download=1',
			'chat-files/1/42/file.png#fragment',
		])('소유자 prefix 안에서도 잘못된 key %s를 거부한다', objectKey => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));
			expect(() => {
				service.assertOwnedAttachmentUrl(`https://api.example.com/api/storage/cowork-chat/${objectKey}`, 1, 42);
			})
				.toThrow(BadRequestException);
		});
	});

	describe('removeObject', () => {
		it('이미 없는 객체는 삭제 성공으로 본다', async () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));
			s3Client.send.mockRejectedValueOnce({name: 'NoSuchKey', $metadata: {httpStatusCode: 404}});

			await expect(service.removeObject('chat-files/1/42/file.png')).resolves.toBeUndefined();
		});

		it('그 밖의 저장소 오류는 재시도할 수 있게 그대로 던진다', async () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));
			s3Client.send.mockRejectedValueOnce(Object.assign(new Error('unavailable'), {$metadata: {httpStatusCode: 503}}));

			await expect(service.removeObject('chat-files/1/42/file.png')).rejects.toThrow('unavailable');
		});

		it('버킷이 없는 404는 객체 삭제 성공으로 보지 않는다', async () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));
			s3Client.send.mockRejectedValueOnce({name: 'NoSuchBucket', $metadata: {httpStatusCode: 404}});

			await expect(service.removeObject('chat-files/1/42/file.png')).rejects.toMatchObject({name: 'NoSuchBucket'});
		});
	});

	describe('createPresignedDownload', () => {
		it('원래 파일명으로 내려받도록 Content-Disposition을 서명에 포함한다', async () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));
			await service.createPresignedDownload('chat-files/1/42/123e4567-e89b-12d3-a456-426614174000.pdf', 1, 42, '회의록 "최종".pdf');

			const command = mockedGetSignedUrl.mock.calls[0][1] as {input: {ResponseContentDisposition: string}};
			expect(command.input.ResponseContentDisposition).toBe(
				'attachment; filename="___ ____.pdf"; filename*=UTF-8\'\'%ED%9A%8C%EC%9D%98%EB%A1%9D%20%22%EC%B5%9C%EC%A2%85%22.pdf',
			);
		});
	});

	describe('confirmUpload', () => {
		it('다른 채널이나 사용자의 objectKey는 저장소 조회 전에 거부한다', async () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));

			await expect(service.confirmUpload(1, 42, 'chat-files/2/99/file.png'))
				.rejects.toBeInstanceOf(BadRequestException);
			expect(s3Client.send).not.toHaveBeenCalled();
		});

		it('실제 크기 메타데이터가 없으면 완료를 인정하지 않는다', async () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));
			s3Client.send.mockResolvedValueOnce(Object.fromEntries([['ContentType', 'image/png']]));
			await expect(service.confirmUpload(1, 42, 'chat-uploads/1/42/file.png'))
				.rejects.toBeInstanceOf(BadRequestException);
		});

		it('실제 업로드 용량이 허용 한도를 넘으면 거부한다', async () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService({
				'object-storage.chatMaxFileSizeBytes': '1024',
			}), s3Client as unknown as S3Client, uploads as never));
			s3Client.send
				.mockResolvedValueOnce(Object.fromEntries([['ContentLength', 1025]]))
				.mockResolvedValueOnce({});

			await expect(service.confirmUpload(1, 42, 'chat-uploads/1/42/file.png'))
				.rejects.toBeInstanceOf(PayloadTooLargeException);
		});
	});
	describe('업로드 확정 정책', () => {
		it('발급 때 신고한 크기와 실제 크기가 다르면 거부한다', async () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));
			s3Client.send.mockResolvedValue(Object.fromEntries([['ContentLength', 99], ['ContentType', 'image/png'], ['ETag', 'etag']]));
			await expect(service.confirmUpload(1, 42, 'chat-uploads/1/42/file.png')).rejects.toBeInstanceOf(BadRequestException);
			expect(uploads.confirm).not.toHaveBeenCalled();
		});
		it('발급된 임시 key와 다른 최종 파일만 메시지에 연결할 수 있게 반환한다', async () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));
			s3Client.send.mockResolvedValue(Object.fromEntries([['ContentLength', 100], ['ContentType', 'image/png'], ['ETag', 'etag']]));
			uploads.confirm.mockImplementation(async (_key: string, attachment: Record<string, unknown>) => attachment);
			const result = await service.confirmUpload(1, 42, 'chat-uploads/1/42/file.png');
			expect(result).toMatchObject({name: 'file.png', size: 100, mimeType: 'image/png'});
			expect(result.objectKey).toMatch(/^chat-files\/1\/42\/[\w\-]+\.png$/v);
			expect(result.objectKey).not.toBe('chat-uploads/1/42/file.png');
		});
		it('application/octet-stream으로 발급한 파일도 확정한다', async () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));
			uploads.find.mockResolvedValue({
				uploadKey: 'chat-uploads/1/42/report.hwp', channelId: 1, userId: 42,
				name: 'report.hwp', mimeType: 'application/octet-stream', declaredSize: 100,
			});
			s3Client.send.mockResolvedValue(Object.fromEntries([['ContentLength', 100], ['ContentType', 'application/octet-stream'], ['ETag', 'etag']]));
			uploads.confirm.mockImplementation(async (_key: string, attachment: Record<string, unknown>) => attachment);
			const result = await service.confirmUpload(1, 42, 'chat-uploads/1/42/report.hwp');
			expect(result).toMatchObject({name: 'report.hwp', mimeType: 'application/octet-stream'});
			expect(result.objectKey).toMatch(/^chat-files\/1\/42\/[\w\-]+\.hwp$/v);
		});
		it('저장소에 형식 정보가 없으면 발급 정보와 다르다고 거부한다', async () => {
			const service = withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));
			s3Client.send.mockResolvedValue(Object.fromEntries([['ContentLength', 100], ['ETag', 'etag']]));
			await expect(service.confirmUpload(1, 42, 'chat-uploads/1/42/file.png')).rejects.toThrow('발급한 크기·형식과 업로드한 파일 정보가 다릅니다');
			expect(uploads.confirm).not.toHaveBeenCalled();
		});
	});
	describe('확정 파일의 메시지 귀속', () => {
		const service = () => withRateLimiter(new ObjectStorageService(s3Client as unknown as S3Client, configService(), s3Client as unknown as S3Client, uploads as never));
		it('완료 확인을 거치지 않거나 다른 메시지에 귀속된 파일은 거부한다', async () => {
			uploads.claim.mockResolvedValue(null);
			await expect(service().claimAttachments([{objectKey: 'chat-files/1/42/file.png'}], 1, 42, 'message-a'))
				.rejects.toBeInstanceOf(HttpException);
		});
		it('파일명과 크기·형식은 확인한 서버 기록을 사용한다', async () => {
			uploads.claim.mockResolvedValue({
				objectKey: 'chat-files/1/42/file.png', name: 'verified.png', size: 100, mimeType: 'image/png',
			});
			await expect(service().claimAttachments([{objectKey: 'chat-files/1/42/file.png'}], 1, 42, 'message-a'))
				.resolves.toEqual([{
					objectKey: 'chat-files/1/42/file.png', name: 'verified.png', size: 100, mimeType: 'image/png', spoiler: false,
				}]);
		});
		it('전송 요청에서 지정한 첨부별 스포일러 표시를 그대로 싣는다', async () => {
			uploads.claim.mockImplementation(async (objectKey: string) => ({
				objectKey, name: 'verified.png', size: 100, mimeType: 'image/png',
			}));
			const attachments = await service().claimAttachments(
				[{objectKey: 'chat-files/1/42/a.png', spoiler: true}, {objectKey: 'chat-files/1/42/b.png', spoiler: false}],
				1,
				42,
				'message-a',
			);
			expect(attachments.map(attachment => attachment.spoiler)).toEqual([true, false]);
		});
		it('다른 사용자의 파일은 이력 조회 전 거부한다', async () => {
			await expect(service().claimAttachments([{objectKey: 'chat-files/1/99/file.png'}], 1, 42, 'message-a'))
				.rejects.toBeInstanceOf(BadRequestException);
			expect(uploads.claim).not.toHaveBeenCalled();
		});
		it('확정한 파일은 재확인 시 임시 파일로 덮어쓰지 않는다', async () => {
			uploads.find.mockResolvedValue({
				channelId: 1, userId: 42, objectKey: 'chat-files/1/42/final.png', name: 'verified.png', size: 100, mimeType: 'image/png',
			});
			const result = await service().confirmUpload(1, 42, 'chat-uploads/1/42/file.png');
			expect(result.objectKey).toBe('chat-files/1/42/final.png');
			expect(s3Client.send).not.toHaveBeenCalled();
		});
		it('검증 후 파일이 바뀌면 확정을 거부한다', async () => {
			s3Client.send.mockResolvedValueOnce(Object.fromEntries([['ContentLength', 100], ['ContentType', 'image/png'], ['ETag', 'old']]))
				.mockRejectedValueOnce({$metadata: {httpStatusCode: 412}});
			await expect(service().confirmUpload(1, 42, 'chat-uploads/1/42/file.png')).rejects.toBeInstanceOf(HttpException);
			expect(uploads.confirm).not.toHaveBeenCalled();
		});
	});
});
