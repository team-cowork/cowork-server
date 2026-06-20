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

    describe('getDisplayNames', () => {
        it('빈 배열이면 fetch 없이 빈 Map을 반환한다', async () => {
            const result = await client.getDisplayNames([]);

            expect(result).toEqual(new Map());
            expect(global.fetch).not.toHaveBeenCalled();
        });

        it('여러 userId를 단일 요청으로 조회해 Map으로 반환한다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({
                ok: true,
                json: jest.fn().mockResolvedValue({
                    users: [
                        { id: 1, name: '홍길동', nickname: '길동이' },
                        { id: 2, name: '김철수', nickname: null },
                    ],
                }),
            });

            const result = await client.getDisplayNames([1, 2]);

            expect(global.fetch).toHaveBeenCalledWith(
                'http://localhost:8082/users/batch?ids=1,2',
                expect.anything(),
            );
            expect(result).toEqual(new Map([[1, '길동이'], [2, '김철수']]));
        });

        it('응답에 없는 userId는 Map에서 생략된다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({
                ok: true,
                json: jest.fn().mockResolvedValue({ users: [{ id: 1, name: '홍길동', nickname: null }] }),
            });

            const result = await client.getDisplayNames([1, 999]);

            expect(result).toEqual(new Map([[1, '홍길동']]));
        });

        it('응답 항목의 형식이 올바르지 않으면 해당 항목만 생략하고 나머지는 반환한다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({
                ok: true,
                json: jest.fn().mockResolvedValue({
                    users: [
                        { id: 1, name: '홍길동', nickname: null },
                        { id: 2, name: '', nickname: null },
                        { id: 'invalid', name: '김철수', nickname: null },
                    ],
                }),
            });

            const result = await client.getDisplayNames([1, 2, 3]);

            expect(result).toEqual(new Map([[1, '홍길동']]));
        });

        it('비정상 응답이면 예외를 던진다', async () => {
            (global.fetch as jest.Mock).mockResolvedValue({
                ok: false,
                status: 500,
                text: jest.fn().mockResolvedValue('boom'),
            });

            await expect(client.getDisplayNames([1, 2])).rejects.toThrow('user-service 오류: 500 - boom');
        });
    });
});
