import { mongo } from 'mongoose';
import { UserProfileProjectionRepository } from '../repository/user-profile-projection.repository';
import { UserProfileEventConsumer } from './user-profile-event.consumer';

type ConsumerWithHandle = {
    handleEvent(event: unknown, messageKey?: string): Promise<void>;
};

const SOURCE_VERSION = mongo.Long.fromString('1787702400000000000');

describe('UserProfileEventConsumer', () => {
    const repository = {
        upsert: jest.fn().mockResolvedValue(undefined),
        remove: jest.fn().mockResolvedValue(undefined),
    };
    const consumer = new UserProfileEventConsumer(
        { get: jest.fn() } as never,
        { sendCustom: jest.fn() } as never,
        repository as unknown as UserProfileProjectionRepository,
        {} as never,
    );
    const handle = (event: unknown, messageKey: string) =>
        (consumer as unknown as ConsumerWithHandle).handleEvent(event, messageKey);

    beforeEach(() => jest.clearAllMocks());

    it('UPSERT 이벤트의 프로필을 projection에 저장한다', async () => {
        const event = {
            eventType: 'UPSERT',
            userId: 42,
            name: '홍길동',
            nickname: '길동이',
            githubId: 'gildong',
            occurredAt: '2026-08-26T00:00:00Z',
        };

        await handle(event, '42');

        expect(repository.upsert).toHaveBeenCalledWith({
            userId: 42,
            name: '홍길동',
            nickname: '길동이',
            githubId: 'gildong',
            occurredAt: new Date('2026-08-26T00:00:00Z'),
            sourceVersion: SOURCE_VERSION,
        });
    });

    it('DELETE 이벤트의 사용자를 projection에서 삭제한다', async () => {
        await handle({
            eventType: 'DELETE',
            userId: 42,
            name: '홍길동',
            nickname: null,
            githubId: null,
            occurredAt: '2026-08-26T00:00:00Z',
        }, '42');

        expect(repository.remove).toHaveBeenCalledWith(
            42,
            new Date('2026-08-26T00:00:00Z'),
            SOURCE_VERSION,
        );
    });

    it('이름이 비어 있는 UPSERT 이벤트는 계약 오류로 격리 대상이 된다', async () => {
        await expect(handle({
            eventType: 'UPSERT',
            userId: 42,
            name: '',
            nickname: null,
            githubId: null,
            occurredAt: '2026-08-26T00:00:00Z',
        }, '42')).rejects.toThrow('invalid user profile event payload');

        expect(repository.upsert).not.toHaveBeenCalled();
    });

    it('userId와 다른 key의 이벤트는 계약 오류로 격리 대상이 된다', async () => {
        await expect((consumer as unknown as ConsumerWithHandle).handleEvent({
            eventType: 'UPSERT',
            userId: 42,
            name: '홍길동',
            nickname: null,
            githubId: null,
            occurredAt: '2026-08-26T00:00:00Z',
        }, '99')).rejects.toThrow('user profile event key mismatch');

        expect(repository.upsert).not.toHaveBeenCalled();
    });

    it('occurredAt이 없거나 잘못된 이벤트는 계약 오류로 격리 대상이 된다', async () => {
        await expect(handle({ eventType: 'DELETE', userId: 42 }, '42'))
            .rejects.toThrow('invalid user profile event payload');
        await expect(handle({ eventType: 'DELETE', userId: 42, occurredAt: 'invalid' }, '42'))
            .rejects.toThrow('invalid user profile event payload');

        expect(repository.remove).not.toHaveBeenCalled();
    });
});
