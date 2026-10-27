import type {ConfigService} from '@nestjs/config';
import {UnreadCounterService} from './unread-counter.service';

describe('UnreadCounterService.incrementIfPresent', () => {
    const createService = () => {
        const script = jest.fn();
        const pipeline = {incrementIfPresentScript: script, exec: jest.fn().mockResolvedValue([])};
        const service = new UnreadCounterService({} as ConfigService);
        Object.assign(service, {client: {pipeline: () => pipeline}, logger: {warn: jest.fn()}});
        return {service, script};
    };

    it('event가 있으면 정렬한 수신자를 청크로 나눠 청크 번호와 함께 같은 적용 기록 비트맵에 반영한다', async () => {
        const {service, script} = createService();
        const userIds = Array.from({length: 150}, (_, i) => 150 - i);

        await service.incrementIfPresent(3, userIds, {id: 'message-1', createdAt: new Date()});

        expect(script).toHaveBeenCalledTimes(2);
        const calls = script.mock.calls as unknown[][];
        expect(calls.map(call => call.slice(0, 4))).toEqual([
            ['3', 'unread:applied-chunks:message-1', 86_400, 0],
            ['3', 'unread:applied-chunks:message-1', 86_400, 1],
        ]);
        expect(calls.flatMap(call => call.slice(4))).toEqual(userIds.toReversed().map(id => `unread:${id}`));
    });

    it('적용 기록 TTL보다 오래된 event는 기록이 사라져 중복을 막을 수 없으므로 증가하지 않는다', async () => {
        const {service, script} = createService();

        await service.incrementIfPresent(3, [7], {id: 'message-1', createdAt: new Date(Date.now() - 86_400_000)});

        expect(script).not.toHaveBeenCalled();
    });

    it('event가 없으면 적용 기록 없이 증가한다', async () => {
        const {service, script} = createService();

        await service.incrementIfPresent(3, [7]);

        expect(script).toHaveBeenCalledWith('3', '', 86_400, 0, 'unread:7');
    });
});
