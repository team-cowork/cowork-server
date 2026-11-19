import {type ElasticsearchService} from '../../search/elasticsearch.service.js';
import {
	type MessageSearchIndexRepository,
	type MessageSearchIndexStateRepository,
	type MessageSearchTombstoneRepository,
} from '../repository/index.js';
import {MessageSearchRebuilder} from './message-search-rebuilder.js';

const elasticsearch = {
	listManagedIndices: jest.fn(),
	getAliasTargets: jest.fn(),
};

const stateRepository = {
	markRebuildStarted: jest.fn(),
	get: jest.fn(),
};

const rebuilder = () => new MessageSearchRebuilder(
	elasticsearch as unknown as ElasticsearchService,
	{} as MessageSearchIndexRepository,
	{} as MessageSearchTombstoneRepository,
	stateRepository as unknown as MessageSearchIndexStateRepository,
);

const flushBackground = async () => new Promise<void>(resolve => {
	setImmediate(resolve);
});

describe('MessageSearchRebuilder.start', () => {
	let failIndexListing: (error: Error) => void;

	beforeEach(() => {
		jest.resetAllMocks();
		// 대상 index 정리 단계에서 재구축을 멈춰 두었다가 테스트가 원하는 시점에 실패시킨다.
		const listing = Promise.withResolvers<string[]>();
		failIndexListing = listing.reject;
		elasticsearch.listManagedIndices.mockReturnValue(listing.promise);
		elasticsearch.getAliasTargets.mockResolvedValue([]);
	});

	it('재구축이 실행 중이면 새 재구축을 시작하지 않는다', () => {
		const target = rebuilder();

		expect(target.start()).toBe(true);
		expect(target.isRunning()).toBe(true);
		expect(target.start({resume: true})).toBe(false);
		expect(elasticsearch.listManagedIndices).toHaveBeenCalledTimes(1);
	});

	it('백그라운드 재구축이 실패하면 실행 중 표시를 풀어 다시 시작할 수 있다', async () => {
		const target = rebuilder();
		expect(target.start()).toBe(true);

		failIndexListing(new Error('elasticsearch unavailable'));
		await flushBackground();

		expect(target.isRunning()).toBe(false);
		expect(target.start()).toBe(true);
		expect(elasticsearch.listManagedIndices).toHaveBeenCalledTimes(2);
	});

	it('재구축을 시작한 적이 없으면 실행 중이 아니다', () => {
		expect(rebuilder().isRunning()).toBe(false);
	});
});
