import { mongo } from 'mongoose';
import { TeamMemberProjectionRepository } from '../repository/team-member-projection.repository';
import { TeamMemberEventConsumer } from './team-member-event.consumer';

type ConsumerWithHandle = {
    handleEvent(event: unknown, messageKey?: string): Promise<void>;
};

const SOURCE_VERSION = mongo.Long.fromString('1787702400000000000');

describe('TeamMemberEventConsumer', () => {
    const repository = {
        upsert: jest.fn().mockResolvedValue(undefined),
        remove: jest.fn().mockResolvedValue(undefined),
    };
    const consumer = new TeamMemberEventConsumer(
        { get: jest.fn() } as never,
        { sendCustom: jest.fn() } as never,
        repository as unknown as TeamMemberProjectionRepository,
        {} as never,
    );
    const handle = (event: unknown, messageKey: string) =>
        (consumer as unknown as ConsumerWithHandle).handleEvent(event, messageKey);

    beforeEach(() => jest.clearAllMocks());

    it('UPSERT 이벤트를 팀 멤버 projection에 저장한다', async () => {
        await handle({
            eventType: 'UPSERT',
            teamId: 10,
            userId: 42,
            role: 'MEMBER',
            teamName: 'backend',
            occurredAt: '2026-08-26T00:00:00',
            snapshot: true,
        }, '10:42');

        expect(repository.upsert).toHaveBeenCalledWith({
            teamId: 10,
            userId: 42,
            role: 'MEMBER',
            teamName: 'backend',
            occurredAt: new Date('2026-08-26T00:00:00Z'),
            sourceVersion: SOURCE_VERSION,
        });
    });

    it('DELETE 이벤트를 팀 멤버 projection에서 삭제한다', async () => {
        await handle({
            eventType: 'DELETE',
            teamId: 10,
            userId: 42,
            role: 'MEMBER',
            teamName: 'backend',
            occurredAt: '2026-08-26T00:00:00',
        }, '10:42');

        expect(repository.remove).toHaveBeenCalledWith(
            10,
            42,
            new Date('2026-08-26T00:00:00Z'),
            SOURCE_VERSION,
        );
    });

    it('composite key가 아닌 이벤트는 계약 오류로 격리 대상이 된다', async () => {
        await expect(handle(
            {
                eventType: 'UPSERT',
                teamId: 10,
                userId: 42,
                role: 'MEMBER',
                teamName: 'backend',
                occurredAt: '2026-08-26T00:00:00',
            },
            '10',
        )).rejects.toThrow('team member event key mismatch');

        expect(repository.upsert).not.toHaveBeenCalled();
    });
});
