import {type Nullable} from '../../../common/nullable.js';

export type ProjectFieldOptionState = {
	id: string;
	name: string;
	color: string;
	description: string;
};

export type ProjectIterationState = {
	id: string;
	title: string;
	startDate: string;
	duration: number;
	completed: boolean;
};

export type ProjectFieldState = {
	id: string;
	name: string;
	dataType: string;
	options: ProjectFieldOptionState[];
	iterations: ProjectIterationState[];
};

export type ProjectViewSortState = {
	fieldId: string;
	direction: string;
};

export type ProjectViewState = {
	id: string;
	number: number;
	name: string;
	layout: string;
	filter: Nullable<string>;
	visibleFieldIds: string[];
	groupByFieldIds: string[];
	verticalGroupByFieldIds: string[];
	sortBy: ProjectViewSortState[];
};

export type ProjectAccessError = 'NOT_FOUND' | 'FORBIDDEN';

/**
 GitHub Project 하나의 설정과 item 순서. `itemIds`는 보관되지 않은 item을 GitHub의 위치 순서대로 담는다.
 item 레코드에는 위치가 없으므로, 조회는 이 목록에 있는 item만 이 순서로 보여 준다.
 `accessError`가 있으면 Project를 찾을 수 없거나 앱에 Projects 권한이 없어 나머지 필드는 비어 있다.
 */
export type ProjectStateEvent = {
	owner: string;
	projectNumber: number;
	nodeId: Nullable<string>;
	title: string;
	url: string;
	closed: boolean;
	fields: ProjectFieldState[];
	views: ProjectViewState[];
	itemIds: string[];
	accessError: Nullable<ProjectAccessError>;
	deleted: boolean;
	sourceOccurredAt: string;
};
