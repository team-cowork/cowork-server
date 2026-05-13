import { Test, TestingModule } from '@nestjs/testing';
import { ConfigService } from '@nestjs/config';
import { UserClient } from './user.client';

describe('UserClient', () => {
    let client: UserClient;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            providers: [
                UserClient,
                {
                    provide: ConfigService,
                    useValue: { get: jest.fn().mockReturnValue('http://localhost:8082') },
                },
            ],
        }).compile();

        client = module.get(UserClient);
    });

    beforeEach(() => {
        global.fetch = jest.fn();
    });

    afterEach(() => {
        jest.restoreAllMocks();
    });

    it('nickname이 있으면 nickname을 반환한다', async () => {
        (global.fetch as jest.Mock).mockResolvedValue({
            ok: true,
            json: jest.fn().mockResolvedValue({ name: '홍길동', nickname: '길동이' }),
        });

        await expect(client.getDisplayName(42)).resolves.toBe('길동이');
    });

    it('nickname이 없으면 name을 반환한다', async () => {
        (global.fetch as jest.Mock).mockResolvedValue({
            ok: true,
            json: jest.fn().mockResolvedValue({ name: '홍길동', nickname: null }),
        });

        await expect(client.getDisplayName(42)).resolves.toBe('홍길동');
    });

    it('비정상 응답이면 예외를 던진다', async () => {
        (global.fetch as jest.Mock).mockResolvedValue({
            ok: false,
            status: 404,
            text: jest.fn().mockResolvedValue('not found'),
        });

        await expect(client.getDisplayName(42)).rejects.toThrow('user-service 오류: 404 - not found');
    });
});
