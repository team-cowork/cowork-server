import {type ElasticsearchService} from '../../search/elasticsearch.service.js';
import {
	type MessageSearchIndexRepository,
	type MessageSearchIndexStateRepository,
	type MessageSearchTombstoneRepository,
} from '../repository/index.js';
import {MessageSearchRebuilder} from './message-search-rebuilder.js';

const unavailableElasticsearch = new Proxy({}, {
	get: () => jest.fn().mockRejectedValue(new Error('elasticsearch unavailable')),
}) as ElasticsearchService;

const stateRepository = {
	tryAcquireRebuildLock: jest.fn(),
	releaseRebuildLock: jest.fn(),
	markRebuildStarted: jest.fn(),
	get: jest.fn(),
};

const rebuilder = () => new MessageSearchRebuilder(
	unavailableElasticsearch,
	{} as MessageSearchIndexRepository,
	{} as MessageSearchTombstoneRepository,
	stateRepository as unknown as MessageSearchIndexStateRepository,
);

describe('MessageSearchRebuilder.start', () => {
	beforeEach(() => {
		jest.resetAllMocks();
		stateRepository.releaseRebuildLock.mockResolvedValue(undefined);
	});

	it('다른 재구축이 락을 쥐고 있으면 시작하지 않는다', async () => {
		stateRepository.tryAcquireRebuildLock.mockResolvedValue(false);

		await expect(rebuilder().start()).resolves.toBe(false);

		expect(stateRepository.markRebuildStarted).not.toHaveBeenCalled();
		expect(stateRepository.releaseRebuildLock).not.toHaveBeenCalled();
	});

	it('백그라운드 재구축이 실패해도 점유한 락을 해제한다', async () => {
		stateRepository.tryAcquireRebuildLock.mockResolvedValue(true);

		await expect(rebuilder().start()).resolves.toBe(true);

		await new Promise<void>(resolve => {
			setImmediate(resolve);
		});
		const [lockId] = stateRepository.tryAcquireRebuildLock.mock.calls[0] as [string];
		expect(stateRepository.releaseRebuildLock).toHaveBeenCalledWith(lockId);
	});
});
