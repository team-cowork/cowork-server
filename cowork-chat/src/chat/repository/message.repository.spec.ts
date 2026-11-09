import {type Model, Types} from 'mongoose';
import type {Message} from '../schema/message.schema.js';
import {MessageRepository, toMessageBroadcastPayload} from './message.repository.js';

describe('toMessageBroadcastPayload', () => {
	it('클라이언트가 쓰는 필드만 남기고 아웃박스 내부 상태 필드는 제외한다', () => {
		const id = new Types.ObjectId();
		const createdAt = new Date('2026-05-12T00:00:00.000Z');
		const fullDocument = {
			_id: id,
			teamId: 10,
			projectId: null,
			channelId: 1,
			authorId: 42,
			content: '안녕하세요',
			type: 'TEXT',
			attachments: [],
			parentMessageId: null,
			isEdited: false,
			editHistory: [],
			isPinned: false,
			reactions: [],
			clientMessageId: 'client-1',
			mentions: [],
			createdAt,
			updatedAt: createdAt,
			notificationStatus: 'PENDING',
			notificationRetryCount: 0,
			notificationProcessingStartedAt: null,
			notificationClaimId: null,
			searchIndexStatus: 'PENDING',
			searchIndexVersion: 1,
			searchIndexSyncedVersion: 0,
			searchIndexRetryCount: 0,
			searchIndexNextAttemptAt: createdAt,
			searchIndexProcessingStartedAt: null,
			searchIndexClaimId: null,
			searchIndexLastError: null,
			searchIndexSyncedAt: null,
		};
		const doc = {toObject: () => fullDocument} as unknown as Parameters<typeof toMessageBroadcastPayload>[0];

		const payload = toMessageBroadcastPayload(doc);

		expect(payload).toEqual({
			_id: id,
			teamId: 10,
			projectId: null,
			channelId: 1,
			authorId: 42,
			content: '안녕하세요',
			type: 'TEXT',
			attachments: [],
			parentMessageId: null,
			isEdited: false,
			isPinned: false,
			clientMessageId: 'client-1',
			mentions: [],
			reactions: [],
			createdAt,
			updatedAt: createdAt,
		});
		expect(payload).not.toHaveProperty('notificationStatus');
		expect(payload).not.toHaveProperty('searchIndexStatus');
		expect(payload).not.toHaveProperty('editHistory');
	});
});

describe('MessageRepository unread 집계', () => {
	const lastReadId = new Types.ObjectId();
	const messageModel = {
		countDocuments: jest.fn().mockResolvedValue(3),
		aggregate: jest.fn().mockResolvedValue([{_id: 1, count: 3}]),
	};
	const repository = new MessageRepository(messageModel as unknown as Model<Message>);

	it('단일 채널 unread는 답장을 포함하고 삭제된 웹훅 영수증은 제외한다', async () => {
		await expect(repository.countUnread(1, lastReadId)).resolves.toBe(3);

		expect(messageModel.countDocuments).toHaveBeenCalledWith({webhookDeleted: {$ne: true}, channelId: 1, _id: {$gt: lastReadId}});
	});

	it('다중 채널 unread도 답장을 포함하고 삭제된 웹훅 영수증은 제외한다', async () => {
		const counts = await repository.countUnreadForChannels([
			{channelId: 1, lastReadMessageId: lastReadId},
			{channelId: 2, lastReadMessageId: null},
		]);

		expect(counts).toEqual(new Map([[1, 3]]));
		expect(messageModel.aggregate).toHaveBeenCalledWith([
			{$match: {webhookDeleted: {$ne: true}, $or: [{channelId: 1, _id: {$gt: lastReadId}}, {channelId: 2}]}},
			{$group: {_id: '$channelId', count: {$sum: 1}}},
		]);
	});
});

describe('MessageRepository 메시지 위치 조회', () => {
	type Stage = {$match?: {_id?: unknown}; $sort?: {_id: number}; $limit?: number};
	const ids = [1, 2, 3, 4, 5].map(n => new Types.ObjectId(n.toString(16).padStart(24, '0')));
	const aggregate = jest.fn<Promise<Array<{_id: Types.ObjectId}>>, [Stage[]]>();
	const repository = new MessageRepository({aggregate, collection: {name: 'messages'}} as unknown as Model<Message>);
	const calledStages = (index: number) => aggregate.mock.calls.map(([pipeline]) => pipeline[index]);

	beforeEach(() => {
		aggregate.mockReset();
	});

	it('around는 기준 메시지를 포함해 앞뒤 메시지를 최신순으로 합친다', async () => {
		aggregate.mockImplementation(async pipeline => (
			pipeline[1].$sort?._id === 1 ? [{_id: ids[3]}, {_id: ids[4]}] : [{_id: ids[2]}, {_id: ids[1]}]
		));

		const rows = await repository.findMessagesAround(1, ids[2].toHexString());

		expect(rows.map(row => row._id)).toEqual([ids[4], ids[3], ids[2], ids[1]]);
		expect(calledStages(0).map(stage => stage.$match?._id)).toEqual([{$gt: ids[2]}, {$lte: ids[2]}]);
		expect(calledStages(2).map(stage => stage.$limit)).toEqual([50, 50]);
	});

	it('after는 이후 메시지를 오름차순으로 조회한 뒤 최신순으로 반환한다', async () => {
		aggregate.mockResolvedValue([{_id: ids[3]}, {_id: ids[4]}]);

		const rows = await repository.findMessages(1, undefined, undefined, ids[2].toHexString());

		expect(rows.map(row => row._id)).toEqual([ids[4], ids[3]]);
		expect(calledStages(0)[0].$match?._id).toEqual({$gt: ids[2]});
		expect(calledStages(1)[0]).toEqual({$sort: {_id: 1}});
	});
});

describe('MessageRepository 알림 아웃박스 점유', () => {
	it('다음 시도 시각이 지나지 않은 PENDING 메시지는 점유하지 않는다', async () => {
		const lean = jest.fn().mockResolvedValue([]);
		const chain = {
			sort: jest.fn().mockReturnThis(), limit: jest.fn().mockReturnThis(), select: jest.fn().mockReturnThis(), lean,
		};
		const messageModel = {find: jest.fn().mockReturnValue(chain)};
		const repository = new MessageRepository(messageModel as unknown as Model<Message>);

		await expect(repository.findPendingAndMarkProcessing(10)).resolves.toEqual([]);

		expect(messageModel.find).toHaveBeenCalledWith({
			webhookDeleted: {$ne: true},
			notificationStatus: 'PENDING',
			$or: [{notificationNextAttemptAt: null}, {notificationNextAttemptAt: {$lte: expect.any(Date) as Date}}],
		});
	});
});

describe('MessageRepository 메시지 수정', () => {
	it('본문의 $ 문자열을 표현식이 아닌 리터럴로 저장한다', async () => {
		const findOneAndUpdate = jest.fn().mockResolvedValue(null);
		const repository = new MessageRepository({findOneAndUpdate} as unknown as Model<Message>);

		await repository.applyEdit(new Types.ObjectId().toHexString(), '$attachments', false);

		const [, [stage]] = findOneAndUpdate.mock.calls[0] as [unknown, Array<{$set: {content: unknown}}>];
		expect(stage.$set.content).toEqual({$literal: '$attachments'});
	});
});
