import {GithubRateLimitError} from '../../github.errors.js';
import type {ProjectBoardApiService} from '../project-board-api.service.js';
import type {GraphqlProject, GraphqlProjectItem} from '../project-board.mapper.js';
import type {ProjectDraftHistory} from './project-draft-history.js';
import {ProjectItemEditorService} from './project-item-editor.service.js';

const target = {owner: 'my-org', projectNodeId: 'PVT_1', itemId: 'PVTI_1'};
const project: GraphqlProject = {
	id: 'PVT_1', number: 1, title: 'Plan', url: '', closed: false,
	owner: {__typename: 'Organization', login: 'My-Org'},
	fields: {
		nodes: [
			{id: 'text', dataType: 'TEXT'},
			{id: 'number', dataType: 'NUMBER'},
			{id: 'date', dataType: 'DATE'},
			{id: 'select', dataType: 'SINGLE_SELECT', options: [{id: 'todo', name: 'Todo', color: 'GRAY'}]},
			{
				id: 'iteration', dataType: 'ITERATION', configuration: {
					completedIterations: [{
						id: 'sprint', title: 'Sprint', startDate: '2026-11-01', duration: 7,
					}],
				},
			},
			{id: 'title', dataType: 'TITLE'},
		],
	},
};
const issue: GraphqlProjectItem = {
	id: 'PVTI_1', type: 'ISSUE', isArchived: false, updatedAt: '2026-11-19T00:00:00Z',
	project: {id: 'PVT_1', number: 1},
	content: {id: 'I_1', repository: {nameWithOwner: 'My-Org/App'}},
};

