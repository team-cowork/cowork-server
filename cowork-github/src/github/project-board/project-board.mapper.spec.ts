import {
	type GraphqlProject,
	type GraphqlProjectItem,
	organizationLogin,
	toFieldValueState,
	toProjectItemState,
	toProjectState,
} from './project-board.mapper.js';

const item = (overrides: Partial<GraphqlProjectItem> = {}): GraphqlProjectItem => ({
	id: 'PVTI_1',
	type: 'ISSUE',
	isArchived: false,
	updatedAt: '2026-11-01T00:00:00Z',
	project: {id: 'PVT_1', number: 3, owner: {__typename: 'Organization', login: 'My-Org'}},
	content: {
		__typename: 'Issue',
		id: 'I_1',
		number: 7,
		title: '로그인 오류',
		state: 'OPEN',
		url: 'https://github.com/my-org/app/issues/7',
		updatedAt: '2026-11-02T00:00:00Z',
		repository: {nameWithOwner: 'my-org/app'},
		assignees: {nodes: [{login: 'octocat'}, null]},
		labels: {nodes: [{name: 'bug', color: 'd73a4a'}]},
		milestone: {title: 'v1'},
	},
	fieldValues: {
		nodes: [
			{__typename: 'ProjectV2ItemFieldSingleSelectValue', optionId: 'opt-todo', field: {id: 'F_STATUS', name: 'Status'}},
			{__typename: 'ProjectV2ItemFieldRepositoryValue', field: {id: 'F_REPO', name: 'Repository'}},
		],
	},
	...overrides,
});

describe('project board mapper', () => {
	it('item을 content와 지원하는 field 값만 담은 state로 바꾼다', () => {
		const state = toProjectItemState(item(), 'fallback');

		expect(state).toEqual({
			owner: 'my-org',
			projectNodeId: 'PVT_1',
			projectNumber: 3,
			itemId: 'PVTI_1',
			type: 'ISSUE',
			contentNodeId: 'I_1',
			content: {
				title: '로그인 오류',
				number: 7,
				state: 'OPEN',
				url: 'https://github.com/my-org/app/issues/7',
				repository: 'my-org/app',
				assignees: ['octocat'],
				labels: [{name: 'bug', color: 'd73a4a'}],
				milestone: 'v1',
				draft: false,
			},
			fieldValues: [{
				fieldId: 'F_STATUS',
				fieldName: 'Status',
				dataType: 'SINGLE_SELECT',
				text: null,
				number: null,
				date: null,
				optionId: 'opt-todo',
				iterationId: null,
			}],
			archived: false,
			deleted: false,
			sourceOccurredAt: '2026-11-02T00:00:00Z',
		});
	});

	it('item이 content보다 나중에 바뀌면 item 시각을 버전으로 쓴다', () => {
		const state = toProjectItemState(item({updatedAt: '2026-11-03T00:00:00Z'}), 'fallback');

		expect(state.sourceOccurredAt).toBe('2026-11-03T00:00:00Z');
	});

	it('draft issue는 저장소 없이 draft로 표시한다', () => {
		const state = toProjectItemState(item({
			type: 'DRAFT_ISSUE',
			content: {
				__typename: 'DraftIssue', id: 'DI_1', title: '초안', updatedAt: '2026-11-01T00:00:00Z',
			},
		}), 'fallback');

		expect(state.content).toMatchObject({title: '초안', repository: null, draft: true});
	});

	it('조직이 아닌 owner는 조직 login으로 보지 않는다', () => {
		expect(organizationLogin({__typename: 'User', login: 'octocat'})).toBeNull();
		expect(organizationLogin({__typename: 'Organization', login: 'My-Org'})).toBe('my-org');
	});

	it('field 값의 종류마다 해당 값만 채운다', () => {
		expect(toFieldValueState({__typename: 'ProjectV2ItemFieldIterationValue', iterationId: 'it-1', field: {id: 'F', name: 'Sprint'}}))
			.toMatchObject({dataType: 'ITERATION', iterationId: 'it-1', optionId: null});
		expect(toFieldValueState({__typename: 'ProjectV2ItemFieldNumberValue', number: 3, field: {id: 'F', name: 'Points'}}))
			.toMatchObject({dataType: 'NUMBER', number: 3});
		expect(toFieldValueState({__typename: 'ProjectV2ItemFieldTextValue', text: 'x', field: {}})).toBeUndefined();
	});

	it('Project 설정을 field, view, item 순서로 바꾼다', () => {
		const project: GraphqlProject = {
			id: 'PVT_1',
			number: 3,
			title: 'Roadmap',
			url: 'https://github.com/orgs/my-org/projects/3',
			closed: false,
			fields: {
				nodes: [
					{
						id: 'F_STATUS', name: 'Status', dataType: 'SINGLE_SELECT', options: [{id: 'opt-todo', name: 'Todo', color: 'GRAY'}],
					},
					{
						id: 'F_SPRINT',
						name: 'Sprint',
						dataType: 'ITERATION',
						configuration: {
							iterations: [{
								id: 'it-2', title: 'Sprint 2', startDate: '2026-11-08', duration: 7,
							}],
							completedIterations: [{
								id: 'it-1', title: 'Sprint 1', startDate: '2026-11-01', duration: 7,
							}],
						},
					},
				],
			},
			views: {
				nodes: [{
					id: 'PVTV_1',
					number: 1,
					name: 'Board',
					layout: 'BOARD_LAYOUT',
					filter: '',
					verticalGroupByFields: {nodes: [{id: 'F_STATUS'}]},
					sortByFields: {nodes: [{direction: 'ASC', field: {id: 'F_SPRINT'}}]},
				}],
			},
		};

		const state = toProjectState('My-Org', project, ['PVTI_2', 'PVTI_1'], '2026-11-17T00:00:00Z');

		expect(state.owner).toBe('my-org');
		expect(state.fields[0].options).toEqual([{
			id: 'opt-todo', name: 'Todo', color: 'GRAY', description: '',
		}]);
		expect(state.fields[1].iterations.map(iteration => [iteration.id, iteration.completed])).toEqual([['it-1', true], ['it-2', false]]);
		expect(state.views[0]).toEqual({
			id: 'PVTV_1',
			number: 1,
			name: 'Board',
			layout: 'BOARD_LAYOUT',
			filter: null,
			visibleFieldIds: [],
			groupByFieldIds: [],
			verticalGroupByFieldIds: ['F_STATUS'],
			sortBy: [{fieldId: 'F_SPRINT', direction: 'ASC'}],
		});
		expect(state.itemIds).toEqual(['PVTI_2', 'PVTI_1']);
		expect(state.accessError).toBeNull();
	});
});
