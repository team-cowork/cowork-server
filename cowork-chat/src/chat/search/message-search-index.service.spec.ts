import {Types} from 'mongoose';
import {type TombstoneRecord} from '../repository/index.js';
import {MessageSearchIndexService} from './message-search-index.service.js';

const tombstone = (messageId: string, version: number): TombstoneRecord => ({
	_id: new Types.ObjectId(),
	messageId,
	version,
	retryCount: 0,
} as unknown as TombstoneRecord);

describe('MessageSearchIndexService.applyTombstones', () => {
	it('한 tombstone의 MongoDB 삭제가 실패해도 나머지는 색인 삭제와 상태 전이를 끝낸다', async () => {
		const service = new MessageSearchIndexService();
		const first = tombstone('m1', 3);
		const failed = tombstone('m2', 4);
		const third = tombstone('m3', 5);
		const messageRepository = {
			deleteById: jest.fn(async (messageId: string) => {
				if (messageId === 'm2') {
					throw new Error('mongo timeout');
				}
			}),
		};
		const elasticsearchService = {
			bulkDeleteForOutbox: jest.fn().mockResolvedValue([
				{messageId: 'm1', result: {outcome: 'APPLIED'}},
				{messageId: 'm3', result: {outcome: 'APPLIED'}},
			]),
		};
		const tombstoneRepository = {
			markDeleted: jest.fn().mockResolvedValue(undefined),
			markRetry: jest.fn().mockResolvedValue(undefined),
			markFailed: jest.fn().mockResolvedValue(undefined),
		};
		Object.assign(service, {
			messageRepository,
			elasticsearchService,
			tombstoneRepository,
			logger: {warn: jest.fn(), error: jest.fn(), log: jest.fn()},
		});

		await service.applyTombstones([first, failed, third]);

		expect(messageRepository.deleteById).toHaveBeenCalledTimes(3);
		expect(elasticsearchService.bulkDeleteForOutbox).toHaveBeenCalledWith([
			{messageId: 'm1', version: 3},
			{messageId: 'm3', version: 5},
		]);
		expect(tombstoneRepository.markDeleted).toHaveBeenCalledWith(first._id);
		expect(tombstoneRepository.markDeleted).toHaveBeenCalledWith(third._id);
		expect(tombstoneRepository.markRetry).toHaveBeenCalledWith(failed._id, 1, expect.any(Date), 'mongo timeout');
		expect(tombstoneRepository.markFailed).not.toHaveBeenCalled();
	});
});
