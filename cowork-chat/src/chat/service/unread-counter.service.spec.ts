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
