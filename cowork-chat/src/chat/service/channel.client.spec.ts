import { Test, TestingModule } from '@nestjs/testing';
import { ConfigService } from '@nestjs/config';
import { ChannelClient } from './channel.client';
import { ChannelMetaCache } from './channel-meta.cache';

describe('ChannelClient', () => {
    let client: ChannelClient;
    let metaCache: { get: jest.Mock; set: jest.Mock };

    beforeEach(async () => {
        metaCache = { get: jest.fn().mockResolvedValue(null), set: jest.fn().mockResolvedValue(undefined) };

        const module: TestingModule = await Test.createTestingModule({
            providers: [
                ChannelClient,
                {
                    provide: ConfigService,
                    useValue: { get: jest.fn().mockReturnValue('http://localhost:8083') },
                },
                { provide: ChannelMetaCache, useValue: metaCache },
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

    it('채널 정보를 조회해 반환하고 캐시에 저장한다', async () => {
        (global.fetch as jest.Mock).mockResolvedValue({
            ok: true,
            json: jest.fn().mockResolvedValue({ id: 1, viewType: 'FILE_SHARE' }),
        });

        await expect(client.getChannel(1, 42)).resolves.toEqual({ id: 1, viewType: 'FILE_SHARE' });
        expect(global.fetch).toHaveBeenCalledWith(
            'http://localhost:8083/channels/1',
            expect.objectContaining({
                headers: { 'X-User-Id': '42' },
                signal: expect.any(AbortSignal) as unknown,
            }),
        );
        expect(metaCache.set).toHaveBeenCalledWith(1, 'FILE_SHARE');
    });

    it('비정상 응답이면 예외를 던진다', async () => {
        (global.fetch as jest.Mock).mockResolvedValue({
            ok: false,
            status: 403,
            text: jest.fn().mockResolvedValue('forbidden'),
        });

        await expect(client.getChannel(1, 42)).rejects.toThrow('channel-service 오류: 403 - forbidden');
    });

    it('캐시에 값이 있으면 channel-service를 호출하지 않고 캐시된 값을 반환한다', async () => {
        metaCache.get.mockResolvedValue('FILE_SHARE');

        await expect(client.getChannel(1, 42)).resolves.toEqual({ id: 1, viewType: 'FILE_SHARE' });
        expect(global.fetch).not.toHaveBeenCalled();
    });
});
