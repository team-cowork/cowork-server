import {type Nullable} from '../../../common/nullable.js';

export type ProjectItemFieldValueState = {
	fieldId: string;
	fieldName: string;
	dataType: 'TEXT' | 'NUMBER' | 'DATE' | 'SINGLE_SELECT' | 'ITERATION';
	text: Nullable<string>;
	number: Nullable<number>;
	date: Nullable<string>;
	optionId: Nullable<string>;
	iterationId: Nullable<string>;
};

export type ProjectItemLabelState = {
	name: string;
	color: string;
};

export type ProjectItemContentState = {
	title: string;
	body?: string;
	number: Nullable<number>;
	state: Nullable<string>;
	url: Nullable<string>;
	repository: Nullable<string>;
	assignees: string[];
	labels: ProjectItemLabelState[];
	milestone: Nullable<string>;
	draft: boolean;
};

/**
 GitHub Project item 하나의 상태. 키는 item node ID다. 삭제 웹훅에는 Project 번호가 없어 `projectNumber`가 null일 수 있다.
 버전은 item과 content 중 나중에 바뀐 시각이다. 이슈 제목처럼 item 웹훅 없이 바뀌는 content도 새 버전으로 반영하기 위해서다.
 */
export type ProjectItemStateEvent = {
	owner: string;
	projectNodeId: string;
	projectNumber: Nullable<number>;
	itemId: string;
	type: 'ISSUE' | 'PULL_REQUEST' | 'DRAFT_ISSUE' | 'REDACTED';
	contentNodeId: Nullable<string>;
	content: Nullable<ProjectItemContentState>;
	fieldValues: ProjectItemFieldValueState[];
	archived: boolean;
	deleted: boolean;
	sourceOccurredAt: string;
};
