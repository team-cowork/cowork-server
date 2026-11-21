import {type ConfigService} from '@nestjs/config';
import {GetObjectCommand} from '@aws-sdk/client-s3';
import {getSignedUrl} from '@aws-sdk/s3-request-presigner';
import {createClient} from './object-storage.module.js';

// 환경 변수 이름은 배포 설정이 정한다.
const env = {
	endpoint: 'S3_INTERNAL_ENDPOINT',
	publicApiBaseUrl: 'PUBLIC_API_BASE_URL',
	accessKey: 'S3_ACCESS_KEY',
	secretKey: 'S3_SECRET_KEY',
	bucket: 'S3_BUCKET',
	maxFileSizeBytes: 'S3_CHAT_MAX_FILE_SIZE_BYTES',
	uploadRateLimitWindowMs: 'S3_CHAT_UPLOAD_RATE_LIMIT_WINDOW_MS',
	uploadRateLimitMaxRequests: 'S3_CHAT_UPLOAD_RATE_LIMIT_MAX_REQUESTS',
} as const;
// AWS S3 API가 정한 PascalCase 입력 필드 이름이다.
const s3Field = {bucket: 'Bucket', key: 'Key'} as const;

const configService = {
	get: jest.fn((key: string) => ({
		[env.endpoint]: 'https://storage.internal:9000',
		[env.publicApiBaseUrl]: 'https://api.example.com',
		[env.accessKey]: 'unit-test-access',
		[env.secretKey]: 'unit-test-secret',
		[env.bucket]: 'cowork-chat',
	})[key]),
} as unknown as ConfigService;

describe('createClient', () => {
	it('서명 클라이언트는 다운로드 URL에 응답 checksum 모드를 넣지 않는다', async () => {
		const signedUrl = await getSignedUrl(
			createClient(configService, true),
			new GetObjectCommand({[s3Field.bucket]: 'cowork-chat', [s3Field.key]: 'chat-files/1/42/123e4567-e89b-12d3-a456-426614174000.png'}),
			{expiresIn: 60},
		);

		expect(new URL(signedUrl).searchParams.has('x-amz-checksum-mode')).toBe(false);
	});

	it('내부 클라이언트는 서버 측 읽기의 응답 checksum 검증을 유지한다', async () => {
		const client = createClient(configService);

		await expect(client.config.responseChecksumValidation()).resolves.toBe('WHEN_SUPPORTED');
	});
});
