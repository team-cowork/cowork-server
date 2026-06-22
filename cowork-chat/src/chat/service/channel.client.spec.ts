import { Test, TestingModule } from '@nestjs/testing';
import { ConfigService } from '@nestjs/config';
import { ChannelClient } from './channel.client';

describe('ChannelClient', () => {
    let client: ChannelClient;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            providers: [
                ChannelClient,
                {
                    provide: ConfigService,
                    useValue: { get: jest.fn().mockReturnValue('http://localhost:8083') },
                },
            ],
        }).compile();

        client = module.get(ChannelClient);
    });

    beforeEach(() => {
        global.fetch = jest.fn();
    });

    afterEach(() => {
        jest.restoreAllMocks();
    });

    it('채널 정보를 조회해 반환한다', async () => {
        (global.fetch as jest.Mock).mockResolvedValue({
            ok: true,
            json: jest.fn().mockResolvedValue({ id: 1, viewType: 'FILE_SHARE' }),
        });

        await expect(client.getChannel(1, 42)).resolves.toEqual({ id: 1, viewType: 'FILE_SHARE' });
        expect(global.fetch).toHaveBeenCalledWith(
            'http://localhost:8083/channels/1',
            expect.objectContaining({
                headers: { 'X-User-Id': '42' },
                // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment -- expect.any()의 반환 타입이 any로 선언되어 있음
                signal: expect.any(AbortSignal),
            }),
        );
    });

    it('비정상 응답이면 예외를 던진다', async () => {
        (global.fetch as jest.Mock).mockResolvedValue({
            ok: false,
            status: 403,
            text: jest.fn().mockResolvedValue('forbidden'),
        });

        await expect(client.getChannel(1, 42)).rejects.toThrow('channel-service 오류: 403 - forbidden');
    });
});
