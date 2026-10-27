import {Types} from 'mongoose';
import {ChannelReadService} from './channel-read.service';

describe('ChannelReadService.advance', () => {
    const requestedId = new Types.ObjectId('000000000000000000000001');
    const adoptedId = new Types.ObjectId('000000000000000000000002');

    const createService = (applied: boolean | null) => {
        const readStateRepository = {updateLastRead: jest.fn().mockResolvedValue(adoptedId)};
        const messageRepository = {countUnread: jest.fn().mockResolvedValue(1)};
        const unreadCounterService = {set: jest.fn().mockResolvedValue(applied)};
        const service = new ChannelReadService(
            readStateRepository as never,
            messageRepository as never,
            unreadCounterService as never,
        );
        return {
            service, readStateRepository, messageRepository, unreadCounterService,
        };
    };

    it('요청값이 아니라 DB가 채택한 읽음 위치로 세고 그 위치와 함께 캐시에 기록한다', async () => {
        const {service, readStateRepository, messageRepository, unreadCounterService} = createService(true);

        const unreadCount = await service.advance(1, 42, requestedId);

        expect(readStateRepository.updateLastRead).toHaveBeenCalledWith(1, 42, requestedId);
        expect(messageRepository.countUnread).toHaveBeenCalledWith(1, adoptedId);
        expect(unreadCounterService.set).toHaveBeenCalledWith(1, 42, {count: 1, readCursor: adoptedId.toHexString()});
        expect(unreadCount).toBe(1);
    });

    it('더 최신 읽음 위치의 값이 이미 기록되어 거부되면 null을 반환한다', async () => {
        const {service} = createService(false);

        await expect(service.advance(1, 42, requestedId)).resolves.toBeNull();
    });

    it('Redis 오류로 기록 여부를 모르면 계산한 값을 반환한다', async () => {
        const {service} = createService(null);

        await expect(service.advance(1, 42, requestedId)).resolves.toBe(1);
    });
});
