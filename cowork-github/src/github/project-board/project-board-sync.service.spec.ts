import {GithubClientError} from '../github.errors.js';
import type {ProjectBoardStateProducer} from './kafka/project-board-state.producer.js';
import type {ProjectBoardApiService} from './project-board-api.service.js';
import {PROJECT_REFRESH_DEBOUNCE_MS, ProjectBoardSyncService} from './project-board-sync.service.js';

const project = {
	id: 'PVT_1', number: 3, title: 'Roadmap', url: 'u', closed: false, owner: {__typename: 'Organization', login: 'my-org'},
};
const item = {
	id: 'PVTI_1',
	type: 'DRAFT_ISSUE',
	isArchived: false,
	updatedAt: '2026-11-01T00:00:00Z',
	project: {id: 'PVT_1', number: 3},
	content: null,
};

describe('ProjectBoardSyncService', () => {
	let api: Record<'getProjectByNumber' | 'getProjectByNode' | 'listItems' | 'listItemIds' | 'getItem', jest.Mock>;
	let producer: Record<'sendProject' | 'sendItem' | 'sendSyncCompleted', jest.Mock>;
	let service: ProjectBoardSyncService;

	beforeEach(() => {
		api = {
			getProjectByNumber: jest.fn().mockResolvedValue(project),
			getProjectByNode: jest.fn().mockResolvedValue(project),
			listItems: jest.fn().mockResolvedValue([item]),
			listItemIds: jest.fn().mockResolvedValue(['PVTI_1']),
			getItem: jest.fn(),
		};
		producer = {sendProject: jest.fn(), sendItem: jest.fn(), sendSyncCompleted: jest.fn()};
		service = new ProjectBoardSyncService(
			api as unknown as ProjectBoardApiService,
			producer as unknown as ProjectBoardStateProducer,
		);
	});

	afterEach(() => {
		service.onModuleDestroy();
		jest.useRealTimers();
	});

	it('item을 모두 보낸 뒤 순서가 담긴 Project 레코드와 완료 마커를 보낸다', async () => {
		await service.syncProject('my-org', 3);

		expect(producer.sendItem).toHaveBeenCalledWith(expect.objectContaining({itemId: 'PVTI_1', owner: 'my-org'}));
		expect(producer.sendProject).toHaveBeenCalledWith(expect.objectContaining({itemIds: ['PVTI_1'], accessError: null}));
		expect(producer.sendSyncCompleted).toHaveBeenCalledWith('my-org', 3);
		expect(producer.sendItem.mock.invocationCallOrder[0]).toBeLessThan(producer.sendProject.mock.invocationCallOrder[0]);
	});

	it('Project가 없거나 권한이 없으면 원인을 Project 레코드로 남기고 완료 마커를 보낸다', async () => {
		api.getProjectByNumber.mockResolvedValueOnce(null);
		await service.syncProject('my-org', 3);
		expect(producer.sendProject).toHaveBeenLastCalledWith(expect.objectContaining({accessError: 'NOT_FOUND', deleted: false}));

		api.getProjectByNumber.mockRejectedValueOnce(new GithubClientError('Resource not accessible by integration', 403));
		await service.syncProject('my-org', 3);
		expect(producer.sendProject).toHaveBeenLastCalledWith(expect.objectContaining({accessError: 'FORBIDDEN'}));
		expect(producer.sendSyncCompleted).toHaveBeenCalledTimes(2);
	});

	it('일시적인 오류는 마커 없이 던진다', async () => {
		api.listItems.mockRejectedValueOnce(new Error('GitHub 502'));

		await expect(service.syncProject('my-org', 3)).rejects.toThrow('GitHub 502');
		expect(producer.sendSyncCompleted).not.toHaveBeenCalled();
	});

	it('같은 Project의 새로 고침 요청을 한 번으로 모은다', async () => {
		jest.useFakeTimers();
		service.scheduleProjectRefresh('my-org', 'PVT_1');
		service.scheduleProjectRefresh('my-org', 'PVT_1');

		await jest.advanceTimersByTimeAsync(PROJECT_REFRESH_DEBOUNCE_MS);

		expect(api.getProjectByNode).toHaveBeenCalledTimes(1);
		expect(producer.sendProject).toHaveBeenCalledWith(expect.objectContaining({itemIds: ['PVTI_1']}));
	});

	it('이미 지워진 item은 다시 읽지 못해도 실패로 보지 않는다', async () => {
		api.getItem.mockRejectedValueOnce(new GithubClientError('Could not resolve', 404));

		await expect(service.refreshItem('my-org', 'PVTI_1')).resolves.toBeUndefined();
		expect(producer.sendItem).not.toHaveBeenCalled();
	});

	it('삭제 state의 버전은 받은 시각보다 이르지 않다', async () => {
		const before = Date.now();
		await service.publishItemDeleted({
			owner: 'My-Org', projectNodeId: 'PVT_1', itemId: 'PVTI_1', updatedAt: '2020-01-01T00:00:00Z',
		});

		const [[event]] = producer.sendItem.mock.calls as Array<[{sourceOccurredAt: string; deleted: boolean; owner: string}]>;
		expect(event.deleted).toBe(true);
		expect(event.owner).toBe('my-org');
		expect(Date.parse(event.sourceOccurredAt)).toBeGreaterThanOrEqual(before);
	});
});
