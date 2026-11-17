import {githubField} from '../github-field.js';
import {ProjectBoardWebhookHandler} from './project-board-webhook.handler.js';
import type {ProjectBoardSyncService} from './project-board-sync.service.js';

describe('ProjectBoardWebhookHandler', () => {
	let syncService: Record<
		'publishItemDeleted' | 'refreshItem' | 'scheduleProjectRefresh' | 'publishProjectDeleted' | 'refreshContentItems',
		jest.Mock
	>;
	let handler: ProjectBoardWebhookHandler;
	const itemPayload = (action: string, changes?: Record<string, unknown>) => ({
		action,
		organization: {login: 'my-org'},
		[githubField.projectsV2Item]: {[githubField.nodeId]: 'PVTI_1', [githubField.projectNodeId]: 'PVT_1', [githubField.updatedAt]: '2026-11-01T00:00:00Z'},
		...(changes !== undefined && {changes}),
	});

	beforeEach(() => {
		syncService = {
			publishItemDeleted: jest.fn(),
			refreshItem: jest.fn(),
			scheduleProjectRefresh: jest.fn(),
			publishProjectDeleted: jest.fn(),
			refreshContentItems: jest.fn(),
		};
		handler = new ProjectBoardWebhookHandler(syncService as unknown as ProjectBoardSyncService);
	});

	it('created는 item을 다시 읽고 순서를 새로 고친다', async () => {
		await handler.handleProjectItem(itemPayload('created'));

		expect(syncService.refreshItem).toHaveBeenCalledWith('my-org', 'PVTI_1');
		expect(syncService.scheduleProjectRefresh).toHaveBeenCalledWith('my-org', 'PVT_1');
	});

	it('deleted는 삭제 state를 발행하고 순서를 새로 고친다', async () => {
		await handler.handleProjectItem(itemPayload('deleted'));

		expect(syncService.publishItemDeleted).toHaveBeenCalledWith({
			owner: 'my-org', projectNodeId: 'PVT_1', itemId: 'PVTI_1', updatedAt: '2026-11-01T00:00:00Z',
		});
		expect(syncService.refreshItem).not.toHaveBeenCalled();
		expect(syncService.scheduleProjectRefresh).toHaveBeenCalled();
	});

	it('reordered는 item을 다시 읽지 않고 순서만 새로 고친다', async () => {
		await handler.handleProjectItem(itemPayload('reordered'));

		expect(syncService.refreshItem).not.toHaveBeenCalled();
		expect(syncService.scheduleProjectRefresh).toHaveBeenCalledWith('my-org', 'PVT_1');
	});

	it('text field 변경은 item만 다시 읽고, 컬럼 field 변경은 설정도 새로 고친다', async () => {
		await handler.handleProjectItem(itemPayload('edited', {[githubField.fieldValue]: {[githubField.fieldType]: 'text'}}));
		expect(syncService.refreshItem).toHaveBeenCalledTimes(1);
		expect(syncService.scheduleProjectRefresh).not.toHaveBeenCalled();

		await handler.handleProjectItem(itemPayload('edited', {[githubField.fieldValue]: {[githubField.fieldType]: 'single_select'}}));
		expect(syncService.scheduleProjectRefresh).toHaveBeenCalledWith('my-org', 'PVT_1');
	});

	it('조직 정보가 없는 item 이벤트는 무시한다', async () => {
		await handler.handleProjectItem({action: 'created', [githubField.projectsV2Item]: {[githubField.nodeId]: 'PVTI_1', [githubField.projectNodeId]: 'PVT_1'}});

		expect(syncService.refreshItem).not.toHaveBeenCalled();
	});

	it('Project 삭제는 삭제 state를, 수정은 새로 고침을 요청한다', async () => {
		await handler.handleProject({action: 'deleted', organization: {login: 'my-org'}, [githubField.projectsV2]: {[githubField.nodeId]: 'PVT_1', number: 3}});
		await handler.handleProject({action: 'edited', organization: {login: 'my-org'}, [githubField.projectsV2]: {[githubField.nodeId]: 'PVT_1', number: 3}});

		expect(syncService.publishProjectDeleted).toHaveBeenCalledWith('my-org', 3);
		expect(syncService.scheduleProjectRefresh).toHaveBeenCalledWith('my-org', 'PVT_1');
	});

	it('content 새로 고침이 실패해도 예외를 던지지 않는다', async () => {
		syncService.refreshContentItems.mockRejectedValue(new Error('forbidden'));

		await expect(handler.handleContentChanged('my-org', 'I_1')).resolves.toBeUndefined();
		await handler.handleContentChanged('my-org', undefined);
		expect(syncService.refreshContentItems).toHaveBeenCalledTimes(1);
	});
});
