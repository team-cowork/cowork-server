import type {ConfigService} from '@nestjs/config';
import {UnreadCounterService} from './unread-counter.service';

describe('UnreadCounterService.incrementIfPresent', () => {
    const createService = () => {
        const script = jest.fn();
        const pipeline = {incrementIfPresentScript: script, exec: jest.fn().mockResolvedValue([])};
        const service = new UnreadCounterService({} as ConfigService);
        Object.assign(service, {client: {pipeline: () => pipeline}});
        return {service, script};
    };

    it('eventId가 있으면 정렬한 수신자를 청크로 나눠 청크 번호와 함께 같은 적용 기록 비트맵에 반영한다', async () => {
        const {service, script} = createService();
        const userIds = Array.from({length: 150}, (_, i) => 150 - i);

        await service.incrementIfPresent(3, userIds, 'message-1');

        expect(script).toHaveBeenCalledTimes(2);
        const calls = script.mock.calls as unknown[][];
        expect(calls.map(call => call.slice(0, 4))).toEqual([
            ['3', 'unread:applied-chunks:message-1', 86_400, 0],
            ['3', 'unread:applied-chunks:message-1', 86_400, 1],
        ]);
        expect(calls.flatMap(call => call.slice(4))).toEqual(userIds.toReversed().map(id => `unread:${id}`));
    });

    it('eventId가 없으면 적용 기록 없이 증가한다', async () => {
        const {service, script} = createService();

        await service.incrementIfPresent(3, [7]);

        expect(script).toHaveBeenCalledWith('3', '', 86_400, 0, 'unread:7');
    });
});

describe('UnreadCounterService 정확한 값 기록', () => {
    const createService = (execResult: unknown = [[null, 1]]) => {
        const script = jest.fn();
        const pipeline = {setIfNotStaleScript: script, exec: jest.fn().mockResolvedValue(execResult)};
        const service = new UnreadCounterService({} as ConfigService);
        Object.assign(service, {client: {pipeline: () => pipeline}});
        return {service, script, pipeline};
    };

    it('읽음 처리 값은 기준 읽음 위치와 함께 같은 위치면 덮어쓰도록 기록한다', async () => {
        const {service, script} = createService();

        const applied = await service.set(3, 7, {count: 2, readCursor: 'aa'});

        expect(script).toHaveBeenCalledWith('unread:7', '3', '3:cursor', 'aa', '2', 86_400, '1');
        expect(applied).toBe(true);
    });

    it('더 최신 읽음 위치의 값이 있어 버려지면 false를 반환한다', async () => {
        const {service} = createService([[null, 0]]);

        await expect(service.set(3, 7, {count: 1, readCursor: 'aa'})).resolves.toBe(false);
    });

    it('Redis 오류면 기록 여부를 알 수 없으므로 null을 반환한다', async () => {
        const {service, pipeline} = createService();
        pipeline.exec.mockRejectedValue(new Error('down'));

        await expect(service.set(3, 7, {count: 1, readCursor: 'aa'})).resolves.toBeNull();
    });

    it('캐시미스 채움은 채널마다 기준 읽음 위치를 넘기고 같은 위치의 기존 값은 덮지 않는다', async () => {
        const {service, script} = createService();

        await service.setMany(7, new Map([
            [3, {count: 4, readCursor: null}],
            [5, {count: 0, readCursor: 'bb'}],
        ]));

        expect(script.mock.calls).toEqual([
            ['unread:7', '3', '3:cursor', '', '4', 86_400, '0'],
            ['unread:7', '5', '5:cursor', 'bb', '0', 86_400, '0'],
        ]);
    });
});
