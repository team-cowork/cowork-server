import { mongo } from 'mongoose';
import { ProjectMemberProjectionRepository } from '../repository/project-member-projection.repository';
import { ProjectMemberEventConsumer } from './project-member-event.consumer';

type ConsumerWithHandle = {
    handleEvent(event: unknown, messageKey?: string): Promise<void>;
};

const SOURCE_VERSION = mongo.Long.fromString('1787702400000000000');

describe('ProjectMemberEventConsumer', () => {
    const repository = {
        add: jest.fn().mockResolvedValue(undefined),
        remove: jest.fn().mockResolvedValue(undefined),
    };
    const consumer = new ProjectMemberEventConsumer(
        { get: jest.fn() } as never,
        { sendCustom: jest.fn() } as never,
        repository as unknown as ProjectMemberProjectionRepository,
        {} as never,
    );
    const handle = (event: unknown, messageKey: string) =>
        (consumer as unknown as ConsumerWithHandle).handleEvent(event, messageKey);

    beforeEach(() => jest.clearAllMocks());

    it('ADDED 이벤트를 멤버십 projection에 idempotent upsert한다', async () => {
        await handle({
            eventType: 'ADDED',
            projectId: 5,
            userId: 42,
            occurredAt: '2026-08-26T00:00:00',
            snapshot: true,
        }, '5:42');

        expect(repository.add).toHaveBeenCalledWith(
            5,
            42,
            new Date('2026-08-26T00:00:00Z'),
            SOURCE_VERSION,
        );
        expect(repository.remove).not.toHaveBeenCalled();
    });

    it('REMOVED 이벤트를 멤버십 projection에서 삭제한다', async () => {
        await handle({ eventType: 'REMOVED', projectId: 5, userId: 42, occurredAt: '2026-08-26T00:00:00' }, '5:42');

        expect(repository.remove).toHaveBeenCalledWith(
            5,
            42,
            new Date('2026-08-26T00:00:00Z'),
            SOURCE_VERSION,
        );
        expect(repository.add).not.toHaveBeenCalled();
    });

    it('알 수 없는 eventType은 계약 오류로 격리 대상이 된다', async () => {
        await expect(
            handle({ eventType: 'UNKNOWN', projectId: 5, userId: 42, occurredAt: '2026-08-26T00:00:00' }, '5:42'),
        ).rejects.toThrow('invalid project member event payload');

        expect(repository.add).not.toHaveBeenCalled();
        expect(repository.remove).not.toHaveBeenCalled();
    });

    it('composite key가 아닌 이벤트는 계약 오류로 격리 대상이 된다', async () => {
        await expect((consumer as unknown as ConsumerWithHandle).handleEvent(
            { eventType: 'ADDED', projectId: 5, userId: 42, occurredAt: '2026-08-26T00:00:00' },
            '5',
        )).rejects.toThrow('project member event key mismatch');

        expect(repository.add).not.toHaveBeenCalled();
    });
});
