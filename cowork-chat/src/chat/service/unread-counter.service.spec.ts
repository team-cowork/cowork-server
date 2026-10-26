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

    it('eventId가 있으면 모든 청크가 같은 적용 기록 집합을 공유해 (이벤트, 수신자)당 한 번만 반영한다', async () => {
        const {service, script} = createService();
        const userIds = Array.from({length: 150}, (_, i) => i + 1);

        await service.incrementIfPresent(3, userIds, 'message-1');

        expect(script).toHaveBeenCalledTimes(2);
        const calls = script.mock.calls as unknown[][];
        for (const [field, appliedKey, ttl] of calls) {
            expect([field, appliedKey, ttl]).toEqual(['3', 'unread:applied:message-1', 86_400]);
        }

        expect(calls.flatMap(call => call.slice(3))).toEqual(userIds.map(id => `unread:${id}`));
    });

    it('eventId가 없으면 적용 기록 없이 증가한다', async () => {
        const {service, script} = createService();

        await service.incrementIfPresent(3, [7]);

        expect(script).toHaveBeenCalledWith('3', '', 86_400, 'unread:7');
    });
});
