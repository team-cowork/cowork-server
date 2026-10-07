import { EurekaClient } from './eureka-client';

jest.mock('../common/config/control-plane-auth', () => ({
    controlPlaneAuthorization: () => 'Basic test',
}));

const config = {
    enabled: true,
    serverUrl: 'http://eureka/eureka',
    appName: 'cowork-chat',
    host: '10.0.0.1',
    port: 3000,
    instanceId: 'chat-1',
    leaseRenewalIntervalSeconds: 30,
};
const statusPath = 'http://eureka/eureka/apps/cowork-chat/chat-1/status';

const flush = async () => new Promise<void>(resolve => {
    setImmediate(resolve);
});

describe('EurekaClient status', () => {
    let fetchMock: jest.SpyInstance;
    let client: EurekaClient;

    const calls = () => fetchMock.mock.calls.map(([url, init]) => `${(init as RequestInit).method} ${String(url)}`);

    beforeEach(() => {
        fetchMock = jest.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(null, { status: 204 }));
        client = new EurekaClient(config);
    });

    afterEach(async () => {
        await client.deregister();
        jest.useRealTimers();
        fetchMock.mockRestore();
    });

    it('등록 뒤 준비 상태 변화를 status override 설정·해제로 반영한다', async () => {
        await client.register();
        fetchMock.mockClear();

        client.setStatus('OUT_OF_SERVICE');
        await flush();
        client.setStatus('UP');
        await flush();

        expect(calls()).toEqual([
            `PUT ${statusPath}?value=OUT_OF_SERVICE`,
            `DELETE ${statusPath}?value=UP`,
        ]);
    });

    it('등록 전 상태는 호출 없이 기록했다가 등록 status로 보낸다', async () => {
        client.setStatus('OUT_OF_SERVICE');
        await flush();
        expect(fetchMock).not.toHaveBeenCalled();

        await client.register();
        await flush();

        const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
        const body = JSON.parse(init.body as string) as { instance: { status: string } };
        expect(body.instance.status).toBe('OUT_OF_SERVICE');
        expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('호출 중 상태가 흔들리면 직렬화하고 마지막 상태만 반영한다', async () => {
        await client.register();
        let release!: () => void;
        fetchMock.mockClear().mockImplementationOnce(async () => new Promise<Response>(resolve => {
            release = () => {
                resolve(new Response(null, { status: 204 }));
            };
        }));

        client.setStatus('OUT_OF_SERVICE');
        client.setStatus('UP');
        client.setStatus('OUT_OF_SERVICE');
        client.setStatus('UP');
        expect(fetchMock).toHaveBeenCalledTimes(1);

        release();
        await flush();

        expect(calls()).toEqual([
            `PUT ${statusPath}?value=OUT_OF_SERVICE`,
            `DELETE ${statusPath}?value=UP`,
        ]);
    });

    it('status 변경 실패는 던지지 않고 다음 heartbeat에서 다시 맞춘다', async () => {
        jest.useFakeTimers({ doNotFake: ['setImmediate'] });
        await client.register();
        fetchMock.mockClear().mockResolvedValueOnce(new Response(null, { status: 503 }));

        client.setStatus('OUT_OF_SERVICE');
        await flush();
        await jest.advanceTimersByTimeAsync(30_000);

        expect(calls()).toEqual([
            `PUT ${statusPath}?value=OUT_OF_SERVICE`,
            'PUT http://eureka/eureka/apps/cowork-chat/chat-1',
            `PUT ${statusPath}?value=OUT_OF_SERVICE`,
        ]);
    });

    it('등록 해제 뒤 상태 변화는 Eureka를 호출하지 않는다', async () => {
        await client.register();
        await client.deregister();
        fetchMock.mockClear();

        client.setStatus('OUT_OF_SERVICE');
        await flush();

        expect(fetchMock).not.toHaveBeenCalled();
    });

    it('등록 해제 중 진행 중이던 heartbeat가 404를 받아도 다시 등록하지 않는다', async () => {
        jest.useFakeTimers({ doNotFake: ['setImmediate'] });
        await client.register();
        let release!: () => void;
        fetchMock.mockClear().mockImplementationOnce(async () => new Promise<Response>(resolve => {
            release = () => {
                resolve(new Response(null, { status: 404 }));
            };
        }));

        await jest.advanceTimersByTimeAsync(30_000);
        const deregistering = client.deregister();
        release();
        await deregistering;
        await flush();

        expect(calls()).toEqual([
            'PUT http://eureka/eureka/apps/cowork-chat/chat-1',
            'DELETE http://eureka/eureka/apps/cowork-chat/chat-1',
        ]);
    });
});