describe('ProjectItemEditorService', () => {
	let api: Record<'getProjectByNode' | 'getItem' | 'getRepository' | 'updateFieldValue' | 'clearFieldValue'
		| 'createDraft' | 'listItems' | 'updateDraft' | 'addItem' | 'removeItem' | 'convertDraft' | 'setAssignees', jest.Mock>;
	let history: {claim: jest.Mock; save: jest.Mock; markRetryable: jest.Mock};
	let service: ProjectItemEditorService;

	beforeEach(() => {
		api = {
			getProjectByNode: jest.fn().mockResolvedValue(project),
			getItem: jest.fn().mockResolvedValue(issue),
			getRepository: jest.fn().mockResolvedValue({
				id: 'R_1', nameWithOwner: 'My-Org/App', owner: {__typename: 'Organization', login: 'My-Org'},
				issueOrPullRequest: {__typename: 'Issue', id: 'I_2'},
			}),
			updateFieldValue: jest.fn(), clearFieldValue: jest.fn(), createDraft: jest.fn().mockResolvedValue('PVTI_NEW'),
			listItems: jest.fn(), updateDraft: jest.fn(), addItem: jest.fn().mockResolvedValue('PVTI_NEW'), removeItem: jest.fn(),
			convertDraft: jest.fn(), setAssignees: jest.fn(),
		};
		history = {claim: jest.fn(), save: jest.fn(), markRetryable: jest.fn()};
		service = new ProjectItemEditorService(api as unknown as ProjectBoardApiService, history as unknown as ProjectDraftHistory);
	});

	describe('setField', () => {
		describe('현재 필드 설정과 일치하는 경우', () => {
			it.each([
				['text', {text: ''}],
				['number', {number: 0}],
				['date', {date: '2026-11-19'}],
				['select', {singleSelectOptionId: 'todo'}],
				['iteration', {iterationId: 'sprint'}],
			])('%s 필드 값을 적용한다', async (fieldId, value) => {
				await service.setField(target, {fieldId, value});
				expect(api.updateFieldValue).toHaveBeenCalledWith({...target, fieldId, value});
			});
		});
		describe('허용하지 않는 필드 값인 경우', () => {
			it.each([
				['number', {text: '1'}],
				['select', {singleSelectOptionId: 'other'}],
				['iteration', {iterationId: 'other'}],
				['text', {text: 'a', number: 1}],
				['text', {}],
				['title', {text: 'change'}],
			])('%s 변경을 거부한다: %j', async (fieldId, value) => {
				await expect(service.setField(target, {fieldId, value})).rejects.toMatchObject({statusCode: 400});
				expect(api.updateFieldValue).not.toHaveBeenCalled();
			});
		});
	});

	describe('clearField', () => {
		it('이슈의 기본 속성을 Project 필드로 비우지 않는다', async () => {
			await expect(service.clearField(target, 'title')).rejects.toMatchObject({statusCode: 400});
			expect(api.clearFieldValue).not.toHaveBeenCalled();
		});
	});

	describe('addItem', () => {
		describe('팀 조직의 저장소인 경우', () => {
			it.each(['Issue', 'PullRequest'])('%s를 카드로 추가한다', async contentType => {
				api.getRepository.mockResolvedValue({
					id: 'R_1', nameWithOwner: 'My-Org/App', owner: {__typename: 'Organization', login: 'My-Org'},
					issueOrPullRequest: {__typename: contentType, id: 'CONTENT_1'},
				});
				await expect(service.addItem(target, {repo: 'app', number: 42})).resolves.toEqual({itemId: 'PVTI_NEW'});
				expect(api.addItem).toHaveBeenCalledWith('my-org', 'PVT_1', 'CONTENT_1');
			});
		});
		describe('소유자가 달라진 경우', () => {
			it('다른 조직으로 이전된 저장소를 거부한다', async () => {
				api.getRepository.mockResolvedValue({id: 'R_1', nameWithOwner: 'Other-Org/App', owner: {__typename: 'Organization', login: 'Other-Org'}});
				await expect(service.addItem(target, {repo: 'app', number: 42})).rejects.toMatchObject({statusCode: 403});
				expect(api.addItem).not.toHaveBeenCalled();
			});
			it('다른 조직으로 이전된 Project를 거부한다', async () => {
				api.getProjectByNode.mockResolvedValue({...project, owner: {__typename: 'Organization', login: 'other'}});
				await expect(service.addItem(target, {repo: 'app', number: 42})).rejects.toMatchObject({statusCode: 403});
				expect(api.addItem).not.toHaveBeenCalled();
			});
		});
	});

	describe('createDraft', () => {
		it('GitHub가 기존 이슈 추가로 해석하는 제목을 거부한다', async () => {
			await expect(service.createDraft(target, {title: 'https://github.com/other/private/issues/1', body: ''}, 'op')).rejects.toMatchObject({statusCode: 400});
			expect(api.createDraft).not.toHaveBeenCalled();
		});

		it('새 draft 생성이 호출 한도로 거절되면 재시도할 수 있게 한다', async () => {
			history.claim.mockImplementation(async (_operationId: string, fingerprint: string) => ({
				record: {fingerprint, claimId: 'claim'}, claimed: true,
			}));
			const throttled = new GithubRateLimitError('Secondary rate limit');
			api.createDraft.mockRejectedValueOnce(throttled);

			await expect(service.createDraft(target, {title: 'Title', body: 'Body'}, 'op')).rejects.toBe(throttled);
			expect(history.markRetryable).toHaveBeenCalledWith('op', expect.objectContaining({claimId: 'claim'}));
			expect(history.save).not.toHaveBeenCalled();
		});

		it('새 draft의 생성 결과가 불명확하면 재시도 표시를 하지 않는다', async () => {
			history.claim.mockImplementation(async (_operationId: string, fingerprint: string) => ({
				record: {fingerprint, claimId: 'claim'}, claimed: true,
			}));
			api.createDraft.mockRejectedValueOnce(new Error('Connection lost'));

			await expect(service.createDraft(target, {title: 'Title', body: 'Body'}, 'op')).rejects.toThrow('Connection lost');
			expect(history.markRetryable).not.toHaveBeenCalled();
		});

		it('이전 draft 확인이 호출 한도로 거절되면 기존 이력을 보존한다', async () => {
			history.claim.mockImplementation(async (_operationId: string, fingerprint: string) => ({
				record: {fingerprint}, claimed: false,
			}));
			const throttled = new GithubRateLimitError('Secondary rate limit');
			api.listItems.mockRejectedValueOnce(throttled);

			await expect(service.createDraft(target, {title: 'Title', body: 'Body'}, 'op')).rejects.toBe(throttled);
			expect(history.markRetryable).not.toHaveBeenCalled();
			expect(api.createDraft).not.toHaveBeenCalled();
		});
	});

	describe('updateDraft', () => {
		it('제목을 유지하면서 본문을 비운다', async () => {
			api.getItem.mockResolvedValue({...issue, type: 'DRAFT_ISSUE', content: {id: 'DI_1'}});
			await service.updateDraft(target, {title: null, body: ''});
			expect(api.updateDraft).toHaveBeenCalledWith('my-org', 'DI_1', {body: ''});
		});
		it('일반 이슈의 제목과 본문을 draft API로 수정하지 않는다', async () => {
			await expect(service.updateDraft(target, {title: 'Title'})).rejects.toMatchObject({statusCode: 400});
			expect(api.updateDraft).not.toHaveBeenCalled();
		});
	});

	describe('removeItem', () => {
		it('다른 Project의 카드 ID를 거부한다', async () => {
			api.getItem.mockResolvedValue({...issue, project: {id: 'PVT_OTHER', number: 2}});
			await expect(service.removeItem(target)).rejects.toMatchObject({statusCode: 404});
			expect(api.removeItem).not.toHaveBeenCalled();
		});
	});

	describe('convertDraft', () => {
		it('선택한 조직 저장소에 이슈를 만든다', async () => {
			api.getItem.mockResolvedValue({...issue, type: 'DRAFT_ISSUE', content: {id: 'DI_1'}});
			await expect(service.convertDraft(target, 'app')).resolves.toEqual({itemId: 'PVTI_1'});
			expect(api.convertDraft).toHaveBeenCalledWith('my-org', 'PVTI_1', 'R_1');
		});
		it('PR 카드는 전환하지 않는다', async () => {
			api.getItem.mockResolvedValue({...issue, type: 'PULL_REQUEST'});
			await expect(service.convertDraft(target, 'app')).rejects.toMatchObject({statusCode: 400});
			expect(api.convertDraft).not.toHaveBeenCalled();
		});
	});

	describe('setAssignees', () => {
		it.each([{assignees: ['alice']}, {assignees: []}])('담당자를 지정한 목록으로 교체한다: $assignees', async ({assignees}) => {
			await service.setAssignees(target, {repo: 'app', assignees});
			expect(api.setAssignees).toHaveBeenCalledWith('my-org', 'I_1', assignees);
		});
		it('외부 조직의 이슈 담당자를 바꾸지 않는다', async () => {
			api.getItem.mockResolvedValue({...issue, content: {id: 'I_1', repository: {nameWithOwner: 'other/app'}}});
			await expect(service.setAssignees(target, {repo: 'app', assignees: ['alice']})).rejects.toMatchObject({statusCode: 403});
			expect(api.setAssignees).not.toHaveBeenCalled();
		});
	});
});
