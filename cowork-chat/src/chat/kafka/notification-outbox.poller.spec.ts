import {Types} from 'mongoose';
import {NotificationOutboxPoller, nextAttemptAt} from './notification-outbox.poller.js';

describe('nextAttemptAt', () => {
	const now = Date.parse('2026-11-01T00:00:00.000Z');

	it('실패 횟수마다 대기 시간을 두 배로 늘린다', () => {
		expect(nextAttemptAt(1, now).getTime() - now).toBe(5000);
		expect(nextAttemptAt(2, now).getTime() - now).toBe(10_000);
		expect(nextAttemptAt(3, now).getTime() - now).toBe(20_000);
	});

	it('대기 시간은 5분을 넘지 않는다', () => {
		expect(nextAttemptAt(9, now).getTime() - now).toBe(5 * 60 * 1000);
	});
});

describe('NotificationOutboxPoller 실패 처리', () => {
	const claimId = 'claim-1';
	let messageRepository: Record<string, jest.Mock>;
	let triggerProducer: {send: jest.Mock};
	let poller: NotificationOutboxPoller;

	const claimedMessage = (notificationRetryCount: number) => ({
		_id: new Types.ObjectId(),
		channelId: 1,
		teamId: 10,
		authorId: 42,
		content: 'hi',
		mentions: [],
		parentMessageId: null,
		createdAt: new Date(),
		notificationRetryCount,
		notificationClaimId: claimId,
	});

	beforeEach(() => {
		messageRepository = {
			reclaimStaleProcessing: jest.fn().mockResolvedValue(0),
			findPendingAndMarkProcessing: jest.fn(),
			findParentAuthorsByChannel: jest.fn().mockResolvedValue(new Map()),
			updateNotificationStatus: jest.fn().mockResolvedValue(undefined),
		};
		triggerProducer = {send: jest.fn().mockRejectedValue(new Error('broker unavailable'))};
		poller = new NotificationOutboxPoller();
		Object.assign(poller, {
			messageRepository,
			triggerProducer,
			unreadCounterService: {incrementIfPresent: jest.fn().mockResolvedValue(undefined)},
			channelMessageReadAccess: {findReadableUsersByChannel: jest.fn().mockResolvedValue(new Map([[1, [42, 7]]]))},
			projectionReadiness: {isReady: () => true},
			logger: {error: jest.fn(), warn: jest.fn(), log: jest.fn()},
		});
	});

	const runCycle = async () => (poller as unknown as {runPollCycle: () => Promise<void>}).runPollCycle();

	it('발행 실패 시 PENDING으로 되돌리며 다음 시도 시각을 미룬다', async () => {
		const claimed = claimedMessage(0);
		messageRepository.findPendingAndMarkProcessing.mockResolvedValue([claimed]);
		const before = Date.now();

		await runCycle();

		expect(messageRepository.updateNotificationStatus).toHaveBeenCalledWith(claimed._id, claimId, 'PENDING', {retryCount: 1, nextAttemptAt: expect.any(Date) as Date});
		const {nextAttemptAt: scheduled} = (messageRepository.updateNotificationStatus.mock.calls[0] as unknown[])[3] as {nextAttemptAt: Date};
		expect(scheduled.getTime()).toBeGreaterThanOrEqual(before + 5000);
	});

	it('세 번째 실패로는 FAILED가 되지 않는다', async () => {
		const claimed = claimedMessage(2);
		messageRepository.findPendingAndMarkProcessing.mockResolvedValue([claimed]);

		await runCycle();

		expect(messageRepository.updateNotificationStatus).toHaveBeenCalledWith(claimed._id, claimId, 'PENDING', {retryCount: 3, nextAttemptAt: expect.any(Date) as Date});
	});

	it('재시도 한도에 닿으면 FAILED로 전환하고 다음 시도 시각은 남기지 않는다', async () => {
		const claimed = claimedMessage(9);
		messageRepository.findPendingAndMarkProcessing.mockResolvedValue([claimed]);

		await runCycle();

		expect(messageRepository.updateNotificationStatus).toHaveBeenCalledWith(claimed._id, claimId, 'FAILED', {retryCount: 10});
	});
});
