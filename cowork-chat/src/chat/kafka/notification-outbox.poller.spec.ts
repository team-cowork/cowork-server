import { NotificationOutboxPoller } from './notification-outbox.poller';
import { Types } from 'mongoose';
import { NotificationTriggerEvent } from './notification-trigger.producer';

type PollerWithRunCycle = { runPollCycle(): Promise<void> };

describe('NotificationOutboxPoller projection readiness', () => {
    it('role/policy projection이 준비되기 전에는 메시지를 claim하지 않는다', async () => {
        const messageRepository = {
            reclaimStaleProcessing: jest.fn(),
            findPendingAndMarkProcessing: jest.fn(),
        };
        const projectionReadiness = { isReady: jest.fn().mockReturnValue(false) };
        const poller = new NotificationOutboxPoller(
            messageRepository as never,
            {} as never,
            {} as never,
            {} as never,
            {} as never,
            {} as never,
            projectionReadiness as never,
        );

        await (poller as unknown as PollerWithRunCycle).runPollCycle();

        expect(messageRepository.reclaimStaleProcessing).not.toHaveBeenCalled();
        expect(messageRepository.findPendingAndMarkProcessing).not.toHaveBeenCalled();
    });

    it('한 배치에서 message_read 허용 사용자에게만 본문 알림과 unread를 전달한다', async () => {
        const message = {
            _id: new Types.ObjectId(),
            channelId: 10,
            teamId: 1,
            authorId: 1,
            content: 'private body',
            mentions: [3],
            parentMessageId: null,
            notificationRetryCount: 0,
            createdAt: new Date('2026-08-30T00:00:00Z'),
        };
        const messageRepository = {
            reclaimStaleProcessing: jest.fn().mockResolvedValue(0),
            findPendingAndMarkProcessing: jest.fn().mockResolvedValue([message]),
            updateNotificationStatus: jest.fn().mockResolvedValue(undefined),
        };
        const channelMemberRepository = {
            findByChannelIds: jest.fn().mockResolvedValue(new Map([[10, [
                { channelId: 10, userId: 1 },
                { channelId: 10, userId: 2 },
                { channelId: 10, userId: 3 },
            ]]])),
        };
        const triggerProducer = {
            send: jest.fn<Promise<void>, [NotificationTriggerEvent]>().mockResolvedValue(undefined),
        };
        const unreadCounter = { incrementIfPresent: jest.fn().mockResolvedValue(undefined) };
        const accessService = {
            // 4는 role상 읽을 수 있어도 기존 channel member 후보에는 없는 사용자다.
            filterReadableUsersByChannel: jest.fn().mockResolvedValue(new Map([[10, [1, 2, 4]]])),
        };
        const poller = new NotificationOutboxPoller(
            messageRepository as never,
            channelMemberRepository as never,
            triggerProducer as never,
            unreadCounter as never,
            {} as never,
            accessService as never,
            { isReady: jest.fn().mockReturnValue(true) } as never,
        );

        await (poller as unknown as PollerWithRunCycle).runPollCycle();

        expect(accessService.filterReadableUsersByChannel).toHaveBeenCalledTimes(1);
        expect(unreadCounter.incrementIfPresent).toHaveBeenCalledWith(10, [2]);
        expect(triggerProducer.send).toHaveBeenCalledTimes(1);
        const sent = triggerProducer.send.mock.calls[0][0];
        expect(sent.targetUserIds).toEqual([2]);
        expect(sent.forcedUserIds).toEqual([]);
        expect(sent.data.content).toBe('private body');
    });
});
