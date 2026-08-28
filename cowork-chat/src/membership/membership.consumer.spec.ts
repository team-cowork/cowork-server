import { MembershipConsumer } from './membership.consumer';

type ConsumerWithHandle = {
    handleEvent(event: unknown, messageKey?: string): Promise<void>;
};

interface PipelineSet {
    $set: Record<string, unknown>;
}

describe('MembershipConsumer projection ordering', () => {
    const memberModel = {
        updateOne: jest.fn<
            Promise<{ modifiedCount: number; upsertedCount: number }>,
            [unknown, PipelineSet[], unknown?]
        >().mockResolvedValue({ modifiedCount: 1, upsertedCount: 0 }),
        deleteOne: jest.fn(),
    };
    const consumer = new MembershipConsumer(memberModel as never, { get: jest.fn() } as never, {} as never);
    const handle = (event: unknown, messageKey: string) =>
        (consumer as unknown as ConsumerWithHandle).handleEvent(event, messageKey);

    beforeEach(() => jest.clearAllMocks());

    it('JOIN은 occurredAt을 저장하는 조건부 upsert로 반영한다', async () => {
        await handle({
            eventType: 'JOIN',
            channelId: 3,
            teamId: 10,
            userId: 42,
            role: 'MEMBER',
            channelType: 'TEXT',
            occurredAt: '2026-08-26T00:00:00',
        }, '3:42');

        const pipeline = memberModel.updateOne.mock.calls[0][1];
        expect(pipeline[0].$set.sourceOccurredAt).toBeDefined();
        expect(pipeline[0].$set.sourceVersion).toBeDefined();
        expect(pipeline[0].$set.deleted).toBeDefined();
        expect(memberModel.updateOne.mock.calls[0][2]).toEqual({ upsert: true, timestamps: false });
    });

    it('LEAVE는 물리 삭제 대신 tombstone을 남긴다', async () => {
        await handle({
            eventType: 'LEAVE',
            channelId: 3,
            teamId: 10,
            userId: 42,
            role: 'MEMBER',
            channelType: 'TEXT',
            occurredAt: '2026-08-26T00:00:00',
        }, '3:42');

        expect(memberModel.deleteOne).not.toHaveBeenCalled();
        const pipeline = memberModel.updateOne.mock.calls[0][1];
        expect(pipeline[0].$set.deleted).toBeDefined();
    });

    it('occurredAt이 없으면 projection을 변경하지 않는다', async () => {
        await expect(handle({
            eventType: 'JOIN',
            channelId: 3,
            teamId: 10,
            userId: 42,
            role: 'MEMBER',
            channelType: 'TEXT',
        }, '3:42')).rejects.toThrow('invalid channel member event payload');

        expect(memberModel.updateOne).not.toHaveBeenCalled();
    });

    it('snapshot=true JOIN은 projection만 갱신하고 socket 변경 알림은 보내지 않는다', async () => {
        const socket = { to: jest.fn().mockReturnValue({ emit: jest.fn() }) };
        consumer.setSocketServer(socket as never);

        await handle({
            eventType: 'JOIN',
            channelId: 3,
            teamId: 10,
            userId: 42,
            role: 'MEMBER',
            channelType: 'TEXT',
            occurredAt: '2026-08-26T00:00:00Z',
            snapshot: true,
        }, '3:42');

        expect(memberModel.updateOne).toHaveBeenCalled();
        expect(socket.to).not.toHaveBeenCalled();
    });
});
